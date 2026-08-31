package at.aimon.workflow.graaljs;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.CancellationSignal;

/**
 * Force-closes a per-run {@code Context} from a non-owner thread on cancellation or wall-clock expiry.
 *
 * <p>
 * Two triggers, both invoking the only sanctioned cross-thread operation {@code Context.close(true)} (cancel if
 * executing):
 * <ul>
 * <li>the run's {@link CancellationSignal} (host-provided; {@code onCancel}) — reaches compute-bound guest code;
 * <li>a wall-clock deadline via the shared scheduler — the mandatory backstop for non-yielding loops. The deadline
 * first runs the caller-supplied {@code deadlineAction} (typically a run-signal trip so in-flight leaves terminate
 * cooperatively and the core signal-polling join frees an owner parked in {@code ctx.parallel}), then closes.
 * </ul>
 * {@code Context.close(true)} blocks until every thread has left the context — an owner inside a host call keeps it
 * entered — so the close itself always runs on the {@code closer} executor: neither the shared scheduler thread nor
 * the thread that tripped the signal is ever parked on a slow close. {@code close(true)} unwinds only compute-bound
 * guest code at a Truffle safepoint. Both triggers and {@link #cancel()} are idempotent — closing an already-closed
 * {@code Context} is a no-op.
 */
final class CancellationWatchdog {

    private static final Logger log = LoggerFactory.getLogger(CancellationWatchdog.class);

    private final Context context;
    private final CancellationSignal.Registration registration;
    private final ScheduledFuture<?> deadline;

    private CancellationWatchdog(Context context, CancellationSignal.Registration registration,
            ScheduledFuture<?> deadline) {
        this.context = context;
        this.registration = registration;
        this.deadline = deadline;
    }

    /**
     * Arms a watchdog: registers on the signal and, if {@code wallClock} is positive, schedules a deadline that runs
     * {@code deadlineAction} and then force-closes the context.
     *
     * @param context
     *            the per-run context to close (must not be null)
     * @param signal
     *            the run's cancellation signal (must not be null)
     * @param wallClock
     *            the wall-clock deadline; non-positive/null disarms the deadline trigger
     * @param scheduler
     *            the shared timing thread; never blocked on a close (must not be null)
     * @param closer
     *            the executor that performs the potentially-blocking {@code close(true)} (must not be null)
     * @param deadlineAction
     *            invoked once on wall-clock expiry before the close, e.g. a run-signal trip (must not be null)
     */
    static CancellationWatchdog arm(Context context, CancellationSignal signal, Duration wallClock,
            ScheduledExecutorService scheduler, Executor closer, Runnable deadlineAction) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(signal, "signal cannot be null");
        Objects.requireNonNull(scheduler, "scheduler cannot be null");
        Objects.requireNonNull(closer, "closer cannot be null");
        Objects.requireNonNull(deadlineAction, "deadlineAction cannot be null");

        final CancellationSignal.Registration registration = signal
                .onCancel(() -> closer.execute(() -> closeQuietly(context, "signal")));

        ScheduledFuture<?> deadline = null;
        if (wallClock != null && !wallClock.isZero() && !wallClock.isNegative()) {
            deadline = scheduler.schedule(() -> {
                runQuietly(deadlineAction);
                closer.execute(() -> closeQuietly(context, "wall-clock"));
            }, wallClock.toMillis(), TimeUnit.MILLISECONDS);
        }
        return new CancellationWatchdog(context, registration, deadline);
    }

    /** Disarms both triggers. Called from the owner-thread {@code finally} once the run has settled. */
    void cancel() {
        registration.remove();
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    private static void runQuietly(Runnable deadlineAction) {
        try {
            deadlineAction.run();
        } catch (RuntimeException e) {
            log.warn("Watchdog deadline action failed: {}", e.getMessage(), e);
        }
    }

    private static void closeQuietly(Context context, String cause) {
        try {
            log.debug("Watchdog closing GraalJS context ({} trigger)", cause);
            context.close(true);
        } catch (RuntimeException e) {
            log.debug("Watchdog context close ({}) ignored: {}", cause, e.getMessage());
        }
    }
}
