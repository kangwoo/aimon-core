/**
 * Declarative execution budget and runtime enforcement primitives for agent executions.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package defines the model and tracker that let callers set safety limits (iteration count, cumulative tokens,
 * wall-clock duration) on an agent execution and interpret why an execution ended.
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.budget.ExecutionBudget} — immutable declaration of per-execution limits
 * <li>{@link at.aimon.core.agent.budget.CompletionReason} — structured terminal cause returned by the executor
 * <li>{@link at.aimon.core.agent.budget.BudgetTracker} — per-execution counter store that decides when a dimension
 * has been exhausted
 * <li>{@link at.aimon.core.agent.budget.BudgetDecision} — tracker verdict consumed by the ReAct loop driver
 * </ul>
 *
 * <p>
 * Consumers typically instantiate a {@link at.aimon.core.agent.budget.BudgetTracker} per execution, call
 * {@link at.aimon.core.agent.budget.BudgetTracker#check()} at each iteration boundary, and translate a
 * {@link at.aimon.core.agent.budget.BudgetDecision#STOP} outcome into a result carrying the matching
 * {@link at.aimon.core.agent.budget.CompletionReason} exposed via
 * {@link at.aimon.core.agent.budget.BudgetTracker#getStopReason()}.
 */
package at.aimon.core.agent.budget;
