package at.aimon.workflow.graaljs.exception;

import at.aimon.core.base.exception.AimonException;

/**
 * Signals that a GraalJS workflow script failed — a guest {@code throw}, a marshalling error, or a top-level
 * {@code PolyglotException} translated at the module boundary.
 *
 * <p>
 * Deliberately extends {@link AimonException} and <b>not</b> {@code WorkflowException}: the fan-out dispatcher
 * treats {@code WorkflowException} as a run-fatal control signal that aborts the whole batch. A script-level
 * error must stay an ordinary, isolable failure. It also keeps every {@code org.graalvm.*} type from crossing the
 * module boundary — only the translated message/cause escapes.
 */
public class JsScriptException extends AimonException {

    private static final long serialVersionUID = 1L;

    public JsScriptException(String message) {
        super(message);
    }

    public JsScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}
