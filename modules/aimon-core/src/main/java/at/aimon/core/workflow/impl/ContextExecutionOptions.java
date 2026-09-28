package at.aimon.core.workflow.impl;

import java.util.Objects;

/**
 * Package-private carrier bundling the execution knobs passed into a {@link DefaultWorkflowContext}: the
 * global leaf-concurrency limiter and the maximum fan-out nesting depth (§6.2). Isolation needs no knob here: an
 * isolated step derives its branch from the execution environment ({@code ExecutionEnvironment.isolate}, §6.3).
 * Immutable; keeps the context constructor within the parameter-count limit.
 */
final class ContextExecutionOptions {

    private final LeafConcurrencyLimiter leafSlots;
    private final int maxNestingDepth;

    /**
     * @param leafSlots
     *            the global leaf-concurrency limiter (must not be null)
     * @param maxNestingDepth
     *            the maximum fan-out nesting depth (must be &gt;= 1)
     */
    ContextExecutionOptions(LeafConcurrencyLimiter leafSlots, int maxNestingDepth) {
        this.leafSlots = Objects.requireNonNull(leafSlots, "leafSlots cannot be null");
        if (maxNestingDepth < 1) {
            throw new IllegalArgumentException("maxNestingDepth must be >= 1, got: " + maxNestingDepth);
        }
        this.maxNestingDepth = maxNestingDepth;
    }

    LeafConcurrencyLimiter leafSlots() {
        return leafSlots;
    }

    int maxNestingDepth() {
        return maxNestingDepth;
    }
}
