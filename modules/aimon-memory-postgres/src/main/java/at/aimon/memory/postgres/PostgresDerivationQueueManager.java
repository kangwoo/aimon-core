package at.aimon.memory.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.base.exception.AimonException;
import at.aimon.core.memory.deriver.DerivationContext;
import at.aimon.core.memory.deriver.DerivationQueueManager;
import at.aimon.core.memory.deriver.DerivationResult;
import at.aimon.core.memory.deriver.DerivationTask;
import at.aimon.core.memory.deriver.DerivationWorkUnit;
import at.aimon.core.memory.deriver.Deriver;
import at.aimon.core.memory.deriver.DeriverProperties;
import at.aimon.core.memory.deriver.QueueStats;
import at.aimon.core.memory.redaction.MessageRedactor;
import at.aimon.core.memory.redaction.RedactionPolicy;

/**
 * Postgres-coordinated {@link DerivationQueueManager}.
 *
 * <p>
 * Enforces the same two invariants as
 * {@link at.aimon.core.memory.deriver.InMemoryDerivationQueueManager}:
 * redaction at enqueue, per-work-unit serialization. The difference is the
 * serialization gate: instead of an in-process monitor the gate is the
 * {@code mem_active_work_unit} table from {@code V1__init.sql} (design §6.1.1).
 * The {@code (workspace_id, session_id, observer_principal_type,
 * observer_principal_id)} primary key plus an {@code INSERT ... ON CONFLICT DO
 * UPDATE WHERE expires_at &lt; ...} probe gives us "first writer wins" across
 * every queue manager instance pointed at the same database.
 *
 * <p>
 * Tasks themselves live in an in-process {@link LinkedBlockingQueue}. JVM crash
 * loses pending tasks for now — full task-row persistence is deferred to a
 * later stage. Each instance generates a UUID {@code holderId} at construction
 * so a stale claim left behind by a dead instance can be stolen once
 * {@code expires_at} passes (lease length: {@link #DEFAULT_CLAIM_LEASE}).
 *
 * <p>
 * Threading: workers are daemon threads named {@code postgres-derivation-worker-N}.
 * If a worker dequeues a task whose work unit is currently claimed by another
 * worker, it puts the task back on the tail of the ready queue and backs off so it
 * does not spin — a short backoff when the holder is another worker in this JVM
 * (tracked by an in-process busy set, so no DB probe is wasted) and a full
 * {@code pollInterval} when the holder is another instance. While a worker holds a
 * claim, a single daemon heartbeat thread periodically renews {@code expires_at} so
 * a derivation that runs longer than the lease cannot have its unit stolen.
 */
public final class PostgresDerivationQueueManager implements DerivationQueueManager {

    static final Duration DEFAULT_CLAIM_LEASE = Duration.ofMinutes(5);
    private static final long DEFAULT_DRAIN_TIMEOUT_SECONDS = 30L;

    /** Backoff when a unit is already claimed by another worker in THIS JVM (no DB probe was needed). */
    private static final long LOCAL_DEFER_BACKOFF_MS = 25L;
    /** Floor on the claim-renewal interval so a tiny test lease can't spin the heartbeat scheduler. */
    private static final long MIN_RENEW_INTERVAL_MS = 1000L;

    private static final Logger log = LoggerFactory.getLogger(PostgresDerivationQueueManager.class);

    private static final String SQL_TRY_ACQUIRE_CLAIM = "INSERT INTO mem_active_work_unit ("
            + "workspace_id, session_id, observer_principal_type, observer_principal_id, "
            + "holder_id, claimed_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (workspace_id, session_id, observer_principal_type, observer_principal_id) DO UPDATE SET "
            + "holder_id = EXCLUDED.holder_id, claimed_at = EXCLUDED.claimed_at, expires_at = EXCLUDED.expires_at "
            + "WHERE mem_active_work_unit.expires_at < EXCLUDED.claimed_at " + "RETURNING holder_id";

    private static final String SQL_RELEASE_CLAIM = "DELETE FROM mem_active_work_unit "
            + "WHERE workspace_id = ? AND session_id = ? AND observer_principal_type = ? "
            + "AND observer_principal_id = ? AND holder_id = ?";

    private static final String SQL_RENEW_CLAIM = "UPDATE mem_active_work_unit SET expires_at = ? "
            + "WHERE workspace_id = ? AND session_id = ? AND observer_principal_type = ? "
            + "AND observer_principal_id = ? AND holder_id = ?";

    private final DataSource dataSource;
    private final Deriver deriver;
    private final MessageRedactor messageRedactor;
    private final DeriverProperties properties;
    private final Duration claimLease;
    private final String holderId;
    private final int tokenBudget;

    private final LinkedBlockingQueue<DerivationTask> readyQueue = new LinkedBlockingQueue<>();
    /** Work units currently being processed by a worker in THIS JVM — lets us skip a DB probe on contention. */
    private final Set<DerivationWorkUnit> locallyClaimed = ConcurrentHashMap.newKeySet();

    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicLong completedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private ExecutorService executor;
    private ScheduledExecutorService heartbeatScheduler;

    /**
     * Creates a new Postgres-backed queue manager. The default claim lease
     * ({@link #DEFAULT_CLAIM_LEASE}) is sufficient for typical deriver
     * invocations; tests use the explicit-lease constructor.
     */
    public PostgresDerivationQueueManager(DataSource dataSource, Deriver deriver, RedactionPolicy redactionPolicy,
            DeriverProperties properties) {
        this(dataSource, deriver, redactionPolicy, properties, DEFAULT_CLAIM_LEASE);
    }

    /**
     * Test-friendly constructor that lets callers shorten the claim lease so
     * concurrent-claim tests don't have to wait minutes for an expired lease
     * to be stealable.
     */
    public PostgresDerivationQueueManager(DataSource dataSource, Deriver deriver, RedactionPolicy redactionPolicy,
            DeriverProperties properties, Duration claimLease) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.deriver = Objects.requireNonNull(deriver, "deriver must not be null");
        this.messageRedactor = new MessageRedactor(
                Objects.requireNonNull(redactionPolicy, "redactionPolicy must not be null"));
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease must not be null");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive, got " + claimLease);
        }
        this.holderId = UUID.randomUUID().toString();
        this.tokenBudget = properties.getBatchMaxTokens();
    }

    /** Returns the per-instance holder id used for claim ownership in {@code mem_active_work_unit}. */
    public String getHolderId() {
        return holderId;
    }

    @Override
    public void enqueue(DerivationTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (stopping.get()) {
            throw new IllegalStateException("queue manager is stopped");
        }
        DerivationTask redacted = task.withMessages(messageRedactor.redactAll(task.getMessages()));
        readyQueue.offer(redacted);
        log.debug("Enqueued task: {}", redacted);
    }

    @Override
    public synchronized void start() {
        if (started.get()) {
            return;
        }
        if (stopping.get()) {
            throw new IllegalStateException("cannot start a stopped manager");
        }
        int workerCount = properties.getWorkerCount();
        // Heartbeat scheduler is created before the workers so a worker that claims a unit can always
        // register a lease-renewal task.
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "postgres-derivation-heartbeat");
            t.setDaemon(true);
            return t;
        });
        executor = Executors.newFixedThreadPool(workerCount, new WorkerThreadFactory());
        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::workerLoop);
        }
        started.set(true);
        log.info("Started Postgres derivation queue with {} workers (holderId={})", workerCount, holderId);
    }

    @Override
    public synchronized void stop() {
        if (!started.get() || stopping.get()) {
            return;
        }
        stopping.set(true);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(DEFAULT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Drain timeout exceeded; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        log.info("Stopped Postgres derivation queue. completed={}, failed={}", completedTasks.get(), failedTasks.get());
    }

    @Override
    public QueueStats stats() {
        return QueueStats.of(readyQueue.size(), activeWorkers.get(), completedTasks.get(), failedTasks.get());
    }

    /**
     * Attempts to acquire the cross-instance claim for {@code unit}. Visible for
     * direct testing of the SQL primitive — the worker loop also calls this.
     *
     * @return {@code true} if this instance now owns the claim, {@code false} otherwise
     */
    boolean tryAcquireClaim(DerivationWorkUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        final Instant now = Instant.now();
        final Instant expires = now.plus(claimLease);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_TRY_ACQUIRE_CLAIM)) {
            ps.setString(1, unit.getWorkspaceId());
            ps.setString(2, unit.getSessionId());
            ps.setString(3, unit.getObserverType().name());
            ps.setString(4, unit.getObserverId());
            ps.setString(5, holderId);
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(expires));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // CONFLICT path with WHERE = false → no row returned.
                    return false;
                }
                return holderId.equals(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error while acquiring claim for " + unit, e);
        }
    }

    /** Releases the claim if (and only if) it is still owned by this instance. */
    void releaseClaim(DerivationWorkUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_RELEASE_CLAIM)) {
            ps.setString(1, unit.getWorkspaceId());
            ps.setString(2, unit.getSessionId());
            ps.setString(3, unit.getObserverType().name());
            ps.setString(4, unit.getObserverId());
            ps.setString(5, holderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // A failed release leaks until expires_at; log loudly but don't surface to the worker.
            log.warn("Postgres error while releasing claim for {}: {}", unit, e.getMessage(), e);
        }
    }

    /**
     * Renews this instance's claim on {@code unit} by pushing {@code expires_at} forward, but only
     * while we still own it ({@code holder_id = ?}). Runs on the heartbeat scheduler so a derivation
     * that outlives the lease cannot have its work unit stolen by another worker (which would break
     * per-unit serialization and duplicate observations / LLM cost).
     */
    private void renewClaim(DerivationWorkUnit unit) {
        final Instant expires = Instant.now().plus(claimLease);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_RENEW_CLAIM)) {
            ps.setTimestamp(1, Timestamp.from(expires));
            ps.setString(2, unit.getWorkspaceId());
            ps.setString(3, unit.getSessionId());
            ps.setString(4, unit.getObserverType().name());
            ps.setString(5, unit.getObserverId());
            ps.setString(6, holderId);
            if (ps.executeUpdate() == 0) {
                log.warn("Claim renewal found no owned row for {} (holderId={}); claim may have been stolen", unit,
                        holderId);
            }
        } catch (SQLException e) {
            log.warn("Postgres error while renewing claim for {}: {}", unit, e.getMessage(), e);
        }
    }

    private void workerLoop() {
        long pollMs = properties.getPollInterval().toMillis();
        while (true) {
            try {
                DerivationTask task = readyQueue.poll(pollMs, TimeUnit.MILLISECONDS);
                if (task == null) {
                    if (stopping.get() && readyQueue.isEmpty()) {
                        return;
                    }
                    continue;
                }
                handleTask(task, pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Unexpected worker error: {}", e.getMessage(), e);
            }
        }
    }

    private void handleTask(DerivationTask task, long pollMs) throws InterruptedException {
        DerivationWorkUnit unit = task.workUnit();
        if (locallyClaimed.contains(unit)) {
            // Another worker in THIS JVM already owns the unit — skip the DB probe (the local set is
            // an optimization; the DB claim below is still the authoritative gate) and defer briefly.
            readyQueue.offer(task);
            Thread.sleep(Math.min(pollMs, LOCAL_DEFER_BACKOFF_MS));
            return;
        }
        if (!tryAcquireClaim(unit)) {
            // Another instance holds the claim. Defer and let a different ready task get a turn,
            // backing off a full poll interval before we probe the DB for this unit again.
            readyQueue.offer(task);
            Thread.sleep(pollMs);
            return;
        }
        locallyClaimed.add(unit);
        long renewMs = Math.max(MIN_RENEW_INTERVAL_MS, claimLease.toMillis() / 3);
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> renewClaim(unit), renewMs, renewMs,
                TimeUnit.MILLISECONDS);
        try {
            runDerive(task);
        } finally {
            heartbeat.cancel(false);
            locallyClaimed.remove(unit);
            releaseClaim(unit);
        }
    }

    private void runDerive(DerivationTask task) {
        activeWorkers.incrementAndGet();
        try {
            DerivationContext ctx = DerivationContext.builder().workspace(task.getWorkspace())
                    .sessionId(task.getSessionId()).observer(task.getObserver()).messages(task.getMessages())
                    .tokenBudget(tokenBudget).build();
            try {
                DerivationResult result = deriver.derive(ctx);
                completedTasks.incrementAndGet();
                log.debug("Derivation succeeded for {}: {}", task.workUnit(), result);
            } catch (RuntimeException e) {
                failedTasks.incrementAndGet();
                log.error("Derivation failed for {}: {}", task.workUnit(), e.getMessage(), e);
            }
        } finally {
            activeWorkers.decrementAndGet();
        }
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "postgres-derivation-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
