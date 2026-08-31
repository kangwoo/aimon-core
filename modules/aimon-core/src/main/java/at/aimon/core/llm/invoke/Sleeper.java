package at.aimon.core.llm.invoke;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import at.aimon.core.llm.LlmCancellation;

/**
 * Abstraction over {@link Thread#sleep(long)} so tests can verify backoff timing without actually blocking.
 *
 * <p>
 * The default implementation {@link #threadSleep()} delegates to {@link Thread#sleep(long)}. Production code should use
 * it. Tests inject a recording implementation that captures the requested {@link Duration} values and returns
 * immediately.
 */
public interface Sleeper {

    /**
     * Sleeps for the given duration.
     *
     * @param duration
     *            the duration to sleep (must not be {@code null}; zero or negative durations are treated as no-op)
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     */
    void sleep(Duration duration) throws InterruptedException;

    /**
     * Sleeps for up to {@code duration}, waking early if {@code cancellation} trips. This makes a retry-backoff sleep
     * responsive to cancellation: a trip arriving <em>during</em> the sleep (e.g. a {@code TaskStop} while the worker
     * waits out a long {@code Retry-After} hint) wakes the sleep promptly instead of being observed only after the full
     * delay elapses. The caller re-checks its own cancellation state after this returns and short-circuits accordingly
     * — this method reports nothing about <em>why</em> it returned.
     *
     * <p>
     * The default implementation ignores {@code cancellation} and delegates to {@link #sleep(Duration)}, so recording
     * test sleepers observe the full requested duration unchanged. {@link #threadSleep()} overrides it to actually wake
     * early.
     *
     * @param duration
     *            the maximum duration to sleep (must not be {@code null}; zero or negative durations are treated as
     *            no-op)
     * @param cancellation
     *            the cancellation token that may wake the sleep early (must not be {@code null}; the inert
     *            {@link LlmCancellation#none()} token simply sleeps the full duration)
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     */
    default void sleep(Duration duration, LlmCancellation cancellation) throws InterruptedException {
        sleep(duration);
    }

    /**
     * Returns a {@link Sleeper} backed by {@link Thread#sleep(long)} whose cancellation-aware overload wakes early on a
     * trip via a {@link CountDownLatch} tripped from {@link LlmCancellation#onCancel(Runnable)} (no thread interruption
     * — consistent with the provider abort model).
     *
     * @return a real-clock sleeper (never {@code null})
     */
    static Sleeper threadSleep() {
        return new Sleeper() {
            @Override
            public void sleep(Duration duration) throws InterruptedException {
                if (duration == null || duration.isZero() || duration.isNegative()) {
                    return;
                }
                Thread.sleep(duration.toMillis());
            }

            @Override
            public void sleep(Duration duration, LlmCancellation cancellation) throws InterruptedException {
                if (duration == null || duration.isZero() || duration.isNegative()) {
                    return;
                }
                if (cancellation == null || !cancellation.isSupported()) {
                    sleep(duration);
                    return;
                }
                if (cancellation.isCancelled()) {
                    // Already cancelled — don't burn the backoff; the caller re-checks and short-circuits.
                    return;
                }
                final CountDownLatch wake = new CountDownLatch(1);
                // If the token trips between the isCancelled() check above and this registration, onCancel fires
                // synchronously (already-cancelled contract), so the latch is already down and await returns at once.
                cancellation.onCancel(wake::countDown);
                wake.await(duration.toMillis(), TimeUnit.MILLISECONDS);
            }
        };
    }
}
