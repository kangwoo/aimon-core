package at.aimon.bootstrap.exception;

import java.util.List;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown by {@code AimonStack#close()} when one or more teardown entries failed.
 *
 * <p>
 * Shutdown never stops at the first failure — every remaining entry still runs — so this exception is raised
 * only after the whole plan has been walked. Each individual failure is attached via
 * {@link Throwable#addSuppressed(Throwable)}, keeping the count and the causes recoverable by a caller that
 * wants to log them.
 *
 * <p>
 * Most callers should log this and continue: by the time it is thrown, everything that could be released has
 * been released.
 */
public class AimonTeardownException extends AimonException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> failedEntries;

    /**
     * Creates an exception describing the entries that failed.
     *
     * @param failedEntries
     *            labels of the teardown entries that threw, in execution order (must not be null)
     */
    public AimonTeardownException(List<String> failedEntries) {
        super(buildMessage(failedEntries));
        this.failedEntries = List.copyOf(failedEntries);
    }

    private static String buildMessage(List<String> failedEntries) {
        return "AIMON stack teardown completed with " + failedEntries.size() + " failure(s): "
                + String.join(", ", failedEntries)
                + " (each failure is attached as a suppressed exception; all remaining entries still ran)";
    }

    /**
     * Returns the labels of the teardown entries that failed, in execution order.
     *
     * @return an immutable list of entry labels
     */
    public List<String> getFailedEntries() {
        return failedEntries;
    }
}
