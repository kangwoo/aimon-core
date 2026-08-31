package at.aimon.core.agent.budget;

/**
 * Outcome of a {@link BudgetTracker#check()} call.
 *
 * <p>
 * The tracker consults the current execution state against its {@link ExecutionBudget} and returns one of these
 * decisions to steer the ReAct loop.
 *
 * <ul>
 * <li>{@link #CONTINUE} — the next iteration may proceed.
 * <li>{@link #STOP} — a budget dimension has been reached or exceeded; the caller should finalise the execution with a
 * matching {@link CompletionReason}.
 * <li>{@link #SHOULD_COMPACT} — a soft hint that the caller should proactively compact the conversation before
 * continuing. Emitted when a budget declares a {@link ExecutionBudget#getCompactionTokenThreshold() compaction
 * token threshold} and the accumulated tokens have reached it, but no hard-STOP dimension has fired. The loop honours
 * the hint by force-compacting and then continues; it is never terminal.
 * </ul>
 */
public enum BudgetDecision {
    CONTINUE, STOP, SHOULD_COMPACT
}
