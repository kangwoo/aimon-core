package at.aimon.core.workflow;

import java.util.Objects;

/**
 * Run-scoped backstops for an workflow run: a hard agent-count ceiling and an optional aggregate token ceiling.
 *
 * <p>
 * Both are enforced by {@code DefaultWorkflowContext} and, on breach, abort the run via
 * {@link at.aimon.core.workflow.exception.WorkflowBudgetExceededException}. The agent-count ceiling is a
 * runaway-script backstop (always active, default {@value #DEFAULT_MAX_AGENTS}); the token ceiling is opt-in
 * ({@code maxTokens <= 0} means unlimited, normalized to {@code 0}) and enforced <em>post-hoc</em> — a subagent's
 * tokens
 * are counted after it finishes, so the agent that crosses the line still completes and further {@code agent()} calls
 * are refused. Under concurrent fan-out, up to the fan-out width (see the concurrency config's {@code perBatchMax} /
 * {@code maxConcurrency}) may cross the line before it is observed; the accumulator is exact, so the next
 * {@code agent()} past the ceiling is always refused. Size the ceiling with that overshoot (roughly
 * {@code perBatchMax × per-agent tokens}) in mind.
 *
 * <p>
 * Immutable value object.
 */
public final class WorkflowBudget {

    /** Default agent-count ceiling per run (mirrors the total-agent cap of the reference design). */
    public static final int DEFAULT_MAX_AGENTS = 1000;

    private final int maxAgents;
    private final long maxTokens;
    private final long maxCostMicros;

    private WorkflowBudget(int maxAgents, long maxTokens, long maxCostMicros) {
        if (maxAgents < 1) {
            throw new IllegalArgumentException("maxAgents must be >= 1, got: " + maxAgents);
        }
        this.maxAgents = maxAgents;
        // Normalize every "unlimited" encoding to 0 so equal budgets compare equal.
        this.maxTokens = Math.max(0, maxTokens);
        this.maxCostMicros = Math.max(0, maxCostMicros);
    }

    /**
     * @return the default budget: {@value #DEFAULT_MAX_AGENTS} agents, no token limit, no cost limit
     */
    public static WorkflowBudget defaults() {
        return new WorkflowBudget(DEFAULT_MAX_AGENTS, 0, 0);
    }

    /**
     * Budget with a custom agent-count ceiling and no token/cost limit.
     *
     * @param maxAgents
     *            the agent-count ceiling (must be >= 1)
     * @return a new budget
     */
    public static WorkflowBudget ofAgents(int maxAgents) {
        return new WorkflowBudget(maxAgents, 0, 0);
    }

    /**
     * Budget with an agent-count ceiling and an aggregate token ceiling (no cost limit).
     *
     * @param maxAgents
     *            the agent-count ceiling (must be >= 1)
     * @param maxTokens
     *            the aggregate total-token ceiling across the run; {@code <= 0} means unlimited
     * @return a new budget
     */
    public static WorkflowBudget of(int maxAgents, long maxTokens) {
        return new WorkflowBudget(maxAgents, maxTokens, 0);
    }

    /**
     * Budget with an agent-count ceiling, an aggregate token ceiling, and an aggregate USD cost ceiling.
     *
     * @param maxAgents
     *            the agent-count ceiling (must be >= 1)
     * @param maxTokens
     *            the aggregate total-token ceiling; {@code <= 0} means unlimited
     * @param maxCostUsd
     *            the aggregate USD cost ceiling across the run; {@code <= 0} means unlimited
     * @return a new budget
     */
    public static WorkflowBudget of(int maxAgents, long maxTokens, double maxCostUsd) {
        return new WorkflowBudget(maxAgents, maxTokens, usdToMicros(maxCostUsd));
    }

    private static long usdToMicros(double usd) {
        return usd <= 0 ? 0 : Math.round(usd * 1_000_000d);
    }

    /**
     * @return the agent-count ceiling (always >= 1)
     */
    public int getMaxAgents() {
        return maxAgents;
    }

    /**
     * @return the aggregate total-token ceiling; {@code <= 0} means unlimited (see {@link #hasTokenLimit()})
     */
    public long getMaxTokens() {
        return maxTokens;
    }

    /**
     * @return {@code true} if a finite aggregate token ceiling is set
     */
    public boolean hasTokenLimit() {
        return maxTokens > 0;
    }

    /**
     * @return the aggregate USD cost ceiling in micros; {@code <= 0} means unlimited (see {@link #hasCostLimit()})
     */
    public long getMaxCostMicros() {
        return maxCostMicros;
    }

    /**
     * @return {@code true} if a finite aggregate USD cost ceiling is set
     */
    public boolean hasCostLimit() {
        return maxCostMicros > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final WorkflowBudget that = (WorkflowBudget) o;
        return maxAgents == that.maxAgents && maxTokens == that.maxTokens && maxCostMicros == that.maxCostMicros;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAgents, maxTokens, maxCostMicros);
    }

    @Override
    public String toString() {
        return "WorkflowBudget{maxAgents=" + maxAgents + ", maxTokens=" + (hasTokenLimit() ? maxTokens : "∞")
                + ", maxCostMicros=" + (hasCostLimit() ? maxCostMicros : "∞") + '}';
    }
}
