package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ModelContextWindowRegistry;

@DisplayName("ContextPolicy - an agent's compaction policy over the model's window")
class ContextPolicyTest {

    private final ModelContextWindowRegistry models = InMemoryModelContextWindowRegistry.withDefaults();

    @Test
    @DisplayName("an empty policy hands the model registry back untouched")
    void emptyPolicyIsIdentity() {
        assertThat(ContextPolicy.none().applyTo(models)).isSameAs(models);
        assertThat(ContextPolicy.none().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("max-window-tokens lowers a larger model's window and leaves a smaller model's alone")
    void maxWindowTokensCapsNeverRaises() {
        final ModelContextWindowRegistry agent = ContextPolicy.builder().maxWindowTokens(500_000).build()
                .applyTo(models);

        assertThat(agent.resolve("gpt-6-sol").getContextWindow()).isEqualTo(500_000);
        assertThat(agent.resolve("gpt-4o").getContextWindow()).isEqualTo(128_000);
    }

    @Test
    @DisplayName("auto-compact-ratio replaces the model's ratio; the blocking limit stays at the window")
    void ratioReplacesTheModelsRatio() {
        final ModelContextLimits limits = ContextPolicy.builder().autoCompactRatio(0.5).build().applyTo(models)
                .resolve("gpt-6-sol");

        // 0.5 of the 1.03M effective window, instead of the default 0.8 (824,000).
        assertThat(limits.getAutoCompactRatio()).isEqualTo(0.5);
        assertThat(limits.getAutoCompactThreshold()).isEqualTo(515_000);
        assertThat(limits.getWarningThreshold()).isEqualTo(495_000);
        assertThat(limits.getBlockingLimit()).isEqualTo(1_027_000);
    }

    @Test
    @DisplayName("a ratio later than the default is honoured too: it replaces, it does not take the earlier of two")
    void aLaterRatioIsHonoured() {
        final ModelContextLimits limits = ContextPolicy.builder().autoCompactRatio(0.95).build().applyTo(models)
                .resolve("gpt-4o");

        assertThat(limits.getAutoCompactThreshold()).isEqualTo((int) (0.95 * (128_000 - 20_000)));
        assertThat(limits.getAutoCompactThreshold()).isGreaterThan(models.resolve("gpt-4o").getAutoCompactThreshold());
    }

    @Test
    @DisplayName("the two combine: the ratio applies to the capped window")
    void bothCombine() {
        final ModelContextLimits limits = ContextPolicy.builder().maxWindowTokens(500_000).autoCompactRatio(0.5).build()
                .applyTo(models).resolve("gpt-6.1-sol");

        assertThat(limits.getAutoCompactThreshold()).isEqualTo(240_000);
        assertThat(limits.getBlockingLimit()).isEqualTo(477_000);
    }

    @Test
    @DisplayName("refuses a ratio outside [0.1, 1] and a cap below the smallest window the limits accept")
    void refusesBadValues() {
        assertThatThrownBy(() -> ContextPolicy.builder().autoCompactRatio(0.0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("autoCompactRatio");
        assertThatThrownBy(() -> ContextPolicy.builder().autoCompactRatio(0.09).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("autoCompactRatio");
        assertThatThrownBy(() -> ContextPolicy.builder().autoCompactRatio(1.5).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("autoCompactRatio");
        assertThatThrownBy(() -> ContextPolicy.builder().autoCompactRatio(Double.NaN).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("autoCompactRatio");
        assertThatThrownBy(
                () -> ContextPolicy.builder().maxWindowTokens(ContextPolicy.MIN_MAX_WINDOW_TOKENS - 1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxWindowTokens");
        assertThat(ContextPolicy.builder().autoCompactRatio(ContextPolicy.MIN_AUTO_COMPACT_RATIO).build()
                .getAutoCompactRatio()).contains(ContextPolicy.MIN_AUTO_COMPACT_RATIO);
    }

    @Test
    @DisplayName("the smallest cap is exactly the smallest window ModelContextLimits accepts with its defaults")
    void theSmallestCapFitsTheDefaults() {
        assertThat(ModelContextLimits.ofContextWindow(ContextPolicy.MIN_MAX_WINDOW_TOKENS).getContextWindow())
                .isEqualTo(ContextPolicy.MIN_MAX_WINDOW_TOKENS);
        assertThatThrownBy(() -> ModelContextLimits.ofContextWindow(ContextPolicy.MIN_MAX_WINDOW_TOKENS - 1))
                .isInstanceOf(IllegalArgumentException.class);
        final ModelContextLimits capped = ContextPolicy.builder().maxWindowTokens(ContextPolicy.MIN_MAX_WINDOW_TOKENS)
                .build().applyTo(models).resolve("gpt-4o");
        assertThat(capped.getContextWindow()).isEqualTo(ContextPolicy.MIN_MAX_WINDOW_TOKENS);
    }

    @Test
    @DisplayName("a cap a model's wider buffers do not fit keeps the model's window, still applies the ratio")
    void aCapThatDoesNotFitKeepsTheModelsWindow() {
        final ModelContextWindowRegistry wide = InMemoryModelContextWindowRegistry.builder()
                .register("wide",
                        ModelContextLimits.builder().contextWindow(400_000).reservedOutputTokens(100_000).build())
                .build();
        final ModelContextWindowRegistry agent = ContextPolicy.builder()
                .maxWindowTokens(ContextPolicy.MIN_MAX_WINDOW_TOKENS).autoCompactRatio(0.5).build().applyTo(wide);

        assertThatCode(() -> agent.resolve("wide")).doesNotThrowAnyException();
        final ModelContextLimits limits = agent.resolve("wide");
        assertThat(limits.getContextWindow()).isEqualTo(400_000);
        assertThat(limits.getAutoCompactThreshold()).isEqualTo(150_000);
        // Looked up again: same answer (the warning is logged once, not on every lookup).
        assertThat(agent.resolve("wide").getContextWindow()).isEqualTo(400_000);
    }

    @Test
    @DisplayName("equal policies are equal and hash alike")
    void equality() {
        final ContextPolicy a = ContextPolicy.builder().maxWindowTokens(500_000).autoCompactRatio(0.5).build();
        final ContextPolicy b = ContextPolicy.builder().maxWindowTokens(500_000).autoCompactRatio(0.5).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(ContextPolicy.none());
        assertThat(a.toString()).contains("maxWindowTokens=500000").contains("autoCompactRatio=0.5");
    }
}
