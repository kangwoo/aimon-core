package at.aimon.core.llm.exception;

import java.io.Serial;

/**
 * Thrown when an in-flight LLM call is aborted because its {@code LlmCancellation} token was cancelled.
 *
 * <p>
 * This is a <b>terminal</b> outcome, not a transient failure. It is a subtype of {@link LlmClientException} so it does
 * not break existing {@code catch (LlmClientException ...)} contracts, but the {@code LlmCallGateway} recognises it by
 * type and <b>never retries or falls back</b> on it — retrying a call the caller explicitly cancelled would defeat the
 * cancellation. The agent/subagent executors map this exception onto their cancellation-unwind path (typically
 * {@code CancelledExecutionException}) at the {@code llm -> agent} boundary.
 */
public class LlmCallCancelledException extends LlmClientException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given message.
     *
     * @param message
     *            a description of the cancelled call (must not be {@code null})
     */
    public LlmCallCancelledException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and the underlying cause raised while the aborted call unwound
     * (for example the provider SDK's stream-closed {@code IOException}).
     *
     * @param message
     *            a description of the cancelled call (must not be {@code null})
     * @param cause
     *            the underlying cause (may be {@code null})
     */
    public LlmCallCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
