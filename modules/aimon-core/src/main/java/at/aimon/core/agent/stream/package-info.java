/**
 * Immutable streaming event model describing agent execution progress.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package defines a sealed hierarchy rooted at {@link at.aimon.core.agent.stream.AgentExecutionEvent} whose
 * concrete subtypes describe discrete progress points reached while an agent executes:
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.stream.IterationStarted} — a new ReAct iteration has begun
 * <li>{@link at.aimon.core.agent.stream.AssistantMessageReceived} — the LLM returned an assistant message
 * <li>{@link at.aimon.core.agent.stream.ToolUseStarted} — the executor is about to invoke a tool
 * <li>{@link at.aimon.core.agent.stream.ToolResultReady} — a tool invocation completed (success or error)
 * <li>{@link at.aimon.core.agent.stream.CompactBoundary} — a conversation compaction step was applied
 * <li>{@link at.aimon.core.agent.stream.IterationCompleted} — the current iteration finished
 * <li>{@link at.aimon.core.agent.stream.ExecutionCompleted} — the overall execution finished on its own terms
 * <li>{@link at.aimon.core.agent.stream.ExecutionError} — the overall execution ended with an error
 * </ul>
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
