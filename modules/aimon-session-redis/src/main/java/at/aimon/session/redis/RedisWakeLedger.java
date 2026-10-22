package at.aimon.session.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.wake.WakeLedger;
import at.aimon.core.agent.session.wake.WakeState;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed {@link WakeLedger}: the idle-session waker's caps shared by every node (idle-wake design §4.3).
 *
 * <p>
 * <b>Layout.</b> One Redis STRING per session at {@code <keyPrefix>:<sessionId>}, holding the state as JSON (wake times
 * in epoch milliseconds). Every write sets an expiry ({@link #DEFAULT_TTL} unless given): a session that has not been
 * woken or settled for that long has no wakes left in its hour window, and only its streak is forgotten — the cap that
 * outlives an hour, reset after a day of quiet.
 *
 * <p>
 * <b>Atomic update.</b> The change is a Java function, so it cannot run inside Redis. {@link #update} reads the value,
 * applies the change, and writes through a Lua compare-and-set that refuses when the stored value is no longer the one
 * read; then it reads again and retries. {@code WATCH}/{@code MULTI} would not do: the connection is shared by every
 * thread of the node, and a transaction is per connection. All backend errors surface as {@link IllegalStateException}.
 */
public final class RedisWakeLedger implements WakeLedger {

    /** Default key prefix. */
    public static final String DEFAULT_KEY_PREFIX = "aimon:session:wake";

    /** Default expiry of a session's entry after its last write. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(1);

    // Contention on one session is a few nodes waking it and settling its turn; a refused CAS is retried at once.
    private static final int MAX_CAS_ATTEMPTS = 200;

    // KEYS[1]=key, ARGV[1]=expected value ("" when absent), ARGV[2]=new value, ARGV[3]=ttl in milliseconds.
    private static final String CAS_SCRIPT = "local v = redis.call('GET', KEYS[1]); " + "if not v then v = '' end; "
            + "if v ~= ARGV[1] then return 0 end; " + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]); "
            + "return 1;";

    private final RedisCommands<String, String> commands;
    private final ObjectMapper mapper;
    private final String keyPrefix;
    private final Duration ttl;

    /**
     * Creates a ledger with the default key prefix and expiry.
     *
     * @param connection
     *            the Lettuce connection (must not be null; owned by the caller)
     */
    public RedisWakeLedger(StatefulRedisConnection<String, String> connection) {
        this(connection, DEFAULT_KEY_PREFIX, DEFAULT_TTL);
    }

    /**
     * Creates a ledger with an explicit key prefix and expiry.
     *
     * @param connection
     *            the Lettuce connection (must not be null; owned by the caller)
     * @param keyPrefix
     *            the key prefix (must not be null)
     * @param ttl
     *            how long a session's entry outlives its last write (must be positive)
     */
    public RedisWakeLedger(StatefulRedisConnection<String, String> connection, String keyPrefix, Duration ttl) {
        this.commands = Objects.requireNonNull(connection, "connection must not be null").sync();
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        this.mapper = new ObjectMapper();
    }

    @Override
    public WakeState get(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            final String json = commands.get(key(sessionId));
            return json == null ? WakeState.empty() : decode(json);
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error reading the wake ledger of " + sessionId, e);
        }
    }

    @Override
    public WakeState update(SessionId sessionId, UnaryOperator<WakeState> change) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(change, "change must not be null");
        final String key = key(sessionId);
        try {
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                final String stored = commands.get(key);
                final WakeState current = stored == null ? WakeState.empty() : decode(stored);
                final WakeState next = Objects.requireNonNull(change.apply(current), "change returned null");
                final Long applied = commands.eval(CAS_SCRIPT, ScriptOutputType.INTEGER, new String[]{key},
                        stored == null ? "" : stored, encode(next), Long.toString(ttl.toMillis()));
                if (applied != null && applied == 1L) {
                    return next;
                }
            }
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error updating the wake ledger of " + sessionId, e);
        }
        throw new IllegalStateException(
                "Wake ledger of " + sessionId + " changed under every one of " + MAX_CAS_ATTEMPTS + " attempts");
    }

    @Override
    public void remove(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        try {
            commands.del(key(sessionId));
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error removing the wake ledger of " + sessionId, e);
        }
    }

    private String key(SessionId sessionId) {
        return keyPrefix + ":" + sessionId.value();
    }

    private String encode(WakeState state) {
        final ObjectNode node = mapper.createObjectNode();
        final ArrayNode recent = node.putArray("recentWakes");
        state.getRecentWakes().forEach(at -> recent.add(at.toEpochMilli()));
        node.put("consecutive", state.getConsecutive());
        if (state.getTurnCountAfterWake().isPresent()) {
            node.put("turnCountAfterWake", state.getTurnCountAfterWake().getAsInt());
        }
        node.put("outstanding", state.isOutstanding());
        return node.toString();
    }

    private WakeState decode(String json) {
        final JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable wake ledger entry: " + e.getOriginalMessage(), e);
        }
        final List<Instant> recent = new ArrayList<>();
        node.path("recentWakes").forEach(millis -> recent.add(Instant.ofEpochMilli(millis.asLong())));
        final JsonNode turnCount = node.get("turnCountAfterWake");
        return WakeState.of(recent, node.path("consecutive").asInt(),
                turnCount == null || turnCount.isNull() ? null : turnCount.asInt(),
                node.path("outstanding").asBoolean());
    }
}
