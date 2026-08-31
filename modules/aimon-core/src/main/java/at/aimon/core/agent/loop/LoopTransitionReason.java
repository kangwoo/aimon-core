package at.aimon.core.agent.loop;

/**
 * Why the ReAct loop re-entered a new iteration.
 *
 * <p>
 * Where {@link at.aimon.core.agent.budget.CompletionReason} tags <em>why the loop stopped</em>, a
 * {@link LoopTransition} tagged with one of these values tags <em>why the loop continued</em>. The distinction is
 * intentionally observation-only: transitions are attached to the per-iteration tracing span (never used for control
 * flow) so an operator or a unit test can reconstruct the shape of a run without inspecting message contents.
 *
 * <p>
 * A transition is recorded for the second and subsequent iterations; the first iteration is the loop's <em>entry</em>,
 * not a re-entry, so it carries no transition. When more than one reason applies to a single re-entry, the most
 * operationally significant one wins (see {@code OrcaAgentExecutor.resolveLoopTransition}) and the others are noted in
 * {@link LoopTransition#getNote()}.
 *
 * <p>
 * This vocabulary covers the re-entry causes that exist in the current loop. It is expected to grow as opt-in features
 * land — for example a {@code TRUNCATION_CONTINUE} once the continuing truncation-recovery strategy (optional)
 * is wired, or a {@code PROMPT_OVERFLOW_RETRY} if prompt-too-long recovery is ever surfaced as a loop-level re-entry
 * rather than an in-gateway retry.
 */
public enum LoopTransitionReason {

    /** The previous iteration issued tool calls; their results were committed and the loop is continuing normally. */
    NEXT_ITERATION,

    /**
     * At least one queued user input was drained mid-turn (after the tool results were committed, before the next LLM
     * call) and appended to the conversation, so the next iteration will act on that new input.
     */
    QUEUED_INPUT,

    /**
     * The budget tracker returned {@link at.aimon.core.agent.budget.BudgetDecision#SHOULD_COMPACT}, so this iteration
     * ran a proactive (forced) compaction before spending more tokens rather than waiting for the auto-compaction band.
     */
    BUDGET_COMPACT
}
