package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SubagentBackgroundConfig — bounded background pool configuration")
class SubagentBackgroundConfigTest {

    @Test
    @DisplayName("defaults(): conservative concurrency with an unbounded queue")
    void defaults() {
        SubagentBackgroundConfig config = SubagentBackgroundConfig.defaults();

        assertThat(config.getMaxConcurrency()).isBetween(1, 4);
        assertThat(config.getQueueCapacity()).isEqualTo(SubagentBackgroundConfig.UNBOUNDED_QUEUE);
        assertThat(config.isQueueUnbounded()).isTrue();
    }

    @Test
    @DisplayName("of(maxConcurrency) sets concurrency and keeps an unbounded queue")
    void ofConcurrencyOnly() {
        SubagentBackgroundConfig config = SubagentBackgroundConfig.of(8);

        assertThat(config.getMaxConcurrency()).isEqualTo(8);
        assertThat(config.isQueueUnbounded()).isTrue();
    }

    @Test
    @DisplayName("of(maxConcurrency, queueCapacity) sets both bounds")
    void ofBothBounds() {
        SubagentBackgroundConfig config = SubagentBackgroundConfig.of(8, 16);

        assertThat(config.getMaxConcurrency()).isEqualTo(8);
        assertThat(config.getQueueCapacity()).isEqualTo(16);
        assertThat(config.isQueueUnbounded()).isFalse();
    }

    @Test
    @DisplayName("builder sets both bounds")
    void builder() {
        SubagentBackgroundConfig config = SubagentBackgroundConfig.builder().maxConcurrency(2).queueCapacity(4).build();

        assertThat(config.getMaxConcurrency()).isEqualTo(2);
        assertThat(config.getQueueCapacity()).isEqualTo(4);
    }

    @Test
    @DisplayName("maxConcurrency < 1 is rejected")
    void rejectsNonPositiveConcurrency() {
        assertThatIllegalArgumentException().isThrownBy(() -> SubagentBackgroundConfig.of(0))
                .withMessageContaining("maxConcurrency");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubagentBackgroundConfig.builder().maxConcurrency(-1).build());
    }

    @Test
    @DisplayName("queueCapacity < 1 is rejected")
    void rejectsNonPositiveQueueCapacity() {
        assertThatIllegalArgumentException().isThrownBy(() -> SubagentBackgroundConfig.of(1, 0))
                .withMessageContaining("queueCapacity");
    }

    @Test
    @DisplayName("toString labels an unbounded queue distinctly")
    void toStringLabelsUnbounded() {
        assertThat(SubagentBackgroundConfig.of(2).toString()).contains("unbounded");
        assertThat(SubagentBackgroundConfig.of(2, 5).toString()).contains("5");
    }
}
