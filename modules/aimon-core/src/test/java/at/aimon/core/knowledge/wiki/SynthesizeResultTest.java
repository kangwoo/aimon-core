package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SynthesizeResult")
class SynthesizeResultTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with valid values stores all fields")
        void builderWithValidValues() {
            SynthesizeResult result = SynthesizeResult.builder().createdPageCount(3).updatedPageCount(2).skippedCount(1)
                    .llmCallCount(6).durationMs(1500L).errors(List.of("error1")).build();

            assertThat(result.getCreatedPageCount()).isEqualTo(3);
            assertThat(result.getUpdatedPageCount()).isEqualTo(2);
            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getLlmCallCount()).isEqualTo(6);
            assertThat(result.getDurationMs()).isEqualTo(1500L);
            assertThat(result.getErrors()).containsExactly("error1");
        }

        @Test
        @DisplayName("empty() factory yields all zeros and empty errors")
        void emptyFactory() {
            SynthesizeResult result = SynthesizeResult.empty();

            assertThat(result.getCreatedPageCount()).isZero();
            assertThat(result.getUpdatedPageCount()).isZero();
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getLlmCallCount()).isZero();
            assertThat(result.getDurationMs()).isZero();
            assertThat(result.getErrors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("negative createdPageCount throws")
        void negativeCreated() {
            assertThatThrownBy(() -> SynthesizeResult.builder().createdPageCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative llmCallCount throws")
        void negativeLlmCalls() {
            assertThatThrownBy(() -> SynthesizeResult.builder().llmCallCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative durationMs throws")
        void negativeDuration() {
            assertThatThrownBy(() -> SynthesizeResult.builder().durationMs(-1L).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("errors list is unmodifiable")
        void errorsImmutable() {
            SynthesizeResult result = SynthesizeResult.builder().errors(new ArrayList<>(List.of("e1"))).build();

            assertThatThrownBy(() -> result.getErrors().add("e2")).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
