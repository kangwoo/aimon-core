package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolConcurrencyConfig Tests")
class ToolConcurrencyConfigTest {

    @Test
    @DisplayName("disabled() is off with the default bound")
    void disabledDefaults() {
        final ToolConcurrencyConfig config = ToolConcurrencyConfig.disabled();
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getMaxConcurrency()).isEqualTo(ToolConcurrencyConfig.DEFAULT_MAX_CONCURRENCY);
    }

    @Test
    @DisplayName("builder default is disabled with default bound")
    void builderDefaults() {
        final ToolConcurrencyConfig config = ToolConcurrencyConfig.builder().build();
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getMaxConcurrency()).isEqualTo(ToolConcurrencyConfig.DEFAULT_MAX_CONCURRENCY);
    }

    @Test
    @DisplayName("enabled(n) sets enabled and the bound")
    void enabledFactory() {
        final ToolConcurrencyConfig config = ToolConcurrencyConfig.enabled(8);
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getMaxConcurrency()).isEqualTo(8);
    }

    @Test
    @DisplayName("maxConcurrency < 1 is rejected")
    void rejectsNonPositiveConcurrency() {
        assertThatThrownBy(() -> ToolConcurrencyConfig.builder().maxConcurrency(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxConcurrency");
        assertThatThrownBy(() -> ToolConcurrencyConfig.enabled(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("perBatchMax defaults to maxConcurrency when unset")
    void perBatchMaxDefaultsToMaxConcurrency() {
        assertThat(ToolConcurrencyConfig.enabled(8).getPerBatchMax()).isEqualTo(8);
        assertThat(ToolConcurrencyConfig.disabled().getPerBatchMax())
                .isEqualTo(ToolConcurrencyConfig.DEFAULT_MAX_CONCURRENCY);
    }

    @Test
    @DisplayName("perBatchMax can be set below maxConcurrency")
    void perBatchMaxBelowMax() {
        final ToolConcurrencyConfig config = ToolConcurrencyConfig.enabled(8, 2);
        assertThat(config.getMaxConcurrency()).isEqualTo(8);
        assertThat(config.getPerBatchMax()).isEqualTo(2);
    }

    @Test
    @DisplayName("perBatchMax equal to maxConcurrency is allowed")
    void perBatchMaxEqualToMax() {
        assertThat(ToolConcurrencyConfig.enabled(4, 4).getPerBatchMax()).isEqualTo(4);
    }

    @Test
    @DisplayName("perBatchMax > maxConcurrency is rejected")
    void perBatchMaxAboveMaxRejected() {
        assertThatThrownBy(() -> ToolConcurrencyConfig.enabled(4, 5)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("perBatchMax");
        assertThatThrownBy(() -> ToolConcurrencyConfig.builder().maxConcurrency(3).perBatchMax(10).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perBatchMax");
    }

    @Test
    @DisplayName("explicit perBatchMax < 1 is rejected (including 0, which must not be absorbed by the unset sentinel)")
    void perBatchMaxNonPositiveRejected() {
        assertThatThrownBy(() -> ToolConcurrencyConfig.builder().maxConcurrency(4).perBatchMax(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perBatchMax");
        assertThatThrownBy(() -> ToolConcurrencyConfig.builder().maxConcurrency(4).perBatchMax(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perBatchMax");
    }

    @Test
    @DisplayName("streamingOverlap defaults to false")
    void streamingOverlapDefaultsFalse() {
        assertThat(ToolConcurrencyConfig.disabled().isStreamingOverlap()).isFalse();
        assertThat(ToolConcurrencyConfig.builder().build().isStreamingOverlap()).isFalse();
        // The convenience factories opt into concurrency but never into overlap.
        assertThat(ToolConcurrencyConfig.enabled(8).isStreamingOverlap()).isFalse();
        assertThat(ToolConcurrencyConfig.enabled(8, 2).isStreamingOverlap()).isFalse();
    }

    @Test
    @DisplayName("streamingOverlap is settable via the builder and independent of the concurrency bounds")
    void streamingOverlapSettable() {
        final ToolConcurrencyConfig config = ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(4)
                .streamingOverlap(true).build();
        assertThat(config.isStreamingOverlap()).isTrue();
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getMaxConcurrency()).isEqualTo(4);
    }

    @Test
    @DisplayName("streamingOverlap can be requested even while concurrency is disabled (flag carried, effect gated)")
    void streamingOverlapCarriedWhenDisabled() {
        final ToolConcurrencyConfig config = ToolConcurrencyConfig.builder().enabled(false).streamingOverlap(true)
                .build();
        assertThat(config.isStreamingOverlap()).isTrue();
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("toString exposes all fields")
    void toStringExposesFields() {
        assertThat(ToolConcurrencyConfig.enabled(6, 2).toString()).contains("enabled=true").contains("maxConcurrency=6")
                .contains("perBatchMax=2").contains("streamingOverlap=false");
    }
}
