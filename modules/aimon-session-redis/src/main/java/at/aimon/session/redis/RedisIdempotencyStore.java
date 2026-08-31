package at.aimon.session.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.PutResult;
import at.aimon.session.redis.internal.IdempotencyEntryCodec;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed {@link IdempotencyStore} per design §9.2.
 *
 * <p>
 * Each idempotency key maps to one Redis STRING containing the JSON-serialized {@link IdempotencyEntry}; the entry's
 * original TTL is stamped into the JSON so {@code touch} can re-arm PEXPIRE without an external parameter.
 *
 * <p>
 * The four atomic transitions ({@code putIfAbsent}, {@code markDone}, {@code touch}, {@code compareAndReset}) are
 * implemented as Lua scripts that read the JSON, run a small text-level check on the {@code "status"} and
 * {@code "holderId"} fields, and either write a replacement value or delete the key. The status / holder fields are
 * always emitted in fixed positions in the JSON object (see {@link IdempotencyEntryCodec}) so the Lua-side
 * {@code string.find} pattern matching is deterministic.
 *
 * <p>
 * For simplicity, transitions that need to update the JSON ({@code markDone}, {@code touch}) follow a
 * "GET → mutate in app → SET" pattern protected by Lua. {@link #findStaleInFlight} uses Redis SCAN.
 */
public final class RedisIdempotencyStore implements IdempotencyStore {

    public static final String DEFAULT_KEY_PREFIX = "aimon:conv:idem";

    /** Primary TTL applied when an entry transitions to {@link IdempotencyEntry.Status#DONE}. */
    public static final Duration DEFAULT_DONE_TTL = Duration.ofHours(24);

    /**
     * Replace the stored value (ARGV[2]) with a new PX (ARGV[3]) only while the entry is IN_FLIGHT and held by ARGV[1].
     * Backs both {@code touch} and {@code releaseHolder} — they differ only in the replacement JSON they compute.
     */
    private static final String REPLACE_IF_HELD_SCRIPT = "local v = redis.call('GET', KEYS[1]); "
            + "if not v then return 0 end; " + "if string.find(v, '\"status\":\"IN_FLIGHT\"', 1, true) and "
            + "   string.find(v, '\"holderId\":\"' .. ARGV[1] .. '\"', 1, true) then "
            + "  redis.call('SET', KEYS[1], ARGV[2], 'PX', tonumber(ARGV[3])); " + "  return 1; " + "end; "
            + "return 0;";

    private static final String COMPARE_AND_RESET_SCRIPT = "local v = redis.call('GET', KEYS[1]); "
            + "if not v then return 0 end; " + "if string.find(v, '\"status\":\"IN_FLIGHT\"', 1, true) and "
            + "   string.find(v, '\"holderId\":\"' .. ARGV[1] .. '\"', 1, true) then "
            + "  redis.call('DEL', KEYS[1]); " + "  return 1; " + "end; " + "return 0;";

    // The mirror image of COMPARE_AND_RESET_SCRIPT: that one deletes an entry held by a named holder, this one deletes
    // an entry held by nobody. The codec writes a holderless reservation as an explicit null rather than omitting the
    // field, so the absence of a holder is something the script can match on rather than something it has to infer.
    private static final String DISCARD_RESERVATION_SCRIPT = "local v = redis.call('GET', KEYS[1]); "
            + "if not v then return 0 end; " + "if string.find(v, '\"status\":\"IN_FLIGHT\"', 1, true) and "
            + "   string.find(v, '\"holderId\":null', 1, true) then " + "  redis.call('DEL', KEYS[1]); "
            + "  return 1; " + "end; " + "return 0;";

    private static final String MARK_DONE_SCRIPT = "local v = redis.call('GET', KEYS[1]); "
            + "if not v then return 0 end; " + "redis.call('SET', KEYS[1], ARGV[1], 'PX', tonumber(ARGV[2])); "
            + "return 1;";

    private final RedisCommands<String, String> commands;
    private final IdempotencyEntryCodec codec;
    private final String keyPrefix;
    private final Duration doneTtl;
    private final Clock clock;

    public RedisIdempotencyStore(StatefulRedisConnection<String, String> connection) {
        this(connection, defaultMapper(), DEFAULT_KEY_PREFIX, DEFAULT_DONE_TTL, Clock.systemUTC());
    }

    public RedisIdempotencyStore(StatefulRedisConnection<String, String> connection, ObjectMapper mapper,
            String keyPrefix, Duration doneTtl, Clock clock) {
        Objects.requireNonNull(connection, "connection must not be null");
        this.commands = connection.sync();
        this.codec = new IdempotencyEntryCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        this.doneTtl = Objects.requireNonNull(doneTtl, "doneTtl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public PutResult putIfAbsent(String key, IdempotencyEntry entry, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        final String fullKey = entryKey(key);
        try {
            final String json = codec.encode(entry, ttl);
            final String acquired = commands.set(fullKey, json, SetArgs.Builder.nx().px(ttl.toMillis()));
            if (acquired != null) {
                return PutResult.inserted();
            }
            final String existing = commands.get(fullKey);
            if (existing == null) {
                return PutResult.inserted();
            }
            return PutResult.existing(codec.decode(existing));
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during putIfAbsent for key " + key, e);
        }
    }

    @Override
    public void markDone(String key, AgentExecutionResult result) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(result, "result must not be null");
        final String fullKey = entryKey(key);
        try {
            final String existing = commands.get(fullKey);
            if (existing == null) {
                return;
            }
            final IdempotencyEntry prev = codec.decode(existing);
            final IdempotencyEntry done = IdempotencyEntry.builder().key(prev.getKey()).sessionId(prev.getSessionId())
                    .inputHash(prev.getInputHash()).status(IdempotencyEntry.Status.DONE).result(result)
                    .createdAt(prev.getCreatedAt()).lastTouchedAt(clock.instant()).build();
            final String json = codec.encode(done, doneTtl);
            commands.eval(MARK_DONE_SCRIPT, io.lettuce.core.ScriptOutputType.INTEGER, new String[]{fullKey}, json,
                    String.valueOf(doneTtl.toMillis()));
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during markDone for key " + key, e);
        }
    }

    @Override
    public Optional<IdempotencyEntry> find(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            final String json = commands.get(entryKey(key));
            return json == null ? Optional.empty() : Optional.of(codec.decode(json));
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during find for key " + key, e);
        }
    }

    @Override
    public boolean touch(String key, String holderId) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        if (holderId.indexOf('"') >= 0 || holderId.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("holderId must not contain '\"' or '\\\\': " + holderId);
        }
        final String fullKey = entryKey(key);
        try {
            final String existing = commands.get(fullKey);
            if (existing == null) {
                return false;
            }
            final IdempotencyEntry prev = codec.decode(existing);
            if (prev.getStatus() != IdempotencyEntry.Status.IN_FLIGHT
                    || !prev.getHolderId().map(holderId::equals).orElse(false)) {
                return false;
            }
            final Duration originalTtl = Duration.ofMillis(codec.readTtlMillis(existing));
            final IdempotencyEntry refreshed = IdempotencyEntry.builder().key(prev.getKey())
                    .sessionId(prev.getSessionId()).inputHash(prev.getInputHash()).status(prev.getStatus())
                    .holderId(holderId).createdAt(prev.getCreatedAt()).lastTouchedAt(clock.instant()).build();
            final String json = codec.encode(refreshed, originalTtl);
            final Long applied = commands.eval(REPLACE_IF_HELD_SCRIPT, io.lettuce.core.ScriptOutputType.INTEGER,
                    new String[]{fullKey}, holderId, json, String.valueOf(originalTtl.toMillis()));
            return applied != null && applied == 1L;
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during touch for key " + key, e);
        }
    }

    @Override
    public boolean releaseHolder(String key, String expectedHolderId, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(expectedHolderId, "expectedHolderId must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (expectedHolderId.indexOf('"') >= 0 || expectedHolderId.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("holderId must not contain '\"' or '\\\\': " + expectedHolderId);
        }
        final String fullKey = entryKey(key);
        try {
            final String existing = commands.get(fullKey);
            if (existing == null) {
                return false;
            }
            final IdempotencyEntry prev = codec.decode(existing);
            if (prev.getStatus() != IdempotencyEntry.Status.IN_FLIGHT
                    || !prev.getHolderId().map(expectedHolderId::equals).orElse(false)) {
                return false;
            }
            // Same entry, no holder: the key stays reserved but stops looking like a live turn to the sweeper.
            final IdempotencyEntry reserved = IdempotencyEntry.builder().key(prev.getKey())
                    .sessionId(prev.getSessionId()).inputHash(prev.getInputHash())
                    .status(IdempotencyEntry.Status.IN_FLIGHT).createdAt(prev.getCreatedAt())
                    .lastTouchedAt(clock.instant()).build();
            final String json = codec.encode(reserved, ttl);
            final Long applied = commands.eval(REPLACE_IF_HELD_SCRIPT, io.lettuce.core.ScriptOutputType.INTEGER,
                    new String[]{fullKey}, expectedHolderId, json, String.valueOf(ttl.toMillis()));
            return applied != null && applied == 1L;
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during releaseHolder for key " + key, e);
        }
    }

    @Override
    public boolean discardReservation(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            final Long applied = commands.eval(DISCARD_RESERVATION_SCRIPT, io.lettuce.core.ScriptOutputType.INTEGER,
                    new String[]{entryKey(key)});
            return applied != null && applied == 1L;
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during discardReservation for key " + key, e);
        }
    }

    @Override
    public boolean compareAndReset(String key, String expectedHolderId) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(expectedHolderId, "expectedHolderId must not be null");
        if (expectedHolderId.indexOf('"') >= 0 || expectedHolderId.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("holderId must not contain '\"' or '\\\\': " + expectedHolderId);
        }
        try {
            final Long applied = commands.eval(COMPARE_AND_RESET_SCRIPT, io.lettuce.core.ScriptOutputType.INTEGER,
                    new String[]{entryKey(key)}, expectedHolderId);
            return applied != null && applied == 1L;
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during compareAndReset for key " + key, e);
        }
    }

    @Override
    public List<IdempotencyEntry> findStaleInFlight(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        final List<IdempotencyEntry> out = new ArrayList<>();
        try {
            ScanCursor cursor = ScanCursor.INITIAL;
            final ScanArgs args = ScanArgs.Builder.matches(keyPrefix + ":*").limit(256);
            do {
                final var scan = commands.scan(cursor, args);
                for (String key : scan.getKeys()) {
                    final String json = commands.get(key);
                    if (json == null) {
                        continue;
                    }
                    final IdempotencyEntry entry = codec.decode(json);
                    // A holderless IN_FLIGHT entry is a reservation waiting in an inbox, not a live turn: nobody
                    // touches it, so it always looks stale, and reporting it would have the sweeper declare a
                    // healthy session lost. See IdempotencyStore#findStaleInFlight.
                    if (entry.getStatus() == IdempotencyEntry.Status.IN_FLIGHT && entry.getHolderId().isPresent()
                            && entry.getLastTouchedAt().isBefore(cutoff)) {
                        out.add(entry);
                    }
                }
                cursor = scan;
            } while (!cursor.isFinished());
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during findStaleInFlight", e);
        }
        return out;
    }

    private String entryKey(String key) {
        return keyPrefix + ":" + key;
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
