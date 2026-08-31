package at.aimon.core.subagent.task;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renews the lease heartbeat of every background task owned by this node (design §4).
 *
 * <p>
 * On its own daemon thread, every {@link TaskLeaseConfig#getHeartbeatInterval() heartbeat interval}, this publisher
 * asks
 * a supplier for the ids of the tasks still running (or queued) on this node and calls
 * {@link BackgroundTaskStore#heartbeat(String, java.time.Instant)} for each, stamping the current instant. The store's
 * terminal-guard makes a heartbeat for a task that just completed a harmless no-op. Because the id supplier is the
 * node's {@link RunningTaskRegistry#taskIds() live handle set}, a healthy node keeps its own tasks perpetually fresh,
 * so
 * the {@link ZombieTaskReaper} only ever reaps tasks whose owner has actually stopped heartbeating.
 *
 * <p>
 * A failed heartbeat (e.g. a transient store error) is logged and swallowed per task, so one unreachable task never
 * stalls the others; the next tick retries. This component is created only when lease recovery is
 * {@link TaskLeaseConfig opted in}; it is idempotent to {@link #start()} and {@link #close()}.
 *
 * <p>
 * <b>Bounded sweep latency.</b> A node may own many tasks, and each heartbeat is a round-trip to a shared store;
 * renewed
 * strictly one-by-one, a slow store could make a single sweep outlast the {@link TaskLeaseConfig#getLeaseTtl() lease
 * TTL} and let the {@link ZombieTaskReaper} reap a still-live task. To keep the sweep's wall-clock bounded, heartbeats
 * are dispatched concurrently across a small pool (all backends renew independent task keys thread-safely), and a sweep
 * that still runs past half the lease TTL logs a warning so the operator can raise {@code leaseTtl} or reduce per-node
 * task count before false reaps occur.
 */
public final class TaskHeartbeatPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TaskHeartbeatPublisher.class);

    /** Upper bound on concurrent heartbeat round-trips per sweep; heartbeats are I/O-bound, so a small fan-out. */
    private static final int MAX_RENEWAL_PARALLELISM = 8;

    private final BackgroundTaskStore store;
    private final Supplier<Set<String>> localTaskIds;
    private final TaskLeaseConfig config;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService renewalPool;
    private volatile boolean started;

    /**
     * Creates a publisher with the system UTC clock.
     *
     * @param store
     *            the shared task store whose heartbeats are renewed (must not be null)
     * @param localTaskIds
     *            supplies the ids of tasks owned by this node at each tick (must not be null)
     * @param config
     *            the lease configuration (must not be null)
     */
    public TaskHeartbeatPublisher(BackgroundTaskStore store, Supplier<Set<String>> localTaskIds,
            TaskLeaseConfig config) {
        this(store, localTaskIds, config, Clock.systemUTC());
    }

    /**
     * Creates a publisher with an explicit clock (for testing).
     *
     * @param store
     *            the shared task store whose heartbeats are renewed (must not be null)
     * @param localTaskIds
     *            supplies the ids of tasks owned by this node at each tick (must not be null)
     * @param config
     *            the lease configuration (must not be null)
     * @param clock
     *            the clock stamped into each heartbeat (must not be null)
     */
    public TaskHeartbeatPublisher(BackgroundTaskStore store, Supplier<Set<String>> localTaskIds, TaskLeaseConfig config,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.localTaskIds = Objects.requireNonNull(localTaskIds, "localTaskIds supplier cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("task-heartbeat-publisher"));
        this.renewalPool = Executors.newFixedThreadPool(MAX_RENEWAL_PARALLELISM,
                daemonThreadFactory("task-heartbeat-renewal"));
    }

    private static ThreadFactory daemonThreadFactory(String namePrefix) {
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(runnable, namePrefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Starts the periodic heartbeat loop. Idempotent: a second call is ignored.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        final long periodMillis = config.getHeartbeatInterval().toMillis();
        scheduler.scheduleAtFixedRate(this::publishOnceQuietly, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        log.debug("Task heartbeat publisher started (interval={})", config.getHeartbeatInterval());
    }

    private void publishOnceQuietly() {
        try {
            publishOnce();
        } catch (RuntimeException e) {
            // Never let an exception escape a scheduled task — that would silently cancel the whole loop.
            log.warn("Heartbeat sweep failed; will retry next tick: {}", e.getMessage());
        }
    }

    /**
     * Renews the heartbeat of every currently-owned task exactly once. Exposed for deterministic testing; the scheduled
     * loop calls this on each tick. Heartbeats are dispatched concurrently (bounded by
     * {@link #MAX_RENEWAL_PARALLELISM})
     * and the call blocks until every one completes, so the returned count is deterministic. A per-task failure is
     * logged and skipped; a sweep that outruns half the lease TTL logs a warning.
     *
     * @return the number of tasks whose heartbeat was successfully renewed
     */
    public int publishOnce() {
        final Set<String> taskIds = localTaskIds.get();
        if (taskIds.isEmpty()) {
            return 0;
        }
        final Instant at = clock.instant();
        final long startNanos = System.nanoTime();

        final List<Future<Boolean>> futures = new ArrayList<>(taskIds.size());
        for (final String taskId : taskIds) {
            futures.add(renewalPool.submit(() -> renewOne(taskId, at)));
        }

        int renewed = 0;
        for (final Future<Boolean> future : futures) {
            try {
                if (Boolean.TRUE.equals(future.get())) {
                    renewed++;
                }
            } catch (InterruptedException e) {
                // Shutdown or cancellation: stop waiting, preserve the interrupt for the caller (the scheduled loop).
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                // renewOne swallows its own failures, so this is defensive only.
                log.warn("Heartbeat renewal task failed unexpectedly: {}", e.getCause().getMessage());
            }
        }

        warnIfSweepTooSlow(startNanos, taskIds.size());
        return renewed;
    }

    /**
     * Renews one task's heartbeat, swallowing a transient store error so one unreachable task never stalls the sweep.
     *
     * @return {@code true} if the heartbeat was applied (task still live and non-terminal)
     */
    private boolean renewOne(String taskId, Instant at) {
        try {
            return store.heartbeat(taskId, at).isPresent();
        } catch (RuntimeException e) {
            log.warn("Failed to renew heartbeat for task {}: {}", taskId, e.getMessage());
            return false;
        }
    }

    private void warnIfSweepTooSlow(long startNanos, int taskCount) {
        final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        final long ttlMillis = config.getLeaseTtl().toMillis();
        // A sweep must finish well within the TTL; crossing half of it is the early warning that renewal can no longer
        // keep leases fresh and live tasks are at risk of being reaped as zombies.
        if (elapsedMillis * 2 >= ttlMillis) {
            log.warn(
                    "Heartbeat sweep of {} task(s) took {}ms — over half the lease TTL ({}ms); live tasks risk being "
                            + "reaped as zombies. Increase leaseTtl or reduce per-node task count.",
                    taskCount, elapsedMillis, ttlMillis);
        }
    }

    /**
     * Stops the heartbeat loop and releases the daemon threads. Idempotent.
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
        renewalPool.shutdownNow();
    }
}
