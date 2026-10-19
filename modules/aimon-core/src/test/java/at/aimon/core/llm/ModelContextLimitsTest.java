package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ModelContextLimits - one ratio decides when auto compaction starts")
class ModelContextLimitsTest {

    @Nested
    @DisplayName("thresholds")
    class Thresholds {

        @Test
        @DisplayName("defaults: auto at 0.8 of the effective window, warning 20K below it, blocking 3K under it")
        void defaults() {
            final ModelContextLimits limits = ModelContextLimits.ofContextWindow(128_000);

            assertThat(limits.getAutoCompactRatio()).isEqualTo(ModelContextLimits.DEFAULT_AUTO_COMPACT_RATIO)
                    .isEqualTo(0.8);
            assertThat(limits.getEffectiveContextWindow()).isEqualTo(108_000);
            assertThat(limits.getAutoCompactThreshold()).isEqualTo(86_400);
            assertThat(limits.getWarningThreshold()).isEqualTo(66_400);
            assertThat(limits.getBlockingLimit()).isEqualTo(105_000);
        }

        @Test
        @DisplayName("the threshold is ratio x effective window, for every window size")
        void thresholdIsRatioTimesEffective() {
            assertThat(ModelContextLimits.ofContextWindow(200_000).getAutoCompactThreshold()).isEqualTo(144_000);
            assertThat(ModelContextLimits.ofContextWindow(1_050_000).getAutoCompactThreshold()).isEqualTo(824_000);
            assertThat(ModelContextLimits.builder().contextWindow(200_000).autoCompactRatio(0.5).build()
                    .getAutoCompactThreshold()).isEqualTo(90_000);
        }

        @Test
        @DisplayName("the warning threshold never goes below zero")
        void warningFloorIsZero() {
            final ModelContextLimits limits = ModelContextLimits.builder().contextWindow(100_000).autoCompactRatio(0.1)
                    .build();

            assertThat(limits.getAutoCompactThreshold()).isEqualTo(8_000);
            assertThat(limits.getWarningThreshold()).isZero();
        }

        @Test
        @DisplayName("a full ratio puts auto above the blocking limit, which the guard checks first")
        void fullRatio() {
            final ModelContextLimits limits = ModelContextLimits.builder().autoCompactRatio(1.0).build();

            assertThat(limits.getAutoCompactThreshold()).isEqualTo(limits.getEffectiveContextWindow())
                    .isGreaterThan(limits.getBlockingLimit());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest
        @ValueSource(doubles = {0.0, -0.1, 1.0000001, 2.0, Double.NaN, Double.POSITIVE_INFINITY})
        @DisplayName("a ratio outside (0, 1] is refused")
        void ratioOutOfRange(double ratio) {
            assertThatThrownBy(() -> ModelContextLimits.builder().autoCompactRatio(ratio).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("autoCompactRatio");
        }

        @Test
        @DisplayName("the effective window must hold the warning band")
        void warningBandMustFit() {
            assertThatThrownBy(() -> ModelContextLimits.ofContextWindow(40_000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reservedOutputTokens + warningBuffer");
            assertThat(ModelContextLimits.ofContextWindow(40_001).getWarningThreshold()).isZero();
        }

        @Test
        @DisplayName("the blocking limit must stay above zero")
        void blockingLimitMustBePositive() {
            assertThatThrownBy(() -> ModelContextLimits.builder().contextWindow(1_002).reservedOutputTokens(1_000)
                    .warningBuffer(1).blockingBuffer(1_000).build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reservedOutputTokens + blockingBuffer");
        }

        @Test
        @DisplayName("the blocking band must sit inside the reserved output")
        void blockingWithinReserved() {
            assertThatThrownBy(
                    () -> ModelContextLimits.builder().reservedOutputTokens(1_000).blockingBuffer(1_001).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blockingBuffer");
        }

        @Test
        @DisplayName("every size must be positive")
        void positiveSizes() {
            assertThatThrownBy(() -> ModelContextLimits.ofContextWindow(0)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contextWindow");
            assertThatThrownBy(() -> ModelContextLimits.builder().reservedOutputTokens(0).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reservedOutputTokens");
            assertThatThrownBy(() -> ModelContextLimits.builder().warningBuffer(0).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("warningBuffer");
            assertThatThrownBy(() -> ModelContextLimits.builder().blockingBuffer(0).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blockingBuffer");
        }
    }

    @Test
    @DisplayName("toBuilder round-trips every value")
    void toBuilderRoundTrip() {
        final ModelContextLimits original = ModelContextLimits.builder().contextWindow(300_000)
                .reservedOutputTokens(30_000).autoCompactRatio(0.6).warningBuffer(10_000).blockingBuffer(5_000).build();
        final ModelContextLimits copy = original.toBuilder().build();

        assertThat(copy.getContextWindow()).isEqualTo(300_000);
        assertThat(copy.getReservedOutputTokens()).isEqualTo(30_000);
        assertThat(copy.getAutoCompactRatio()).isEqualTo(0.6);
        assertThat(copy.getWarningBuffer()).isEqualTo(10_000);
        assertThat(copy.getBlockingBuffer()).isEqualTo(5_000);
        assertThat(copy.toString()).isEqualTo(original.toString());
    }

    @Test
    @DisplayName("toString names the ratio")
    void toStringIncludesTheRatio() {
        assertThat(ModelContextLimits.builder().autoCompactRatio(0.6).build().toString())
                .contains("autoCompactRatio=0.6").doesNotContain("autoCompactBuffer");
    }
}
