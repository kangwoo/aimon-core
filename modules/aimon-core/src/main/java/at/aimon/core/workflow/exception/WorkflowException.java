package at.aimon.core.workflow.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Root exception for the workflow subsystem.
 *
 * <p>
 * An {@code WorkflowException} signals a framework-level control condition of an workflow <em>run</em> (as
 * opposed to a single subagent's execution failure, which is returned as an unsuccessful result, never thrown). It is
 * therefore treated as <b>run-fatal</b> by the fan-out engine: {@code BoundedFanoutDispatcher} does not isolate it into
 * an error result but re-throws it out of {@code dispatch} so the run aborts. Specific control conditions extend this
 * class (e.g. {@link WorkflowBudgetExceededException}).
 */
public class WorkflowException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message
     *            the detail message (must not be null)
     */
    public WorkflowException(String message) {
        super(message);
    }

    /**
     * @param message
     *            the detail message (must not be null)
     * @param cause
     *            the cause (may be null)
     */
    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
