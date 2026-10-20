package at.aimon.core.llms.openai.exception;

/**
 * Thrown by this module's OpenAI transport for an attempt that was refused, or cut, because the call it belongs to was
 * aborted by its cancellation token.
 *
 * <p>
 * The type is the whole no-retry guarantee. The SDK's {@code RetryingHttpClient} re-sends an attempt whose failure
 * is an {@code IOException}, an {@code OpenAIIoException} or an {@code OpenAIRetryableException}; an aborted attempt
 * must fail with none of those, or a cancelled request would be answered with a fresh one. This class therefore
 * extends the module's own {@link OpenAIException}, and it must never gain an {@code IOException} ancestor, nor be
 * wrapped in one on its way out of the transport: that invariant is the whole guarantee.
 *
 * <p>
 * It surfaces through {@code OpenAILlmClient}'s catch cascade, where the token is tripped by construction — only the
 * abort lever moves the call into the aborted state — and so maps to
 * {@link at.aimon.core.llm.exception.LlmCallCancelledException} with this exception as its cause.
 *
 * <p>
 * Thread-safe and immutable.
 */
public class OpenAICallAbortedException extends OpenAIException {

    /**
     * Creates a new exception with a message.
     *
     * @param message
     *            which attempt was refused or cut, and when
     */
    public OpenAICallAbortedException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a message and the transport failure the abort produced.
     *
     * @param message
     *            which attempt was cut, and when
     * @param cause
     *            the failure OkHttp raised for the cancelled call, kept for diagnosis; never rethrown as itself
     */
    public OpenAICallAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
