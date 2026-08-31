package at.aimon.core.workflow.impl;

import java.util.Objects;

import at.aimon.core.workflow.WorktreeEnvironmentFactory;

/**
 * Package-private carrier bundling the execution knobs passed into a {@link DefaultWorkflowContext}: the
 * global leaf-concurrency limiter, the optional worktree environment factory (§6.3), and the maximum fan-out
 * nesting depth (§6.2). Immutable; keeps the context constructor within the parameter-count limit.
 */
final class ContextExecutionOptions {

    private final LeafConcurrencyLimiter leafSlots;
    private final WorktreeEnvironmentFactory worktreeFactory;
    private final int maxNestingDepth;

    /**
     * @param leafSlots
     *            the global leaf-concurrency limiter (must not be null)
     * @param worktreeFactory
     *            the worktree environment factory, or null when isolation is unavailable
     * @param maxNestingDepth
     *            the maximum fan-out nesting depth (must be &gt;= 1)
     */
    ContextExecutionOptions(LeafConcurrencyLimiter leafSlots, WorktreeEnvironmentFactory worktreeFactory,
            int maxNestingDepth) {
        this.leafSlots = Objects.requireNonNull(leafSlots, "leafSlots cannot be null");
        this.worktreeFactory = worktreeFactory;
        if (maxNestingDepth < 1) {
            throw new IllegalArgumentException("maxNestingDepth must be >= 1, got: " + maxNestingDepth);
        }
        this.maxNestingDepth = maxNestingDepth;
    }

    LeafConcurrencyLimiter leafSlots() {
        return leafSlots;
    }

    WorktreeEnvironmentFactory worktreeFactory() {
        return worktreeFactory;
    }

    int maxNestingDepth() {
        return maxNestingDepth;
    }
}
