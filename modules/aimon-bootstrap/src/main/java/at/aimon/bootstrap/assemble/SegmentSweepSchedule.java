package at.aimon.bootstrap.assemble;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.transcript.SessionLogSegmentSweeper;

/**
 * Runs a {@link SessionLogSegmentSweeper} on a daemon thread at a fixed delay (session-log §11).
 *
 * <p>
 * Application-scoped, like the stores the sweeper walks. Nothing runs until {@link #start()} — the stack calls it with
 * the other background sweepers, when it starts serving — and the first pass waits one interval, so a node that is
 * starting up does not also scan the whole segment store in its first seconds. A pass that fails is logged and the
 * next one runs on schedule; the sweeper already swallows storage failures, so only a bug reaches that log line.
 *
 * <p>
 * Each tick calls {@link SessionLogSegmentSweeper#sweepIfClaimed()}, so a sweeper built with coordination runs its pass
 * only on the node holding the sweep lease, and one without it runs every time.
 *
 * <p>
 * Thread-safe; {@link #start()} and {@link #close()} are idempotent.
 */
public final class SegmentSweepSchedule implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SegmentSweepSchedule.class);

    private final SessionLogSegmentSweeper sweeper;
    private final Duration interval;
    private ScheduledExecutorService executor;
    private boolean closed;

    /**
     * @param sweeper
     *            the sweeper (must not be null)
     * @param interval
     *            the delay between passes (must be positive)
     */
    public SegmentSweepSchedule(SessionLogSegmentSweeper sweeper, Duration interval) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper cannot be null");
        this.interval = Objects.requireNonNull(interval, "interval cannot be null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
    }

    /**
     * Starts the schedule. A no-op when already started or closed.
     */
    public synchronized void start() {
        if (executor != null || closed) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "aimon-segment-sweep");
            thread.setDaemon(true);
            return thread;
        });
        final long delayMs = Math.max(1L, interval.toMillis());
        executor.scheduleWithFixedDelay(this::sweepQuietly, delayMs, delayMs, TimeUnit.MILLISECONDS);
        log.info("Segment sweep started: interval={}, grace={}, coordinated={}", interval, sweeper.getGrace(),
                sweeper.isCoordinated());
    }

    /**
     * @return whether the schedule is running
     */
    public synchronized boolean isRunning() {
        return executor != null && !closed;
    }

    private void sweepQuietly() {
        try {
            sweeper.sweepIfClaimed();
        } catch (RuntimeException e) {
            log.error("Segment sweep pass failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Stops the schedule; a pass in progress is interrupted. Idempotent.
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
