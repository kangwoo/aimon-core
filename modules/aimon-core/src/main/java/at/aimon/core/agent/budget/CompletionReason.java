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
     * {@link #ERROR} for a failed result ({@link #fromWireName(String, boolean)}), which is what such a peer reported
     * before this value existed.
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

    /**
     * Reads a reason another node wrote, without failing on a name this build does not know.
     *
     * <p>
     * Every decoder that takes a reason off a wire or out of a store reads it through here rather than through
     * {@link #valueOf(String)}. During a rolling upgrade a node on the newer build writes names the older one has not
     * heard of, and the reason is a label sitting next to the payload that matters — an answer, an error message,
     * the fact that an execution is over. Refusing the whole frame or entry over the label is the worse failure, so a
     * name that is absent or unknown reads as the coarse fact the writer's success flag already carries:
     * {@link #COMPLETED} for a success, {@link #ERROR} for a failure.
     *
     * <p>
     * The unknown name is <b>not kept</b>. That is safe only while no reader writes a decoded reason back to where it
     * came from; none does today (the idempotency stores rewrite only entries that carry no result yet). A reader that
     * starts to would turn a newer node's reason into {@code COMPLETED} / {@code ERROR} for every node, and needs a
     * way to carry the raw name first.
     *
     * <p>
     * A caller whose frame has no success flag passes {@code false}: every reason except {@link #COMPLETED} is a
     * non-success ({@link #isSuccessful()}), and a name this build does not know is by construction not
     * {@code COMPLETED}.
     *
     * @param name
     *            the name as written by {@link #name()}; may be null, or a name this build does not define
     * @param success
     *            whether the result the reason belongs to succeeded; decides the fallback and nothing else
     * @return the named reason, or the coarse one for {@code success} when the name is absent or unknown (never null)
     */
    public static CompletionReason fromWireName(String name, boolean success) {
        if (name != null) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                // Fall through to the coarse reason the success flag already implies — see the method javadoc.
            }
        }
        return success ? COMPLETED : ERROR;
    }
}
