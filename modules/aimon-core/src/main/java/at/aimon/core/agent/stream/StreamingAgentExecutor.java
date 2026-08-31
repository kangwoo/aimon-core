package at.aimon.core.agent.stream;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRuntime;

/**
 * Streaming companion to {@link AgentExecutor} that exposes an agent execution as a sequence of
 * {@link AgentExecutionEvent} instances published via the standard {@link java.util.concurrent.Flow} API.
 *
 * <p>
 * Implementations produce a <b>cold</b> publisher: no work is started until a {@link Flow.Subscriber} subscribes.
 * Subscribers are supplied with events in <b>wall-clock emission order</b> — the same order the underlying executor
 * reached the corresponding progress points — and the stream is always terminated by a single terminal domain event
 * ({@link ExecutionCompleted} or {@link ExecutionError}) followed by {@link Flow.Subscriber#onComplete()}. Domain
 * errors MUST be reported through {@link ExecutionError} rather than {@link Flow.Subscriber#onError(Throwable)};
 * {@code onError} is reserved for failures of the subscription protocol itself (for example, a subscriber-supplied
 * {@link Flow.Subscriber#onSubscribe(Flow.Subscription)} that throws).
 *
 * <p>
 * <b>Backpressure:</b> this interface intentionally does not mandate a specific backpressure policy. Implementations
 * are free to buffer events in a bounded queue or to drop the oldest buffered event for slow consumers; what they MUST
 * guarantee is (1) strict emission ordering to each individual subscriber and (2) that a terminal event is always
 * delivered before {@code onComplete}. Implementations should document their chosen policy in their own Javadoc.
 *
 * <p>
 * <b>Threading:</b> the publisher may deliver signals on any thread; subscribers must therefore be thread-safe with
 * respect to themselves. Implementations MUST NOT call subscriber methods re-entrantly from within
 * {@link Flow.Subscription#request(long)}.
 *
 * <p>
 * Streaming events are <b>strictly informational</b>. They are not a replacement for
 * {@link at.aimon.core.agent.interceptor.AgentExecutionInterceptor}: consumers cannot mutate or short-circuit an
 * execution by observing them.
 *
 * @param <CTX>
 *            agent runtime type
 * @param <REQ>
 *            agent execution request type
 * @param <RES>
 *            agent execution result type
 * @see AgentExecutor
 * @see AgentExecutionEvent
 */
// @formatter:off
public interface StreamingAgentExecutor<
        CTX extends AgentRuntime,
        REQ extends AgentExecutionRequest,
        RES extends AgentExecutionResult> {
    // @formatter:on

    /**
     * Returns a cold {@link Flow.Publisher} that, upon the first subscription, triggers the agent execution for the
     * given context and request and emits one {@link AgentExecutionEvent} per reached progress point.
     *
     * <p>
     * Contract summary (per subscriber):
     *
     * <ul>
     * <li>Events are delivered in wall-clock emission order.
     * <li>Exactly one terminal domain event is delivered: {@link ExecutionCompleted} on clean finish (including clean
     * budget-driven stops) or {@link ExecutionError} on abnormal termination.
     * <li>The terminal domain event is followed by {@link Flow.Subscriber#onComplete()}; subscribers MUST NOT receive
     * any further events after {@code onComplete}.
     * <li>{@link Flow.Subscriber#onError(Throwable)} is reserved for subscription-protocol failures (for example, a
     * subscriber that throws from {@code onSubscribe}) and MUST NOT be used to signal domain errors.
     * </ul>
     *
     * <p>
     * Whether the returned publisher supports multiple subscriptions (multicast) or only the first subscription
     * (unicast) is implementation-defined. Callers that need deterministic semantics should assume unicast.
     *
     * @param context
     *            agent runtime (must not be null)
     * @param request
     *            execution request (must not be null)
     * @return a cold publisher of execution events (never null)
     * @throws NullPointerException
     *             if {@code context} or {@code request} is null
     */
    Flow.Publisher<AgentExecutionEvent> events(CTX context, REQ request);

    /**
     * Convenience API for callers that do not want to interact with the {@link Flow} protocol directly.
     *
     * <p>
     * Triggers the agent execution for the given context and request, invokes {@code listener} once per emitted
     * {@link AgentExecutionEvent} in wall-clock order (including the terminal {@link ExecutionCompleted} /
     * {@link ExecutionError} event), and returns a {@link CompletionStage} that resolves as follows:
     *
     * <ul>
     * <li><b>Normal completion:</b> the stage completes with the final {@link AgentExecutionResult} produced by the
     * underlying executor.
     * <li><b>Exceptional completion:</b> the stage completes exceptionally with the {@link Throwable} that caused the
     * execution to fail. Implementations SHOULD use the same cause they embedded in the corresponding
     * {@link ExecutionError} event (if any) so observers see a consistent picture.
     * </ul>
     *
     * <p>
     * <b>Listener isolation:</b> exceptions thrown from {@code listener} MUST be caught and logged by the
     * implementation; they MUST NOT propagate back to the executor, cancel the execution, or prevent subsequent events
     * (including the terminal one) from being delivered to the same listener.
     *
     * <p>
     * The listener may be invoked on any thread (including the caller's thread before this method returns, if the
     * implementation emits events synchronously); listeners MUST be thread-safe with respect to themselves.
     *
     * @param context
     *            agent runtime (must not be null)
     * @param request
     *            execution request (must not be null)
     * @param listener
     *            event listener invoked exactly once per emitted event (must not be null)
     * @return a stage that completes with the final result or exceptionally on failure (never null)
     * @throws NullPointerException
     *             if {@code context}, {@code request}, or {@code listener} is null
     */
    CompletionStage<RES> executeAsync(CTX context, REQ request, Consumer<AgentExecutionEvent> listener);
}
