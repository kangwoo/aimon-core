package at.aimon.workflow.graaljs.exception;

/**
 * Signals that a GraalJS workflow script was cancelled — a wall-clock watchdog {@code close(true)}, a
 * {@code CancellationSignal} trip, or a {@code ResourceLimits.statementLimit} overrun, all surfacing as a cancelled
 * {@code PolyglotException} translated at the module boundary.
 *
 * <p>
 * Extends {@link JsScriptException} (hence {@code AimonException}, not {@code WorkflowException}) so a single
 * {@code catch (JsScriptException)} in a consumer covers both the error and cancellation channels while the run-fatal
 * carve-out stays untouched.
 */
public class JsScriptCancelledException extends JsScriptException {

    private static final long serialVersionUID = 1L;

    public JsScriptCancelledException(String message) {
        super(message);
    }

    public JsScriptCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
