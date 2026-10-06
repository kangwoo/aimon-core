package at.aimon.core.skill.hook.declarative;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The deadline of one blocking call made on the calling thread: when it passes while the call is still in flight, the
 * calling thread is interrupted.
 *
 * <p>
 * This is how the {@code http} and {@code mcp} action executors keep an action's {@code timeout} over the whole call,
 * with one mechanism for both. Neither transport has a bound of its own that covers it: {@code McpClient.callTool}
 * takes no timeout, and the JDK's {@code HttpRequest.timeout} stops counting when the response headers arrive, leaving
 * the body unbounded. Both calls answer an interrupt by giving the request up &mdash; the stdio MCP transport stops
 * polling for its response, and {@code HttpClient.send} cancels the exchange and closes its connection &mdash; so the
 * interrupt ends the request and not only the wait for it.
 *
 * <p>
 * No thread is created per call. The deadline rides on the JDK's shared delay scheduler
 * ({@link CompletableFuture#orTimeout}) and is cancelled by {@link #disarm()}. The call is not moved to another
 * thread to be bounded either: a hook already runs on a pool thread whose wait the hook executor's outer net bounds,
 * and that net is the fallback for a call that does not answer the interrupt.
 *
 * <p>
 * <b>Usage.</b> {@link #arm} immediately before the call, {@link #disarm()} in a {@code finally} around it &mdash; a
 * call that leaves through an {@link Error} must end its deadline too, or the timer fires later and interrupts a pool
 * thread that has moved on to other work. {@code disarm} reports whether the deadline had fired, which is how the
 * caller tells its own interrupt ({@code TIMEOUT}) from one that came from outside ({@code CANCELLED}).
 *
 * <p>
 * <b>The interrupt flag.</b> {@link #fire()} and {@link #disarm()} are mutually exclusive, so once {@code disarm} has
 * returned no interrupt from this deadline can arrive any more, and one that already did is taken back there: the flag
 * is cleared, and set again when the thread was already interrupted as the deadline was armed. Clearing can swallow an
 * outside interrupt that landed <em>during</em> the call in the same instant the deadline fired; the call is over
 * either way, and the caller that sent it (the hook executor's net) has already decided without this hook's result.
 *
 * <p>
 * One instance serves one call on one thread.
 */
final class CallDeadline {

    private final Thread caller = Thread.currentThread();
    private final boolean interruptedBefore = caller.isInterrupted();
    private final CompletableFuture<Void> timer = new CompletableFuture<>();
    private boolean armed = true;
    private boolean fired;

    private CallDeadline() {
    }

    /**
     * Starts a deadline for a call the current thread is about to make.
     *
     * @param timeout
     *            how long the call may take (must not be null)
     * @return the armed deadline (never null); the caller must {@link #disarm()} it
     */
    static CallDeadline arm(Duration timeout) {
        final CallDeadline deadline = new CallDeadline();
        deadline.timer.orTimeout(toNanosSaturating(timeout), TimeUnit.NANOSECONDS).whenComplete((ignored, thrown) -> {
            if (thrown instanceof TimeoutException) {
                deadline.fire();
            }
        });
        return deadline;
    }

    private synchronized void fire() {
        if (armed) {
            fired = true;
            caller.interrupt();
        }
    }

    /**
     * Ends the deadline and reports whether it had fired.
     *
     * @return true when the call ran out of time (the interrupt it caused has been taken back)
     */
    synchronized boolean disarm() {
        armed = false;
        // Cancels the scheduled timeout, so a call that ended leaves nothing behind.
        timer.complete(null);
        if (fired) {
            Thread.interrupted();
            if (interruptedBefore) {
                caller.interrupt();
            }
        }
        return fired;
    }

    private static long toNanosSaturating(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
