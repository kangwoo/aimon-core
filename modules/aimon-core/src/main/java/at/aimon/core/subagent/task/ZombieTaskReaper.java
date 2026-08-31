package at.aimon.core.subagent.task;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reaps zombie background tasks whose owning node has stopped renewing their lease (design §4).
 *
 * <p>
 * On its own daemon thread, every {@link TaskLeaseConfig#getSweepInterval() sweep interval}, this reaper scans the
 * shared {@link BackgroundTaskStore} for non-terminal tasks whose {@link BackgroundTask#getLastHeartbeat() heartbeat}
 * has aged past the {@link TaskLeaseConfig#getLeaseTtl() lease TTL} and transitions each to {@code FAILED} — the owning
 * node is presumed lost ("owner node lost"). Without this, a node that crashes mid-run would leave its task stuck
 * {@code RUNNING} in a shared store forever.
 *
 * <p>
 * The transition goes through {@link BackgroundTaskStore#transition(String, BackgroundTaskState)}, whose terminal-guard
 * makes reaping idempotent and safe against a late completion: if the real owner finishes (or another reaper wins)
 * first
 * the task is already terminal and the reap is a no-op. A task with no recorded heartbeat is treated as heartbeated at
 * its {@link BackgroundTask#getStartTime() start time}, so a stalled task started before lease tracking was enabled
 * still ages normally rather than being reaped immediately or never.
 *
 * <p>
 * <b>Lease false-positive.</b> If a node is merely stalled (a stop-the-world pause longer than the TTL) rather than
 * dead, its live task can be reaped to {@code FAILED} and its eventual real result discarded by the terminal guard.
 * Choosing a {@link TaskLeaseConfig#getLeaseTtl() TTL} comfortably above the worst-case pause avoids this; the config
 * enforces {@code leaseTtl > heartbeatInterval} as a floor.
 *
 * <p>
 * In a node running several execution managers against one store, each may run its own reaper; the terminal-guarded
 * transition keeps concurrent reaps correct (at most one wins), only mildly redundant. This component is created only
 * when lease recovery is {@link TaskLeaseConfig opted in}; it is idempotent to {@link #start()} and {@link #close()}.
 */
public final class ZombieTaskReaper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ZombieTaskReaper.class);

    private final BackgroundTaskStore store;
    private final TaskLeaseConfig config;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private volatile boolean started;

    /**
     * Creates a reaper with the system UTC clock.
     *
     * @param store
     *            the shared task store to sweep (must not be null)
     * @param config
     *            the lease configuration (must not be null)
     */
    public ZombieTaskReaper(BackgroundTaskStore store, TaskLeaseConfig config) {
        this(store, config, Clock.systemUTC());
    }

    /**
     * Creates a reaper with an explicit clock (for testing).
     *
     * @param store
     *            the shared task store to sweep (must not be null)
     * @param config
     *            the lease configuration (must not be null)
     * @param clock
     *            the clock defining "now" for staleness (must not be null)
     */
    public ZombieTaskReaper(BackgroundTaskStore store, TaskLeaseConfig config, Clock clock) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            final Thread thread = new Thread(runnable, "zombie-task-reaper");
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Starts the periodic reap loop. Idempotent: a second call is ignored.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        final long periodMillis = config.getSweepInterval().toMillis();
        scheduler.scheduleAtFixedRate(this::sweepOnceQuietly, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        log.debug("Zombie task reaper started (sweepInterval={}, leaseTtl={})", config.getSweepInterval(),
                config.getLeaseTtl());
    }

    private void sweepOnceQuietly() {
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            // Never let an exception escape a scheduled task — that would silently cancel the whole loop.
            log.warn("Zombie sweep failed; will retry next tick: {}", e.getMessage());
        }
    }

    /**
     * Sweeps the store once, reaping every expired-lease zombie to {@code FAILED}. Exposed for deterministic testing;
     * the scheduled loop calls this on each tick.
     *
     * @return the ids of the tasks reaped by this sweep (never null; empty when nothing was stale)
     */
    public List<String> sweepOnce() {
        final Instant now = clock.instant();
        final List<String> reaped = new ArrayList<>();
        for (final BackgroundTask task : store.list(TaskQuery.all())) {
            if (task.getState().isTerminal()) {
                continue;
            }
            final Instant effectiveHeartbeat = task.getLastHeartbeat().orElse(task.getStartTime());
            final Duration age = Duration.between(effectiveHeartbeat, now);
            if (age.compareTo(config.getLeaseTtl()) <= 0) {
                continue;
            }
            if (store.transition(task.getTaskId(), BackgroundTaskState.FAILED).isPresent()) {
                reaped.add(task.getTaskId());
                log.warn("Reaped zombie task {} (subagent={}, state={}, heartbeat age={}): owner node lost",
                        task.getTaskId(), task.getSubagentName(), task.getState(), age);
            }
        }
        return reaped;
    }

    /**
     * Stops the reap loop and releases the daemon thread. Idempotent.
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
