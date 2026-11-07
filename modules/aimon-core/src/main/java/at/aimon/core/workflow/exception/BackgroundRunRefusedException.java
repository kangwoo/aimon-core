package at.aimon.core.workflow.exception;

import java.io.Serial;

/**
 * Thrown by {@code WorkflowRunner#runInBackground(..., BackgroundSubmission)} when the submitter's ceiling and the
 * runner's base share no tool, so the run would have nothing to run under (register TC-25). Thrown before any run is
 * claimed in the run store, so nothing is left behind.
 *
 * <p>
 * Its own type so a tool reports it as what it is — a refusal of the caller's ceiling — and not as an invalid
 * parameter or an unexpected failure.
 */
public class BackgroundRunRefusedException extends WorkflowException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message
     *            the detail message, read by the model (must not be null)
     */
    public BackgroundRunRefusedException(String message) {
        super(message);
    }
}
