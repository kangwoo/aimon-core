package at.aimon.core.workflow.exception;

import java.io.Serial;

/**
 * Thrown when an workflow run exceeds its agent-count backstop (the safety limit on how many
 * {@code agent()} calls a single run may make).
 *
 * <p>
 * This is a <b>run-fatal</b> control signal: it indicates a runaway script rather than a recoverable per-task failure,
 * so — unlike an ordinary execution failure — it is <em>not</em> isolated into a null/error result inside
 * {@code parallel}/{@code pipeline}. {@code BoundedFanoutDispatcher} re-throws any {@link WorkflowException}
 * (including this one) out of {@code dispatch}, aborting the run, so the backstop is not silently swallowed on the
 * fan-out path.
 */
public class WorkflowBudgetExceededException extends WorkflowException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message
     *            the detail message (must not be null)
     */
    public WorkflowBudgetExceededException(String message) {
        super(message);
    }
}
