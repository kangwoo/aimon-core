package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LlmModel}, focused on the per-request {@code requestTimeout} worst-case ceiling.
 */
@DisplayName("LlmModel - requestTimeout")
class LlmModelTest {

    @Test
    @DisplayName("requestTimeout is empty by default")
    void requestTimeoutEmptyByDefault() {
        LlmModel model = LlmModel.builder().name("claude-sonnet-4-20250514").build();

        assertThat(model.getRequestTimeout()).isEmpty();
    }

    @Test
    @DisplayName("requestTimeout is carried through the builder")
    void requestTimeoutCarriedThrough() {
        Duration timeout = Duration.ofSeconds(45);

        LlmModel model = LlmModel.builder().name("gpt-4").requestTimeout(timeout).build();

        assertThat(model.getRequestTimeout()).contains(timeout);
    }

    @Test
    @DisplayName("null requestTimeout leaves the field unset (no throw)")
    void nullRequestTimeoutLeavesUnset() {
        LlmModel model = LlmModel.builder().requestTimeout(null).build();

        assertThat(model.getRequestTimeout()).isEmpty();
    }

    @Test
    @DisplayName("zero requestTimeout is rejected")
    void zeroRequestTimeoutRejected() {
        assertThatThrownBy(() -> LlmModel.builder().requestTimeout(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Request timeout must be positive");
    }

    @Test
    @DisplayName("negative requestTimeout is rejected")
    void negativeRequestTimeoutRejected() {
        assertThatThrownBy(() -> LlmModel.builder().requestTimeout(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Request timeout must be positive");
    }

    @Test
    @DisplayName("requestTimeout participates in equals/hashCode")
    void requestTimeoutInEqualsAndHashCode() {
        LlmModel a = LlmModel.builder().name("gpt-4").requestTimeout(Duration.ofSeconds(30)).build();
        LlmModel b = LlmModel.builder().name("gpt-4").requestTimeout(Duration.ofSeconds(30)).build();
        LlmModel different = LlmModel.builder().name("gpt-4").requestTimeout(Duration.ofSeconds(31)).build();
        LlmModel unset = LlmModel.builder().name("gpt-4").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
        assertThat(a).isNotEqualTo(unset);
    }
}
