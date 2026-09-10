/**
 * Immutable streaming event model describing agent execution progress.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package defines a sealed hierarchy rooted at {@link at.aimon.core.agent.stream.AgentExecutionEvent} whose
 * concrete subtypes describe discrete progress points reached while an agent executes — a new iteration, an
 * assistant message, a tool invocation and its result, a compaction boundary, a terminal outcome:
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.stream.IterationStarted} — a new ReAct iteration has begun
 * <li>{@link at.aimon.core.agent.stream.AssistantTextDelta} — one fragment of the assistant's answer, streaming
 * <li>{@link at.aimon.core.agent.stream.AssistantReasoningDelta} — one fragment of the model's <em>deliberation</em>,
 * which is never the answer and is never accumulated with it
 * <li>{@link at.aimon.core.agent.stream.ToolUseStarted} — the executor is about to invoke a tool
 * <li>{@link at.aimon.core.agent.stream.ExecutionCompleted} — the overall execution finished on its own terms
 * </ul>
 *
 * <p>
 * <b>That list is illustrative, not exhaustive, and deliberately so.</b> The exhaustive one is the {@code permits}
 * clause of {@link at.aimon.core.agent.stream.AgentExecutionEvent} and the {@code
 *
<ul>
 * } in its own javadoc, which
 * sits next to the clause it mirrors; a second full enumeration here would be a second thing to keep in step, and it
 * had already fallen eight subtypes behind before anyone noticed.
 *
 * <p>
 * Consumers (REPL, web UI, recorders, test assertions) are expected to subscribe to a
 * {@link java.util.concurrent.Flow.Publisher} of these events. Producing that publisher and emitting events from
 * {@code OrcaAgentExecutor} is delivered by separate tasks (see STREAM-02 / STREAM-03 in the adoption plan); this
 * package only provides the value-object model.
 *
 * <h2>Non-goals</h2>
 *
 * <p>
 * This model is <b>not</b> a replacement for
 * {@link at.aimon.core.agent.interceptor.AgentExecutionInterceptor}. Interceptors can short-circuit or mutate an
 * execution request/response; streaming events are strictly informational, observational, and one-way. See STREAM-05 in
 * the pipeline adoption plan for the intended split of responsibilities.
 *
 * <h2>Design notes</h2>
 *
 * <p>
 * Per project convention ({@code CLAUDE.md} — "Prefer class over record"), {@link
 * at.aimon.core.agent.stream.AgentExecutionEvent} is modeled as a {@code sealed abstract class} with {@code final}
 * subclasses instead of a {@code sealed interface} with {@code record} implementations. All subclasses are immutable
 * value objects built via fluent builders, perform defensive copies for collection inputs, and expose {@link
 * java.util.Optional} for nullable fields.
 */
package at.aimon.core.agent.stream;
