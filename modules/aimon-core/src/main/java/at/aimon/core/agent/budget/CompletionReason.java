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
     * The final assistant response of a turn or of a subagent fork was cut off by the provider's max-output-token limit
     * ({@link at.aimon.core.llm.StopReason#MAX_TOKENS}). The partial text is surfaced to the caller with
     * {@link TruncatedResponses#TRUNCATION_MARKER} appended, but the answer is incomplete — {@link #isSuccessful()}
     * returns {@code false} so callers can distinguish it from a clean {@link #COMPLETED} finish.
     *
     * <p>
     * A response cut off inside its tool calls does <em>not</em> end the execution with this reason. Its calls are
     * refused rather than run ({@link TruncatedResponses#refusal(at.aimon.core.llm.ToolUse)}) and the loop continues.
     */
    TRUNCATED,
    /**
     * A fork did not start because an {@code onStart} hook blocked it: the hook exited 2 or, unless it declares
     * {@code failOpen: true}, its command could not be run at all. Nothing ran — no iteration, no tokens, no
     * {@code onStop} — and the error message carries the hooks' reasons
     * ({@code Execution blocked by OnStart hook [SUBAGENT/<name>]: ...}). Both ways a fork runs end with it: the ReAct
     * loop and a registered code behavior.
     *
     * <p>
     * A reason of its own, apart from {@link #ERROR}, so a caller can tell a refusal from a fault without reading the
     * message: retrying the same fork under the same guard is refused again, where retrying after an error may not
     * fail again.
     *
     * <p>
     * Only a fork ends with this reason. A turn an {@code onStart} hook blocks does not end with a result at all: the
     * executor throws {@code ExecutionBlockedByHookException}.
     *
     * <p>
     * Added after the first release of this enum, so a peer running an older build does not know the name. The one
     * codec that carries a fork's reason between nodes ({@code JsonTaskResultCodec}) reads a name it does not know as
     * {@link #ERROR} for a failed result, which is what such a peer reported before this value existed.
     */
    BLOCKED,
    /**
     * Execution ended with an unexpected error.
     *
     * <p>
     * Also the reason of an execution {@link StalledIterationGuard} stopped: consecutive iterations whose tool
     * calls all failed, refused cut responses included. No reader tells that apart from another error by this
     * value; the error message does, and names {@code max_tokens} when every stalled iteration was a refused cut
     * response.
     */
    ERROR;

    /**
     * @return true if the agent finished its task successfully on its own terms
     */
    public boolean isSuccessful() {
        return this == COMPLETED;
    }
}
