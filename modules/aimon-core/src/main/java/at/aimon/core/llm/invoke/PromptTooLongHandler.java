package at.aimon.core.llm.invoke;

import java.util.Optional;

import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;

/**
 * Extension point invoked by {@link LlmCallGateway} when a provider call fails with an
 * {@link LlmPromptTooLongException}.
 *
 * <h2>Contract</h2>
 *
 * <p>
 * The gateway hands the handler a {@link PromptTooLongEvent} and respects the returned {@link HandlerOutcome}:
 * <ul>
 * <li>{@link HandlerOutcome#RETRY} — the handler has performed a compensating side-effect (for example, compacting or
 * summarising the transcript buffer referenced by {@link PromptTooLongEvent#getMemorySnapshot()}) and the gateway
 * should re-issue the same call with the same model. The gateway does <strong>not</strong> increment the attempt
 * counter across handler-driven retries: the attempt counter reflects <em>provider-side</em> retries only.
 * <li>{@link HandlerOutcome#ABORT} — the handler could not (or chose not to) compensate. The gateway propagates the
 * original {@link LlmPromptTooLongException} unchanged.
 * <li>{@link Optional#empty()} returned from {@link #handle(PromptTooLongEvent)} is treated exactly like
 * {@link HandlerOutcome#ABORT}. This keeps no-op / delegating implementations concise.
 * </ul>
 *
 * <p>
 * Handlers may rethrow {@link LlmClientException} from {@link #handle(PromptTooLongEvent)} to fail the call
 * immediately with a different error (for example, when compaction itself fails and the caller should see a wrapped
 * error). Such exceptions propagate out of the gateway verbatim.
 *
 * <h2>Handler-driven retry bound</h2>
 *
 * <p>
 * The gateway enforces a cap on consecutive handler-driven retries to prevent infinite loops when a handler keeps
 * returning {@link HandlerOutcome#RETRY} without making progress. Handlers should be designed to converge after at
 * most a small, bounded number of invocations for a single logical call.
 *
 * <h2>Threading</h2>
 *
 * <p>
 * Implementations should be thread-safe when the same gateway is shared across threads. The handler is called
 * synchronously from the gateway thread, so it must not block indefinitely.
 *
 * <h2>Default implementation</h2>
 *
 * <p>
 * This interface intentionally has no default implementation in the LLM core package — wiring a concrete handler (for
 * example, a compaction-driven one) is the responsibility of RETRY-05 and later. When no handler is injected into a
 * gateway, the gateway treats {@link LlmPromptTooLongException} as unrecoverable and rethrows it immediately.
 *
 * @param <M>
 *            the transcript-buffer type the handler operates on
 */
@FunctionalInterface
public interface PromptTooLongHandler<M> {

    /**
     * Handles a {@link LlmPromptTooLongException} captured by the gateway.
     *
     * @param event
     *            the event describing the failing call (must not be {@code null})
     * @return an outcome instructing the gateway how to proceed, or {@link Optional#empty()} to abort
     * @throws LlmClientException
     *             if the handler wishes to fail the enclosing gateway call with a different error. The exception
     *             propagates out of the gateway verbatim.
     */
    Optional<HandlerOutcome> handle(PromptTooLongEvent<M> event) throws LlmClientException;
}
