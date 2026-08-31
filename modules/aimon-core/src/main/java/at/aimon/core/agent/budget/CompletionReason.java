package at.aimon.core.agent.budget;

/**
 * Terminal cause for an agent execution.
 *
 * <p>
 * Used by {@link at.aimon.core.agent.AgentExecutionResult#getCompletionReason()} to let callers distinguish a clean
 * success from various safety stops and errors. Only {@link #COMPLETED} indicates the agent finished its work on its
 * own terms.
 */
public enum CompletionReason {
    /** Agent produced a final answer normally. */
    COMPLETED,
    /** Execution halted because the iteration limit was reached. */
    MAX_ITERATIONS,
    /** Execution halted because the cumulative token budget was exhausted. */
    TOKEN_BUDGET_EXCEEDED,
    /** Execution halted because the cumulative monetary (cost) budget was exhausted. */
    COST_BUDGET_EXCEEDED,
    /** Execution halted because the wall-clock budget elapsed. */
    WALL_CLOCK_EXCEEDED,
    /**
     * Execution was aborted by a framework-initiated cancellation (parent agent shutdown, system shutdown, or other
     * non-user-initiated stop). User- or queue-initiated interrupts use {@link #INTERRUPTED} instead.
     */
    ABORTED,
    /**
     * Execution was interrupted cooperatively by a user action (e.g., Ctrl+C) or a higher-priority queued input that
     * preempted the current turn. Distinguishes intentional human-in-the-loop interruptions from the other budget- or
     * system-driven stops.
     */
    INTERRUPTED,
    /**
     * Execution was suspended pending out-of-band approval for one or more skill invocations (SK-11.4). The agent
     * loop performs an "atomic suspension": no assistant message or tool_result for the current LLM iteration is
     * committed to {@code TranscriptBuffer}. A subsequent resume re-issues the LLM call from the same memory state, and
     * the
     * pre-flight scan finds the now-cached approval decisions and lets the turn proceed. Distinct from
     * {@link #INTERRUPTED} which represents user-driven cancellation that does NOT expect a resume.
     */
    SUSPENDED,
    /**
     * The final assistant turn was cut off by the provider's max-output-token limit ({@link
     * at.aimon.core.llm.StopReason#MAX_TOKENS}). The partial text is surfaced to the caller with a truncation marker
     * appended, but the answer is incomplete — {@link #isSuccessful()} returns {@code false} so callers can
     * distinguish it from a clean {@link #COMPLETED} finish.
     */
    TRUNCATED,
    /** Execution ended with an unexpected error. */
    ERROR;

    /**
     * @return true if the agent finished its task successfully on its own terms
     */
    public boolean isSuccessful() {
        return this == COMPLETED;
    }
}
