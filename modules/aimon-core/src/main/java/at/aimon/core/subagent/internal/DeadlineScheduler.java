package at.aimon.core.subagent.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an action once a delay has passed, unless the returned {@link Handle} is closed first — and never after
 * {@link Handle#close()} has returned.
 *
 * <p>
 * The second half is the point. A fork's deadline action trips the fork's cancellation, which fires its terminators,
 * and one of those may interrupt a thread. Once the fork has returned, that thread may be a pooled worker running
 * someone else's task, so the action must not be able to land late: {@code close()} and the action take the same lock,
 * and a closed handle's action does nothing.
 *
 * <p>
 * One daemon thread serves every timer of the JVM; actions are expected to be short (trip a signal, interrupt a
 * thread, record a state) and are isolated from one another, so an action that throws is logged and does not take the
 * timer thread down.
 */
public final class DeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeadlineScheduler.class);

    private static final ScheduledThreadPoolExecutor TIMER = newTimer();

    private DeadlineScheduler() {
    }

    private static ScheduledThreadPoolExecutor newTimer() {
        final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
            final Thread t = new Thread(r, "subagent-deadline");
            t.setDaemon(true);
            return t;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /**
     * Schedules {@code action} to run once after {@code delay}. A zero or negative delay runs it as soon as the timer
     * thread can.
     *
     * @param delay
     *            how long to wait (must not be null)
     * @param action
     *            what to run (must not be null)
     * @return the handle that cancels it
     */
    public static Handle schedule(Duration delay, Runnable action) {
        Objects.requireNonNull(delay, "delay cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
        final Handle handle = new Handle(action);
        final long nanos = delay.isNegative() ? 0 : saturatedNanos(delay);
        handle.future = TIMER.schedule(handle::fire, nanos, TimeUnit.NANOSECONDS);
        return handle;
    }

    private static long saturatedNanos(Duration delay) {
        try {
            return delay.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** One scheduled action. */
    public static final class Handle implements AutoCloseable {
        private final Object lock = new Object();
        private final Runnable action;
        private volatile ScheduledFuture<?> future;
        private boolean closed;
        private volatile boolean fired;

        private Handle(Runnable action) {
            this.action = action;
        }

        private void fire() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                fired = true;
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.warn("Deadline action failed: {}", e.toString());
                }
            }
        }

        /**
         * @return whether the action ran (or is running)
         */
        public boolean hasFired() {
            return fired;
        }

        /**
         * Cancels the action. When this returns the action has either already finished or will never run.
         */
        @Override
        public void close() {
            synchronized (lock) {
                closed = true;
            }
            final ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }
}
