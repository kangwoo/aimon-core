package at.aimon.core.memory.deriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three values a derivation queue is allowed to be tuned with, and the two that cannot be zero.
 *
 * <p>
 * What is worth testing here is the refusal, not the numbers. Both queue implementations read
 * {@code workerCount} straight into a thread-pool size and {@code batchMaxTokens} into a budget they subtract from;
 * a zero in either place produces a queue that accepts tasks and never runs one, which looks from the outside
 * exactly like a queue whose deriver is slow.
 */
@DisplayName("DeriverProperties")
class DeriverPropertiesTest {

    @Test
    @DisplayName("the defaults are usable as they stand")
    void defaultsAreValid() {
        final DeriverProperties defaults = DeriverProperties.defaults();

        assertThat(defaults.getWorkerCount()).isGreaterThanOrEqualTo(1);
        assertThat(defaults.getBatchMaxTokens()).isGreaterThanOrEqualTo(1);
        assertThat(defaults.getPollInterval()).isPositive();
    }

    @Test
    @DisplayName("stated values are kept as stated")
    void statedValuesSurvive() {
        final DeriverProperties props = DeriverProperties.of(2, 4096, Duration.ofMillis(20));

        assertThat(props.getWorkerCount()).isEqualTo(2);
        assertThat(props.getBatchMaxTokens()).isEqualTo(4096);
        assertThat(props.getPollInterval()).isEqualTo(Duration.ofMillis(20));
    }

    @Test
    @DisplayName("a queue with no workers is refused rather than built")
    void workerCountIsChecked() {
        assertThatThrownBy(() -> DeriverProperties.of(0, 1000, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workerCount");
    }

    @Test
    @DisplayName("a batch budget of zero is refused — every batch would be empty")
    void batchMaxTokensIsChecked() {
        assertThatThrownBy(() -> DeriverProperties.of(1, 0, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batchMaxTokens");
    }

    @Test
    @DisplayName("a null poll interval is refused at construction, not at the first poll")
    void pollIntervalIsRequired() {
        assertThatThrownBy(() -> DeriverProperties.of(1, 1000, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pollInterval");
    }
}
