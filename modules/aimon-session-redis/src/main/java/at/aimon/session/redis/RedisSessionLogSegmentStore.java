package at.aimon.session.redis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLogSegmentStoreException;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.store.SegmentInfo;
import at.aimon.core.agent.session.store.SegmentScanPage;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

/**
 * Redis-backed {@link SessionLogSegmentStore}: two hashes per session.
 *
 * <pre>{@code
 * <prefix>:{s:<sessionId>}:data     field <segmentId> -> {"fromSeq":..,"toSeq":..,"entryCount":..,"createdAt":"..",
 *                                                          "payload":"<encoded entries>"}
 * <prefix>:{s:<sessionId>}:created  field <segmentId> -> "<createdAt, ISO-8601>"
 * }</pre>
 *
 * <p>
 * The layout is chosen for two properties. <b>No two sessions share a key:</b> both keys end in a fixed suffix after
 * the session id, so one session's key can never spell another's — an earlier {@code <prefix>:<sid>} /
 * {@code <prefix>:<sid>:created} pair let session {@code X:created}'s data hash be session {@code X}'s creation hash.
 * <b>Both keys of a session live in one Redis Cluster slot:</b> the braces are a hash tag, so the two-key Lua scripts
 * and the two-key {@code DEL} never raise {@code CROSSSLOT}. The tag opens with the fixed marker {@code s:} so it is
 * never empty: Redis hashes from the first {@code '{'} to the first {@code '}'} after it, and hashes the <em>whole</em>
 * key when that span is empty — which a bare {@code {<sessionId>}} would make it for an id starting with
 * {@code '}'}, sending the two keys to different slots. A session id containing {@code '}'} anywhere only shortens
 * the tag to {@code s:} plus the id's prefix, and both keys share that tag. A custom prefix must not contain
 * {@code '{'}, or it would become the tag and put every session in one slot.
 *
 * <p>
 * The second hash is what {@link #list} reads, so garbage collection lists a session without pulling every payload
 * over the wire. Both hashes change together in one Lua script — {@link #put} refuses an id that already exists
 * rather than overwriting it, and {@link #delete} removes the field from both — so they cannot disagree. A session
 * delete drops both keys.
 *
 * <p>
 * {@link #scanSessions} walks the {@code :created} keys with {@code SCAN}, so no index key has to be kept in step with
 * the per-session keys — an index would sit in its own cluster slot and could not change atomically with them.
 * {@code SCAN} only walks the node it is sent to. Over a standalone connection that node is the whole keyspace; over a
 * cluster connection the scan walks every master in turn, carrying the node in its cursor, so a session on any
 * master is reported. A topology change during a pass can still hide keys from that pass — {@code SCAN}'s own caveat
 * — and the next pass sees them.
 *
 * <p>
 * Keys carry no TTL: a segment lives as long as the record's manifest names it, and garbage collection decides when
 * it goes. Not fenced, like the record store: deletes are fenced when reached through
 * {@code SessionStore.segments(...)}.
 */
public final class RedisSessionLogSegmentStore implements SessionLogSegmentStore {

    /** Default key prefix. Frozen from its first deployment on — {@code RedisKeyPrefixFreezeTest} pins it. */
    public static final String DEFAULT_KEY_PREFIX = "aimon:session:segment";

    /** Opens the hash tag, so the tag is never empty whatever the session id starts with. */
    private static final String TAG_OPEN = ":{s:";
    private static final String DATA_SUFFIX = "}:data";
    private static final String CREATED_SUFFIX = "}:created";

    private static final String F_FROM_SEQ = "fromSeq";
    private static final String F_TO_SEQ = "toSeq";
    private static final String F_ENTRY_COUNT = "entryCount";
    private static final String F_CREATED_AT = "createdAt";
    private static final String F_PAYLOAD = "payload";

    // KEYS = [dataKey, createdKey], ARGV = [segmentId, document, createdAt]. Returns 1 when written, 0 on a duplicate.
    private static final String PUT_SCRIPT = "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then return 0 end "
            + "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) " + "redis.call('HSET', KEYS[2], ARGV[1], ARGV[3]) "
            + "return 1";

    // KEYS = [dataKey, createdKey], ARGV = [segmentId].
    private static final String DELETE_SCRIPT = "redis.call('HDEL', KEYS[1], ARGV[1]) "
            + "redis.call('HDEL', KEYS[2], ARGV[1]) " + "return 1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedisClusterCommands<String, String> commands;
    private final KeyspaceScanner scanner;
    private final String keyPrefix;

    /**
     * @param connection
     *            the connection (must not be null)
     */
    public RedisSessionLogSegmentStore(StatefulRedisConnection<String, String> connection) {
        this(connection, DEFAULT_KEY_PREFIX);
    }

    /**
     * @param connection
     *            the connection (must not be null)
     * @param keyPrefix
     *            the key prefix (must not be null)
     */
    public RedisSessionLogSegmentStore(StatefulRedisConnection<String, String> connection, String keyPrefix) {
        this(Objects.requireNonNull(connection, "connection must not be null").sync(),
                KeyspaceScanner.standalone(connection.sync()), keyPrefix);
    }

    /**
     * A store over a Redis Cluster. Every per-session command goes to the slot its hash tag names; the store-wide scan
     * walks every master.
     *
     * @param connection
     *            the cluster connection (must not be null)
     */
    public RedisSessionLogSegmentStore(StatefulRedisClusterConnection<String, String> connection) {
        this(connection, DEFAULT_KEY_PREFIX);
    }

    /**
     * @param connection
     *            the cluster connection (must not be null)
     * @param keyPrefix
     *            the key prefix (must not be null, must not contain {@code '{'})
     */
    public RedisSessionLogSegmentStore(StatefulRedisClusterConnection<String, String> connection, String keyPrefix) {
        this(Objects.requireNonNull(connection, "connection must not be null").sync(),
                KeyspaceScanner.cluster(connection), keyPrefix);
    }

    /** Package-private so a test can drive the per-node scan without a cluster. */
    RedisSessionLogSegmentStore(RedisClusterCommands<String, String> commands, KeyspaceScanner scanner,
            String keyPrefix) {
        // Not null-checked, as the standalone constructors never checked what sync() answered: the key layout
        // (dataKey / createdKey) needs no commands, and a null here fails on first use.
        this.commands = commands;
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        if (keyPrefix.indexOf('{') >= 0) {
            throw new IllegalArgumentException("keyPrefix must not contain '{' - it would become the hash tag of "
                    + "every key and put all sessions in one cluster slot: " + keyPrefix);
        }
    }

    @Override
    public void put(SessionLogSegment segment) {
        Objects.requireNonNull(segment, "segment must not be null");
        final ObjectNode doc = MAPPER.createObjectNode();
        doc.put(F_FROM_SEQ, segment.getFromSeq());
        doc.put(F_TO_SEQ, segment.getToSeq());
        doc.put(F_ENTRY_COUNT, segment.getEntryCount());
        doc.put(F_CREATED_AT, segment.getCreatedAt().toString());
        doc.put(F_PAYLOAD, segment.getPayload());
        final SessionId sessionId = segment.getSessionId();
        final Long written;
        try {
            written = commands.eval(PUT_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{dataKey(sessionId), createdKey(sessionId)}, segment.getId().value(),
                    MAPPER.writeValueAsString(doc), segment.getCreatedAt().toString());
        } catch (RedisException | JsonProcessingException e) {
            throw failure("put", sessionId, e);
        }
        if (written == null || written == 0L) {
            throw new SessionLogSegmentStoreException("Segment " + segment.getId() + " already exists for session "
                    + sessionId + "; segment ids are never reused");
        }
    }

    @Override
    public Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try {
            final String raw = commands.hget(dataKey(sessionId), id.value());
            if (raw == null) {
                return Optional.empty();
            }
            final JsonNode doc = MAPPER.readTree(raw);
            return Optional.of(SessionLogSegment.builder().sessionId(sessionId).id(id)
                    .fromSeq(doc.get(F_FROM_SEQ).asLong()).toSeq(doc.get(F_TO_SEQ).asLong())
                    .entryCount(doc.get(F_ENTRY_COUNT).asInt()).payload(doc.get(F_PAYLOAD).asText())
                    .createdAt(Instant.parse(doc.get(F_CREATED_AT).asText())).build());
        } catch (Exception e) {
            throw failure("get", sessionId, e);
        }
    }

    @Override
    public List<SegmentInfo> list(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            final Map<String, String> created = commands.hgetall(createdKey(sessionId));
            final List<SegmentInfo> infos = new ArrayList<>(created.size());
            for (Map.Entry<String, String> e : created.entrySet()) {
                infos.add(SegmentInfo.of(SegmentId.of(e.getKey()), Instant.parse(e.getValue())));
            }
            return infos;
        } catch (RuntimeException e) {
            throw failure("list", sessionId, e);
        }
    }

    /**
     * A {@code SCAN} over the sessions' {@code :created} keys; the cursor is Redis's own over a standalone connection,
     * and {@code <nodeId>:<nodeCursor>} over a cluster connection. Looser than the other
     * backends in the ways the SPI allows: every session with any segment is reported regardless of
     * {@code createdBefore}, a session may appear twice in a pass, and {@code limit} is passed as {@code COUNT}, which
     * Redis treats as a hint.
     */
    @Override
    public SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit) {
        Objects.requireNonNull(createdBefore, "createdBefore must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        final String keyStart = keyPrefix + TAG_OPEN;
        try {
            final KeyspaceScanner.Step page = scanner.next(cursor,
                    ScanArgs.Builder.matches(globEscape(keyStart) + "*" + globEscape(CREATED_SUFFIX)).limit(limit));
            final List<SessionId> ids = new ArrayList<>(page.getKeys().size());
            for (String key : page.getKeys()) {
                // The key ends in the created suffix and starts with the prefix; what lies between is the session id,
                // whatever it contains — a session id holding "}:created" still ends before the last suffix.
                if (key.startsWith(keyStart) && key.endsWith(CREATED_SUFFIX)
                        && key.length() > keyStart.length() + CREATED_SUFFIX.length()) {
                    ids.add(SessionId.of(key.substring(keyStart.length(), key.length() - CREATED_SUFFIX.length())));
                }
            }
            return SegmentScanPage.of(ids, page.getNextCursor());
        } catch (RedisException e) {
            throw new SessionLogSegmentStoreException("Redis error during scanSessions", e);
        }
    }

    @Override
    public void delete(SessionId sessionId, SegmentId id) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try {
            commands.eval(DELETE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{dataKey(sessionId), createdKey(sessionId)}, id.value());
        } catch (RedisException e) {
            throw failure("delete", sessionId, e);
        }
    }

    @Override
    public void deleteAll(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            commands.del(dataKey(sessionId), createdKey(sessionId));
        } catch (RedisException e) {
            throw failure("deleteAll", sessionId, e);
        }
    }

    /** The payload hash of a session. Package-private so the freeze test can pin the layout. */
    String dataKey(SessionId sessionId) {
        return keyPrefix + TAG_OPEN + sessionId.value() + DATA_SUFFIX;
    }

    /** The creation-time hash of a session. Package-private so the freeze test can pin the layout. */
    String createdKey(SessionId sessionId) {
        return keyPrefix + TAG_OPEN + sessionId.value() + CREATED_SUFFIX;
    }

    /** Escapes the glob metacharacters of {@code SCAN MATCH}, so a prefix is matched literally. */
    private static String globEscape(String literal) {
        final StringBuilder out = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            final char c = literal.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static SessionLogSegmentStoreException failure(String operation, SessionId sessionId, Exception cause) {
        return new SessionLogSegmentStoreException("Redis error during " + operation + " for " + sessionId, cause);
    }
}
