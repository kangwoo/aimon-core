package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkflowConcurrencyConfig — two-tier bound, machine-aware defaults, validation")
class WorkflowConcurrencyConfigTest {

    @Test
    @DisplayName("disabled() is off but keeps numeric bounds baked in (gate on isEnabled, not the numbers)")
    void disabledIsOffButNumericBoundsBaked() {
        final WorkflowConcurrencyConfig config = WorkflowConcurrencyConfig.disabled();

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getMaxConcurrency()).isEqualTo(WorkflowConcurrencyConfig.DEFAULT_MAX_CONCURRENCY);
        assertThat(config.getPerBatchMax()).isEqualTo(WorkflowConcurrencyConfig.DEFAULT_MAX_CONCURRENCY);
    }

    @Test
    @DisplayName("disabled() returns the shared singleton")
    void disabledIsSingleton() {
        assertThat(WorkflowConcurrencyConfig.disabled()).isSameAs(WorkflowConcurrencyConfig.disabled());
    }

    @Test
    @DisplayName("enabled(int) is single-tier: perBatchMax defaults to maxConcurrency")
    void enabledSingleTier() {
        final WorkflowConcurrencyConfig config = WorkflowConcurrencyConfig.enabled(6);

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getMaxConcurrency()).isEqualTo(6);
        assertThat(config.getPerBatchMax()).isEqualTo(6);
    }

    @Test
    @DisplayName("enabled(int,int) carries an explicit per-batch cap")
    void enabledTwoTier() {
        final WorkflowConcurrencyConfig config = WorkflowConcurrencyConfig.enabled(8, 2);

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getMaxConcurrency()).isEqualTo(8);
        assertThat(config.getPerBatchMax()).isEqualTo(2);
    }

    @Test
    @DisplayName("defaults() is enabled and machine-aware: max(1, min(16, cores - 2))")
    void defaultsAreMachineAware() {
        final WorkflowConcurrencyConfig config = WorkflowConcurrencyConfig.defaults();
        final int expected = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors() - 2));

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getMaxConcurrency()).isEqualTo(expected);
        assertThat(config.getPerBatchMax()).isEqualTo(expected);
    }

    @Test
    @DisplayName("build() rejects maxConcurrency < 1")
    void rejectsMaxConcurrencyBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowConcurrencyConfig.enabled(0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WorkflowConcurrencyConfig.builder().maxConcurrency(-1).build());
    }

    @Test
    @DisplayName("build() rejects perBatchMax outside [1, maxConcurrency]")
    void rejectsPerBatchOutOfRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowConcurrencyConfig.enabled(4, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowConcurrencyConfig.enabled(4, 0));
    }

    @Test
    @DisplayName("forSharedPool auto-derives an equal fair per-batch cap when perBatchMax was unset")
    void forSharedPoolAutoDerives() {
        // maxConcurrency=8, 4 concurrent runs -> fair share 8/4 = 2 (and strictly < 8).
        assertThat(WorkflowConcurrencyConfig.enabled(8).forSharedPool(4).getPerBatchMax()).isEqualTo(2);
        // One concurrent run -> still capped at maxConcurrency-1 (7), leaving headroom for a concurrent run.
        assertThat(WorkflowConcurrencyConfig.enabled(8).forSharedPool(1).getPerBatchMax()).isEqualTo(7);
        // Fair share floors to at least 1 when there are more runs than workers.
        assertThat(WorkflowConcurrencyConfig.enabled(4).forSharedPool(16).getPerBatchMax()).isEqualTo(1);
    }

    @Test
    @DisplayName("forSharedPool honours an operator-chosen perBatchMax strictly below maxConcurrency as-is")
    void forSharedPoolHonoursTighterCap() {
        // A tighter explicit cap is the operator's responsibility — kept as-is, not re-sized against the run count.
        assertThat(WorkflowConcurrencyConfig.enabled(8, 3).forSharedPool(4).getPerBatchMax()).isEqualTo(3);
        assertThat(WorkflowConcurrencyConfig.enabled(8, 7).forSharedPool(10).getPerBatchMax()).isEqualTo(7);
    }

    @Test
    @DisplayName("forSharedPool tames a whole-pool cap (perBatchMax == maxConcurrency) identically whether defaulted "
            + "or spelled out — no throw")
    void forSharedPoolTamesWholePool() {
        // enabled(8, 8) is numerically identical to enabled(8); both mean "use the whole pool" and tame the same way.
        assertThat(WorkflowConcurrencyConfig.enabled(8, 8).forSharedPool(4).getPerBatchMax())
                .isEqualTo(WorkflowConcurrencyConfig.enabled(8).forSharedPool(4).getPerBatchMax()).isEqualTo(2);
        // Even with a single hosting slot the derivation succeeds (capped at maxConcurrency-1), never throwing.
        assertThat(WorkflowConcurrencyConfig.enabled(8, 8).forSharedPool(1).getPerBatchMax()).isEqualTo(7);
    }

    @Test
    @DisplayName("forSharedPool passes sequential and single-worker configs through unchanged")
    void forSharedPoolExemptions() {
        final WorkflowConcurrencyConfig disabled = WorkflowConcurrencyConfig.disabled();
        assertThat(disabled.forSharedPool(4)).isSameAs(disabled);
        final WorkflowConcurrencyConfig single = WorkflowConcurrencyConfig.enabled(1);
        assertThat(single.forSharedPool(4)).isSameAs(single);
    }

    @Test
    @DisplayName("forSharedPool rejects maxConcurrentRuns < 1")
    void forSharedPoolRejectsBadRunCount() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowConcurrencyConfig.enabled(8).forSharedPool(0));
    }

    // --- Phase 4: maxNestingDepth / maxLiveFanoutThreads --------------------------------------------

    @Test
    @DisplayName("maxNestingDepth defaults to 1 (pre-Phase-4 behaviour); maxLiveFanoutThreads defaults >= maxConcurrency")
    void nestingDefaults() {
        final WorkflowConcurrencyConfig config = WorkflowConcurrencyConfig.enabled(8);

        assertThat(config.getMaxNestingDepth()).isEqualTo(WorkflowConcurrencyConfig.DEFAULT_MAX_NESTING_DEPTH);
        assertThat(config.getMaxNestingDepth()).isEqualTo(1);
        assertThat(config.getMaxLiveFanoutThreads()).isGreaterThanOrEqualTo(config.getMaxConcurrency());
    }

    @Test
    @DisplayName("build() rejects maxNestingDepth < 1")
    void rejectsBadNestingDepth() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WorkflowConcurrencyConfig.builder().maxConcurrency(4).maxNestingDepth(0).build());
    }

    @Test
    @DisplayName("build() rejects maxLiveFanoutThreads < maxConcurrency (leaf ceiling must stay reachable)")
    void rejectsMaxLiveBelowConcurrency() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> WorkflowConcurrencyConfig.builder().maxConcurrency(8).maxLiveFanoutThreads(4).build());
    }

    @Test
    @DisplayName("build() rejects a perBatchMax^maxNestingDepth footprint that exceeds the thread budget")
    void rejectsDepthWidthExplosion() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowConcurrencyConfig.builder().maxConcurrency(8)
                .perBatchMax(8).maxNestingDepth(5).maxLiveFanoutThreads(64).build());
    }

    @Test
    @DisplayName("forSharedPool preserves maxNestingDepth and maxLiveFanoutThreads (does not drop new fields)")
    void forSharedPoolPreservesNewFields() {
        // maxLiveFanoutThreads must cover the worst-case 8^3 = 512 footprint of the un-derived config.
        final WorkflowConcurrencyConfig derived = WorkflowConcurrencyConfig.builder().enabled(true).maxConcurrency(8)
                .maxNestingDepth(3).maxLiveFanoutThreads(1000).build().forSharedPool(4);

        assertThat(derived.getMaxNestingDepth()).isEqualTo(3);
        assertThat(derived.getMaxLiveFanoutThreads()).isEqualTo(1000);
    }
}
