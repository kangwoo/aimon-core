package at.aimon.session.redis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionRecordStoreException;
import at.aimon.core.agent.session.store.SessionRecordCodec;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.store.StoredSessionRecord;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed {@link SessionRecordStore}: the transcript half of the session, which until now only the in-memory store
 * held.
 *
 * <p>
 * Everything else about a session was already distributed — the lease, the signal bus, the inbox, the idempotency
 * ledger — so a fleet could hand a session from one node to another and lose the conversation while keeping the
 * bookkeeping about it. This is the store that closes that gap.
 *
 * <h2>Key layout</h2>
 *
 * <pre>
 *   {keyPrefix}:{sessionId}   HASH
 *       sessionId               — always present; this is what makes the record exist
 *       transcript              — absent until the first merge
 *       agentRef                — absent while unbound
 *       compactionFailureCount  — absent until the first increment
 *       sessionTotals           — absent until the first turn ends
 *       budgetOverride          — absent when there is no override
 * </pre>
 *
 * <p>
 * <b>Absent is not a separate state.</b> Every field above decodes to the same default the in-memory store reports for
 * a record that has one — no transcript, no totals, no override, a count of zero — so the first turn of a session,
 * which runs against a record the claim path provisioned and nothing has written to yet, behaves identically on all
 * three backends.
 *
 * <p>
 * {@code sessionId} carries no information the key does not already have, and exists for one reason: a Redis hash with
 * no fields does not exist. Without it, provisioning a record without binding it — what the inbox drain path does, and
 * what {@link #provision(SessionId)} is for — would write nothing at all and leave {@link #exists(SessionId)} false on
 * a session that was just taken in hand.
 *
 * <p>
 * There is no {@code updatedAt} here, unlike the Mongo and Postgres stores. Both get a server-side timestamp for free
 * ({@code $$NOW}, {@code now()}); Redis has none available to a plain {@code HSET}, and writing a per-node clock into a
 * field nothing reads would produce a value that misorders concurrent writes with no reader to notice. Operators have
 * {@code OBJECT IDLETIME} and their keyspace notifications instead.
 *
 * <h2>Every write is partial, and each is one round trip</h2>
 *
 * <p>
 * {@link SessionRecordStore} has no full-record write because four writers own four fields, and the {@code @implSpec}
 * on each method asks for one atomic operation rather than a read-modify-write. Redis makes that cheap: a hash field is
 * exactly the granularity the SPI wants, so nothing here reads a field it does not own.
 *
 * <ul>
 * <li>{@link #provision(SessionId, String) provision} — one script: {@code HSET} the sentinel, {@code HSETNX} the
 * binding, {@code HGETALL} the result. {@code HSETNX} is the binding rule — an existing {@code agentRef} wins, so a
 * node that wanted a different agent learns so from the returned record instead of having already stolen the session
 * <li>{@link #mergeFromSnapshot(SessionSnapshot) mergeFromSnapshot} — a plain {@code HSET} of the transcript (plus the
 * sentinel, in case the record is being created here); the side fields are not named, so a concurrent writer of any of
 * them cannot lose its write
 * <li>{@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget) setTotalsAndBudgetOverride} — one
 * script carrying both fields ({@code HDEL} for a cleared override), so the pair moves together
 * <li>{@link #incrementCompactionFailureCount(SessionId) incrementCompactionFailureCount} — {@code HINCRBY}, which
 * returns the value it produced, so two nodes incrementing concurrently get two different numbers
 * <li>{@link #resetCompactionFailureCount(SessionId) resetCompactionFailureCount} — an {@code HSET} of that field alone
 * </ul>
 *
 * <p>
 * Every write except the first two is wrapped in an {@code EXISTS} guard inside the same script. That guard is not
 * defensive coding: {@code HSET} creates the hash it writes to, so without it a side-field write against a session that
 * was never provisioned would conjure a record holding only that field, where the SPI says it must be a no-op.
 *
 * <h2>Fencing</h2>
 *
 * <p>
 * None here, deliberately. This class implements the plain SPI; writes are fenced against the node's lease when the
 * store is reached through {@code SessionStore.records()}, which is where the lease lives. An assembler that hands this
 * store around directly gets last-write-wins between nodes on the same session — the same bargain the in-memory store
 * offers within one JVM.
 */
public final class RedisSessionRecordStore implements SessionRecordStore {

    /**
     * Default key prefix for session-record hashes.
     *
     * <p>
     * The one prefix in this module spelled for the session rather than the conversation. {@code aimon:conv},
     * {@code aimon:inbox} and {@code aimon:conv:idem} are contracts with keys already live in deployed clusters; this
     * one had none when it was added. It is frozen from here on all the same — {@code RedisKeyPrefixFreezeTest} pins
     * it, because from the first deployment there are keys behind it.
     */
    public static final String DEFAULT_KEY_PREFIX = "aimon:session:record";

    private static final String F_SESSION_ID = "sessionId";

    private static final String F_TRANSCRIPT = "transcript";

    private static final String F_AGENT_REF = "agentRef";

    private static final String F_COMPACTION_FAILURE_COUNT = "compactionFailureCount";

    private static final String F_SESSION_TOTALS = "sessionTotals";

    private static final String F_BUDGET_OVERRIDE = "budgetOverride";

    // KEYS = [recordKey], ARGV = [sessionId, agentRef?]. Returns the hash as a flat field/value array.
    //
    // ARGV[2] is genuinely absent — not an empty-string sentinel — when the caller provisions without binding, so a
    // Lua `if ARGV[2] then` is the whole test and an agent named "" could never be confused with "no agent".
    private static final String PROVISION_SCRIPT = "redis.call('HSET', KEYS[1], 'sessionId', ARGV[1]) "
            + "if ARGV[2] then redis.call('HSETNX', KEYS[1], 'agentRef', ARGV[2]) end "
            + "return redis.call('HGETALL', KEYS[1])";

    // KEYS = [recordKey], ARGV = [sessionTotals, budgetOverride?]. Returns 1 when written, 0 when there is no record.
    private static final String SET_PAIR_SCRIPT = "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
            + "redis.call('HSET', KEYS[1], 'sessionTotals', ARGV[1]) "
            + "if ARGV[2] then redis.call('HSET', KEYS[1], 'budgetOverride', ARGV[2]) "
            + "else redis.call('HDEL', KEYS[1], 'budgetOverride') end " + "return 1";

    private static final String INCREMENT_SCRIPT = "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
            + "return redis.call('HINCRBY', KEYS[1], 'compactionFailureCount', 1)";

    private static final String RESET_SCRIPT = "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
            + "redis.call('HSET', KEYS[1], 'compactionFailureCount', 0) " + "return 1";

    private static final int SCAN_BATCH = 256;

    private final RedisCommands<String, String> commands;
    private final String keyPrefix;

    /**
     * Creates a store over the default key prefix.
     *
     * @param connection
     *            the Redis connection (must not be null)
     */
    public RedisSessionRecordStore(StatefulRedisConnection<String, String> connection) {
        this(connection, DEFAULT_KEY_PREFIX);
    }

    /**
     * Creates a store over a named key prefix.
     *
     * @param connection
     *            the Redis connection (must not be null)
     * @param keyPrefix
     *            the prefix for session-record hashes (must not be null)
     */
    public RedisSessionRecordStore(StatefulRedisConnection<String, String> connection, String keyPrefix) {
        Objects.requireNonNull(connection, "connection must not be null");
        this.commands = connection.sync();
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
    }

    @Override
    public void mergeFromSnapshot(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        final SessionId id = snapshot.getSessionId();
        // Names the transcript and the sentinel, nothing else. Everything the snapshot cannot carry — the binding, the
        // counter, the totals, the override — is simply not in the write, so it survives by not being mentioned
        // rather than by being read and written back.
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put(F_SESSION_ID, id.value());
        fields.put(F_TRANSCRIPT, SessionRecordCodec.encodeTranscript(snapshot));
        try {
            commands.hset(recordKey(id), fields);
        } catch (RedisException e) {
            throw failure("mergeFromSnapshot", id, e);
        }
    }

    @Override
    public SessionRecordView provision(SessionId sessionId, String agentRef) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final String[] argv = agentRef == null
                ? new String[]{sessionId.value()}
                : new String[]{sessionId.value(), agentRef};
        try {
            final List<Object> flat = commands.eval(PROVISION_SCRIPT, ScriptOutputType.MULTI,
                    new String[]{recordKey(sessionId)}, argv);
            return toRecord(sessionId, toMap(flat));
        } catch (RedisException e) {
            throw failure("provision", sessionId, e);
        }
    }

    @Override
    public void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals, ExecutionBudget budgetOverride) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(totals, "totals must not be null");
        final String encodedTotals = SessionRecordCodec.encodeTotals(totals);
        final String encodedOverride = SessionRecordCodec.encodeBudgetOverride(budgetOverride);
        // A null override does not mean "leave it alone": it clears one that was set, so the next open falls back to
        // the opener's default. Omitting the argument is what tells the script to HDEL rather than HSET.
        final String[] argv = encodedOverride == null
                ? new String[]{encodedTotals}
                : new String[]{encodedTotals, encodedOverride};
        try {
            commands.eval(SET_PAIR_SCRIPT, ScriptOutputType.INTEGER, new String[]{recordKey(sessionId)}, argv);
        } catch (RedisException e) {
            throw failure("setTotalsAndBudgetOverride", sessionId, e);
        }
    }

    @Override
    public int incrementCompactionFailureCount(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            // HINCRBY returns the value its own increment produced. A read-after-write would let another node's
            // increment land in between and hand both callers the same number — the one miscount a circuit breaker
            // must not make. The EXISTS guard makes a missing record answer 0, i.e. "nothing has been counted".
            final Long updated = commands.eval(INCREMENT_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{recordKey(sessionId)});
            return updated == null ? 0 : updated.intValue();
        } catch (RedisException e) {
            throw failure("incrementCompactionFailureCount", sessionId, e);
        }
    }

    @Override
    public void resetCompactionFailureCount(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            commands.eval(RESET_SCRIPT, ScriptOutputType.INTEGER, new String[]{recordKey(sessionId)});
        } catch (RedisException e) {
            throw failure("resetCompactionFailureCount", sessionId, e);
        }
    }

    @Override
    public Optional<SessionRecordView> load(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            final Map<String, String> hash = commands.hgetall(recordKey(sessionId));
            return hash.isEmpty() ? Optional.empty() : Optional.of(toRecord(sessionId, hash));
        } catch (RedisException e) {
            throw failure("load", sessionId, e);
        }
    }

    @Override
    public void delete(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            commands.del(recordKey(sessionId));
        } catch (RedisException e) {
            throw failure("delete", sessionId, e);
        }
    }

    @Override
    public List<SessionId> listSessionIds() {
        final List<SessionId> ids = new ArrayList<>();
        try {
            // The id is the key suffix, so this needs no HGET per key. Session ids may themselves contain ':', which
            // is why the suffix is taken by prefix length rather than by splitting.
            for (String key : scanKeys()) {
                ids.add(SessionId.of(key.substring(keyPrefix.length() + 1)));
            }
        } catch (RedisException e) {
            throw new SessionRecordStoreException("Redis error during listSessionIds", e);
        }
        return ids;
    }

    @Override
    public boolean exists(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            return commands.exists(recordKey(sessionId)) == 1L;
        } catch (RedisException e) {
            throw failure("exists", sessionId, e);
        }
    }

    @Override
    public void clear() {
        try {
            final List<String> keys = scanKeys();
            if (!keys.isEmpty()) {
                commands.del(keys.toArray(new String[0]));
            }
        } catch (RedisException e) {
            throw new SessionRecordStoreException("Redis error during clear", e);
        }
    }

    private List<String> scanKeys() {
        final List<String> keys = new ArrayList<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        final ScanArgs args = ScanArgs.Builder.matches(keyPrefix + ":*").limit(SCAN_BATCH);
        do {
            final var scan = commands.scan(cursor, args);
            keys.addAll(scan.getKeys());
            cursor = scan;
        } while (!cursor.isFinished());
        return keys;
    }

    private String recordKey(SessionId sessionId) {
        return keyPrefix + ":" + sessionId.value();
    }

    private StoredSessionRecord toRecord(SessionId sessionId, Map<String, String> hash) {
        try {
            return StoredSessionRecord.builder(sessionId)
                    .transcript(SessionRecordCodec.decodeTranscript(sessionId, hash.get(F_TRANSCRIPT)))
                    .agentRef(hash.get(F_AGENT_REF)).compactionFailureCount(intValue(hash))
                    .sessionTotals(SessionRecordCodec.decodeTotals(hash.get(F_SESSION_TOTALS)))
                    .budgetOverride(SessionRecordCodec.decodeBudgetOverride(hash.get(F_BUDGET_OVERRIDE))).build();
        } catch (RuntimeException e) {
            // A record that will not decode is an infrastructure failure from the caller's side, not a missing one:
            // reporting it as absent would let a session silently resume with an empty history.
            throw new SessionRecordStoreException("Failed to decode stored session record for " + sessionId, e);
        }
    }

    private static Map<String, String> toMap(List<Object> flat) {
        final Map<String, String> hash = new LinkedHashMap<>();
        if (flat == null) {
            return hash;
        }
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            hash.put(String.valueOf(flat.get(i)), String.valueOf(flat.get(i + 1)));
        }
        return hash;
    }

    private static int intValue(Map<String, String> hash) {
        final String raw = hash.get(F_COMPACTION_FAILURE_COUNT);
        return raw == null ? 0 : Integer.parseInt(raw);
    }

    private static SessionRecordStoreException failure(String operation, SessionId sessionId, Exception cause) {
        return new SessionRecordStoreException("Redis error during " + operation + " for " + sessionId, cause);
    }
}
