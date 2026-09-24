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
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed {@link SessionLogSegmentStore}: two hashes per session.
 *
 * <pre>{@code
 * <prefix>:<sessionId>          field <segmentId> -> {"fromSeq":..,"toSeq":..,"entryCount":..,"createdAt":"..",
 *                                                     "payload":"<encoded entries>"}
 * <prefix>:<sessionId>:created  field <segmentId> -> "<createdAt, ISO-8601>"
 * }</pre>
 *
 * <p>
 * The second hash is what {@link #list} reads, so garbage collection lists a session without pulling every payload
 * over the wire. Both hashes change together in one Lua script — {@link #put} refuses an id that already exists
 * rather than overwriting it, and {@link #delete} removes the field from both — so they cannot disagree. A session
 * delete drops both keys.
 *
 * <p>
 * Keys carry no TTL: a segment lives as long as the record's manifest names it, and garbage collection decides when
 * it goes. Not fenced, like the record store: deletes are fenced when reached through
 * {@code SessionStore.segments(...)}.
 */
public final class RedisSessionLogSegmentStore implements SessionLogSegmentStore {

    /** Default key prefix. Frozen from its first deployment on — {@code RedisKeyPrefixFreezeTest} pins it. */
    public static final String DEFAULT_KEY_PREFIX = "aimon:session:segment";

    private static final String CREATED_SUFFIX = ":created";

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

    private final RedisCommands<String, String> commands;
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
        Objects.requireNonNull(connection, "connection must not be null");
        this.commands = connection.sync();
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
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

    private String dataKey(SessionId sessionId) {
        return keyPrefix + ":" + sessionId.value();
    }

    private String createdKey(SessionId sessionId) {
        return dataKey(sessionId) + CREATED_SUFFIX;
    }

    private static SessionLogSegmentStoreException failure(String operation, SessionId sessionId, Exception cause) {
        return new SessionLogSegmentStoreException("Redis error during " + operation + " for " + sessionId, cause);
    }
}
