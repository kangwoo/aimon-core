/**
 * Invocation infrastructure that wraps {@link at.aimon.core.llm.LlmClient} with retry, fallback, and
 * prompt-too-long recovery semantics.
 *
 * <h2>Scope</h2>
 *
 * <p>
 * This package is <strong>consumer-side</strong>: it composes the exception taxonomy in
 * {@code at.aimon.core.llm.exception}
 * and the policy models in {@code at.aimon.core.llm.retry} into a runtime
 * {@link at.aimon.core.llm.invoke.LlmCallGateway}
 * that upper-layer agents can invoke via the same signature as {@link at.aimon.core.llm.LlmClient}.
 *
 * <h2>Core Types</h2>
 * <ul>
 * <li>{@link at.aimon.core.llm.invoke.LlmCallGateway} — the retry/fallback/prompt-too-long orchestrator.
 * <li>{@link at.aimon.core.llm.invoke.PromptTooLongHandler} — extension point invoked when the provider reports the
 * prompt exceeds the model's context window.
 * <li>{@link at.aimon.core.llm.invoke.PromptTooLongEvent} — immutable event carrying the triggering exception, the
 * current model, the attempt number, and an optional transcript-buffer snapshot.
 * <li>{@link at.aimon.core.llm.invoke.HandlerOutcome} — enum describing the handler's instruction back to the gateway.
 * <li>{@link at.aimon.core.llm.invoke.Sleeper} — small interface over {@link java.lang.Thread#sleep(long)} so tests can
 * observe backoff timing without blocking.
 * </ul>
 *
 * @since 0.0.36
 */
package at.aimon.core.llm.invoke;
