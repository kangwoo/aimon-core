package at.aimon.core.llm.streaming;

/**
 * Callback invoked by {@link at.aimon.core.llm.invoke.LlmCallGateway} when a streaming attempt is discarded and a new
 * one will start.
 *
 * <p>
 * Callers (typically {@link at.aimon.core.agent.impl.orca.OrcaAgentExecutor}) use this to emit an
 * {@code AssistantTextStreamReset} event so the UI can clear any partial text already shown to the user.
 *
 * <p>
 * The listener is invoked <b>before</b> the next attempt begins — no chunks for the new attempt have been delivered
 * yet.
 */
@FunctionalInterface
public interface StreamingRetryListener {

    /**
     * Invoked exactly once per discarded attempt.
     *
     * @param previousAttempt
     *            the zero-based index of the attempt that was discarded
     * @param nextAttempt
     *            the zero-based index of the upcoming attempt
     * @param reason
     *            short human-readable reason (e.g., {@code "5xx_retry"}, {@code "fallback_model"},
     *            {@code "429_retry"})
     */
    void onRetry(int previousAttempt, int nextAttempt, String reason);

    /** No-op listener. */
    StreamingRetryListener NOOP = (prev, next, reason) -> {
    };
}
