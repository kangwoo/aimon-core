package at.aimon.core.agent.interrupt;

import java.io.Serial;
import java.util.Objects;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown by {@link CancellationSignal#checkpoint()} when the signal has been tripped.
 *
 * <p>
 * The executor treats this exception as a control-flow signal rather than an error: it terminates the current
 * execution with {@link at.aimon.core.agent.budget.CompletionReason#INTERRUPTED} and the carried
 * {@link InterruptReason} feeds the observability events. Tools must not catch and suppress this exception. A tool
 * that prefers a graceful return should check {@link CancellationSignal#isCancelled()} explicitly instead of calling
 * {@link CancellationSignal#checkpoint()}.
 */
public class CancelledExecutionException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final InterruptReason reason;

    /**
     * @param reason
     *            the reason recorded on the signal at trip time (never null)
     * @throws NullPointerException
     *             if {@code reason} is null
     */
    public CancelledExecutionException(InterruptReason reason) {
        super("Execution cancelled: " + Objects.requireNonNull(reason, "reason must not be null"));
        this.reason = reason;
    }

    /**
     * @return the reason the signal was tripped
     */
    public InterruptReason getReason() {
        return reason;
    }
}
