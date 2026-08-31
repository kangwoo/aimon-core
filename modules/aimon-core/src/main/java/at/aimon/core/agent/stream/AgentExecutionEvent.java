package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Base type of the streaming event hierarchy describing discrete agent execution progress points.
 *
 * <p>
 * This sealed class defines the three fields shared by every event:
 *
 * <ol>
 * <li>{@link #getTimestamp()} — wall-clock instant at which the event was emitted
 * <li>{@link #getAgentRuntimeId()} — identifier of the agent runtime the event belongs to
 * <li>{@link #getIteration()} — 1-based iteration number for iteration-phase events, or {@code 0} for events that do
 * not belong to a specific iteration (notably {@link ExecutionCompleted} and {@link ExecutionError})
 * </ol>
 *
 * <p>
 * Per project convention (see {@code CLAUDE.md} — "Prefer class over record") this hierarchy is modeled as a
 * {@code sealed abstract class} with {@code final} subtypes instead of a {@code sealed interface} with
 * {@code record} implementations. All subtypes are immutable value objects constructed via fluent builders.
 *
 * <p>
 * Subtypes (exhaustive, declared in the {@code permits} clause):
 *
 * <ul>
 * <li>{@link IterationStarted}
 * <li>{@link AssistantMessageReceived}
 * <li>{@link AssistantTextDelta}
 * <li>{@link AssistantTextStreamReset}
 * <li>{@link AssistantTextStreamCompleted}
 * <li>{@link ToolUseStarted}
 * <li>{@link ToolResultReady}
 * <li>{@link CompactBoundary}
 * <li>{@link IterationCompleted}
 * <li>{@link ExecutionCompleted}
 * <li>{@link ExecutionError}
 * <li>{@link SkillTurnSuspendedEvent}
 * <li>{@link InterruptedAt}
 * <li>{@link RejectedAt}
 * <li>{@link SubagentTaskCompleted}
 * </ul>
 *
 * <p>
 * Events are <b>strictly informational</b>. They are not a replacement for
 * {@link at.aimon.core.agent.interceptor.AgentExecutionInterceptor}: consumers cannot mutate or short-circuit an
 * execution by observing them.
 *
 * @see at.aimon.core.agent.stream
 */
// @formatter:off
public abstract sealed class AgentExecutionEvent
        permits IterationStarted,
                AssistantMessageReceived,
                AssistantTextDelta,
                AssistantTextStreamReset,
                AssistantTextStreamCompleted,
                ToolUseStarted,
                ToolResultReady,
                CompactBoundary,
                IterationCompleted,
                ExecutionCompleted,
                ExecutionError,
                SkillTurnSuspendedEvent,
                InterruptedAt,
                RejectedAt,
                SubagentTaskCompleted {
    // @formatter:on

    private final Instant timestamp;
    private final AgentRuntimeId agentRuntimeId;
    private final int iteration;

    /**
     * Protected constructor validating the three common fields.
     *
     * @param timestamp
     *            wall-clock instant at which the event was emitted (must not be null)
     * @param agentRuntimeId
     *            identifier of the agent runtime the event belongs to (must not be null)
     * @param iteration
     *            1-based iteration number for iteration-phase events; may be {@code 0} for events that are not bound
     *            to an iteration, such as {@link ExecutionCompleted} and {@link ExecutionError}. Must be {@code >= 0}.
     * @throws NullPointerException
     *             if {@code timestamp} or {@code agentRuntimeId} is null
     * @throws IllegalArgumentException
     *             if {@code iteration} is negative
     */
    protected AgentExecutionEvent(Instant timestamp, AgentRuntimeId agentRuntimeId, int iteration) {
        this.timestamp = Objects.requireNonNull(timestamp, "Timestamp cannot be null");
        this.agentRuntimeId = Objects.requireNonNull(agentRuntimeId, "AgentRuntimeId cannot be null");
        if (iteration < 0) {
            throw new IllegalArgumentException("Iteration cannot be negative: " + iteration);
        }
        this.iteration = iteration;
    }

    /**
     * Returns the wall-clock instant at which the event was emitted.
     *
     * @return the emission timestamp (never null)
     */
    public final Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the identifier of the agent runtime the event belongs to.
     *
     * @return the agent runtime identifier (never null)
     */
    public final AgentRuntimeId getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /**
     * Returns the iteration number associated with this event.
     *
     * <p>
     * For events that are raised from within the ReAct loop this is the 1-based iteration number. For terminal
     * execution-level events ({@link ExecutionCompleted}, {@link ExecutionError}) a value of {@code 0} is acceptable
     * and denotes "not associated with any specific iteration".
     *
     * @return the iteration number (always {@code >= 0})
     */
    public final int getIteration() {
        return iteration;
    }

    /**
     * Short event name used by {@link #toString()} (typically the simple class name).
     *
     * @return a non-null, non-empty event name
     */
    protected abstract String eventName();

    /**
     * Returns a subclass-specific detail fragment included in {@link #toString()}.
     *
     * <p>
     * Implementations should return an empty string when there are no extra fields to render and must never return
     * {@code null}.
     *
     * @return the detail fragment (never null; may be empty)
     */
    protected abstract String detailString();

    @Override
    public final String toString() {
        final String detail = detailString();
        final StringBuilder sb = new StringBuilder(eventName()).append('{').append("timestamp=").append(timestamp)
                .append(", agentRuntimeId=").append(agentRuntimeId).append(", iteration=").append(iteration);
        if (detail != null && !detail.isEmpty()) {
            sb.append(", ").append(detail);
        }
        sb.append('}');
        return sb.toString();
    }
}
