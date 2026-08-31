package at.aimon.core.llm.invoke;

/**
 * Outcome of a {@link PromptTooLongHandler} invocation, instructing the enclosing gateway whether to retry the failing
 * call or to propagate the original exception.
 *
 * <p>
 * The handler returns an {@link java.util.Optional} of {@code HandlerOutcome}:
 * <ul>
 * <li>{@link #RETRY} — the handler has taken a compensating action (for example, compacting the transcript buffer) so
 * the same logical call should be attempted again with the same model.
 * <li>{@link #ABORT} — the handler could not (or chose not to) act. The gateway must propagate the triggering
 * {@link at.aimon.core.llm.exception.LlmPromptTooLongException} to the caller.
 * <li>{@link java.util.Optional#empty()} returned from the handler is treated as {@link #ABORT} by the gateway.
 * </ul>
 *
 * <p>
 * Because repeated {@link #RETRY} outcomes without progress can loop indefinitely, the gateway enforces a bound on the
 * number of consecutive handler-driven retries. See {@link LlmCallGateway} for that cap.
 */
public enum HandlerOutcome {

    /**
     * Re-execute the failing call with the same model. The handler is expected to have shrunk or rewritten the
     * transcript buffer so the next attempt fits within the context window.
     */
    RETRY,

    /**
     * Propagate the triggering {@link at.aimon.core.llm.exception.LlmPromptTooLongException} without retrying.
     */
    ABORT
}
