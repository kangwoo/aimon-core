package at.aimon.session.redis;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.BackgroundTaskStore;
import at.aimon.core.subagent.task.TaskQuery;
import at.aimon.session.redis.internal.BackgroundTaskCodec;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis-backed {@link BackgroundTaskStore}: the shared-backend metadata store that makes {@code Task.list} / status
 * queries observe background subagent tasks spawned on <em>any</em> instance (subagent design §4).
 *
 * <p>
 * <b>Layout.</b> Each task snapshot is one Redis STRING at {@code <keyPrefix>:<taskId>} holding the JSON produced by
 * {@link BackgroundTaskCodec}. A companion Redis SET at {@code <keyPrefix>:index} holds every live task id so
 * {@link #list(TaskQuery)} enumerates tasks without a full-keyspace {@code SCAN}. {@code put} writes the value and the
 * index entry <em>atomically</em> in a single Lua script, so the two can never diverge (a crashed node can no longer
 * leave a snapshot that is invisible to {@code list} / the reaper). {@code remove} deletes the value before dropping
 * the index entry, so the only crash residue is a stale index entry, which is self-healed on the next {@code list} (a
 * missing value drops the id from the index).
 *
 * <p>
 * <b>Compare-and-set writes.</b> {@link #transition(String, BackgroundTaskState)} and
 * {@link #heartbeat(String, java.time.Instant)} evolve exactly one field of the snapshot ({@code state}/{@code endTime}
 * for a transition, {@code lastHeartbeat} for a heartbeat), but Redis stores the snapshot as one opaque JSON string. To
 * keep those single-field writes atomic — matching the field-scoped updates of the in-memory
 * ({@code computeIfPresent}), Mongo ({@code $set}) and Postgres ({@code UPDATE SET column}) backends — the write is
 * guarded by an optimistic compare-and-set: a small Lua script re-checks terminality <em>and</em> that the stored value
 * is byte-identical to the snapshot this call read, then overwrites. If a concurrent writer changed the snapshot in
 * between (e.g. a heartbeat racing the owning node's PENDING→RUNNING transition), the CAS is refused and the operation
 * re-reads the fresh snapshot and re-applies its field, so no concurrent non-terminal update is ever clobbered. Once a
 * task is {@link BackgroundTaskState#isTerminal() terminal}, no further transition or heartbeat is applied, so a
 * completion and a concurrent stop can never both win. All backend errors surface as {@link IllegalStateException} —
 * the Redis SDK exception never crosses the module boundary.
 */
public final class RedisBackgroundTaskStore implements BackgroundTaskStore {

    /** Default key prefix for task snapshots and the task-id index set. */
    public static final String DEFAULT_KEY_PREFIX = "aimon:subagent:task";

    // Bounds the optimistic CAS retry loop. Contention on a single task key is limited to the owning node's few
    // lifecycle transitions plus its periodic heartbeat, so a handful of attempts is far more than enough; exhausting
    // them signals pathological contention and surfaces as an IllegalStateException rather than a silent lost update.
    private static final int MAX_CAS_ATTEMPTS = 5;

    // Sentinel returned by CAS_TRANSITION_SCRIPT when the stored snapshot changed since the caller's read, so the
    // caller must re-read and retry. A real encoded snapshot is always a JSON object starting with '{', so this
    // space-delimited token can never collide with a successful (new-value) response.
    private static final String CAS_RETRY_SIGNAL = "\0CAS_RETRY\0";

    // Atomic put: write the snapshot value and add the index entry in one script so a reader/reaper can never observe a
    // value without its index entry (or vice versa). KEYS[1]=value key, KEYS[2]=index key, ARGV[1]=json,
    // ARGV[2]=taskId.
    private static final String PUT_SCRIPT = "redis.call('SET', KEYS[1], ARGV[1]); "
            + "redis.call('SADD', KEYS[2], ARGV[2]); " + "return 'OK';";

    // Compare-and-set write: overwrite the snapshot only while it is still non-terminal AND byte-identical to the value
    // the caller read (ARGV[2]). Returns the new value on success, a Lua false (decoded by Lettuce as null) when the
    // key
    // is gone or already terminal, and ARGV[3] (the retry sentinel) when the stored value changed under the caller.
    private static final String CAS_TRANSITION_SCRIPT = "local v = redis.call('GET', KEYS[1]); "
            + "if not v then return false end; " + "if string.find(v, '\"state\":\"COMPLETED\"', 1, true) "
            + "   or string.find(v, '\"state\":\"FAILED\"', 1, true) "
            + "   or string.find(v, '\"state\":\"KILLED\"', 1, true) then " + "  return false; " + "end; "
            + "if v ~= ARGV[2] then return ARGV[3]; end; " + "redis.call('SET', KEYS[1], ARGV[1]); "
            + "return ARGV[1];";

    private final RedisCommands<String, String> commands;
    private final BackgroundTaskCodec codec;
    private final String keyPrefix;
    private final String indexKey;
    private final Clock clock;

    /**
     * Creates a store with the default key prefix, a JSR-310-aware mapper and the system UTC clock.
     *
     * @param connection
     *            the Lettuce connection (must not be null; owned by the caller)
     */
    public RedisBackgroundTaskStore(StatefulRedisConnection<String, String> connection) {
        this(connection, defaultMapper(), DEFAULT_KEY_PREFIX, Clock.systemUTC());
    }

    /**
     * Creates a store with explicit collaborators.
     *
     * @param connection
     *            the Lettuce connection (must not be null; owned by the caller)
     * @param mapper
     *            the Jackson mapper for snapshot codec (must not be null)
     * @param keyPrefix
     *            the key prefix for task snapshots and the index set (must not be null)
     * @param clock
     *            the clock used to stamp terminal transition end times (must not be null)
     */
    public RedisBackgroundTaskStore(StatefulRedisConnection<String, String> connection, ObjectMapper mapper,
            String keyPrefix, Clock clock) {
        Objects.requireNonNull(connection, "connection must not be null");
        this.commands = connection.sync();
        this.codec = new BackgroundTaskCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        this.indexKey = keyPrefix + ":index";
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void put(BackgroundTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        try {
            // Value and index entry in one atomic script: the two can never diverge, so a crash can no longer leave a
            // snapshot that is invisible to list()/the reaper.
            commands.eval(PUT_SCRIPT, ScriptOutputType.STATUS, new String[]{taskKey(task.getTaskId()), indexKey},
                    codec.encode(task), task.getTaskId());
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during put for task " + task.getTaskId(), e);
        }
    }

    @Override
    public Optional<BackgroundTask> find(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        try {
            final String json = commands.get(taskKey(taskId));
            return json == null ? Optional.empty() : Optional.of(codec.decode(json));
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during find for task " + taskId, e);
        }
    }

    @Override
    public List<BackgroundTask> list(TaskQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        try {
            final Set<String> ids = commands.smembers(indexKey);
            if (ids.isEmpty()) {
                return new ArrayList<>();
            }
            final List<String> idList = new ArrayList<>(ids);
            final String[] keys = idList.stream().map(this::taskKey).toArray(String[]::new);
            final List<KeyValue<String, String>> values = commands.mget(keys);
            final List<BackgroundTask> result = new ArrayList<>();
            final List<String> stale = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                final KeyValue<String, String> kv = values.get(i);
                if (!kv.hasValue()) {
                    // Index points at a value that no longer exists (crashed remove): drop it.
                    stale.add(idList.get(i));
                    continue;
                }
                final BackgroundTask task = codec.decode(kv.getValue());
                if (query.matches(task)) {
                    result.add(task);
                }
            }
            if (!stale.isEmpty()) {
                commands.srem(indexKey, stale.toArray(new String[0]));
            }
            return result;
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during list", e);
        }
    }

    @Override
    public Optional<BackgroundTask> transition(String taskId, BackgroundTaskState to) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(to, "target state cannot be null");
        // Evolve only state (and endTime for a terminal target); the CAS guard preserves any concurrent field change.
        return compareAndSetWrite(taskId, "transition", builder -> {
            builder.state(to);
            if (to.isTerminal()) {
                builder.endTime(clock.instant());
            }
            return builder;
        });
    }

    @Override
    public Optional<BackgroundTask> heartbeat(String taskId, java.time.Instant at) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(at, "heartbeat instant cannot be null");
        // Evolve only lastHeartbeat; the CAS guard makes sure a heartbeat can never revert a concurrent state change
        // (e.g. the owning node's PENDING→RUNNING transition) — the failure the whole-snapshot overwrite used to have.
        return compareAndSetWrite(taskId, "heartbeat", builder -> builder.lastHeartbeat(at));
    }

    /**
     * Optimistic compare-and-set write of a single-field mutation. Reads the current snapshot, applies {@code mutation}
     * to a builder, and asks {@link #CAS_TRANSITION_SCRIPT} to overwrite the value only if it is still non-terminal and
     * unchanged since the read. A concurrent non-terminal change refuses the CAS; the operation then re-reads the fresh
     * snapshot and re-applies its field, so it never clobbers another writer's update. Returns empty when the task is
     * unknown or already terminal (guard-and-return semantics), mirroring the in-memory reference store.
     */
    private Optional<BackgroundTask> compareAndSetWrite(String taskId, String opName,
            UnaryOperator<BackgroundTask.Builder> mutation) {
        final String key = taskKey(taskId);
        try {
            for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
                final String currentJson = commands.get(key);
                if (currentJson == null) {
                    return Optional.empty();
                }
                final BackgroundTask current = codec.decode(currentJson);
                if (current.getState().isTerminal()) {
                    // Idempotent: never evolve a terminal task (completion / stop already won).
                    return Optional.empty();
                }
                final BackgroundTask updated = mutation.apply(current.toBuilder()).build();
                final String applied = commands.<String>eval(CAS_TRANSITION_SCRIPT, ScriptOutputType.VALUE,
                        new String[]{key}, codec.encode(updated), currentJson, CAS_RETRY_SIGNAL);
                if (applied == null) {
                    // Key vanished, or the stored value went terminal between our read and the eval.
                    return Optional.empty();
                }
                if (!CAS_RETRY_SIGNAL.equals(applied)) {
                    return Optional.of(updated);
                }
                // A concurrent non-terminal writer changed the snapshot under us: re-read and re-apply.
            }
            throw new IllegalStateException("Redis " + opName + " for task " + taskId
                    + " lost the compare-and-set race after " + MAX_CAS_ATTEMPTS + " attempts");
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during " + opName + " for task " + taskId, e);
        }
    }

    @Override
    public void remove(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        try {
            // Value first, then index: mirrors put()'s ordering so a stale index entry is the only crash residue, and
            // it is self-healed by list().
            commands.del(taskKey(taskId));
            commands.srem(indexKey, taskId);
        } catch (RedisException e) {
            throw new IllegalStateException("Redis error during remove for task " + taskId, e);
        }
    }

    private String taskKey(String taskId) {
        return keyPrefix + ":" + taskId;
    }

    private static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
