package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("IngestResult")
class IngestResultTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with valid values stores all fields")
        void builderWithValidValues() {
            IngestResult result = IngestResult.builder().ingestedCount(10).skippedCount(2).updatedPageCount(3)
                    .createdPageCount(7).mergedPageCount(4).durationMs(1500L).errors(List.of("error1")).build();

            assertThat(result.getIngestedCount()).isEqualTo(10);
            assertThat(result.getSkippedCount()).isEqualTo(2);
            assertThat(result.getUpdatedPageCount()).isEqualTo(3);
            assertThat(result.getCreatedPageCount()).isEqualTo(7);
            assertThat(result.getMergedPageCount()).isEqualTo(4);
            assertThat(result.getDurationMs()).isEqualTo(1500L);
            assertThat(result.getErrors()).containsExactly("error1");
        }

        @Test
        @DisplayName("errors defaults to empty list when not set")
        void errorsDefaultToEmpty() {
            IngestResult result = IngestResult.builder().build();

            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("all counts default to zero when not set")
        void countsDefaultToZero() {
            IngestResult result = IngestResult.builder().build();

            assertThat(result.getIngestedCount()).isZero();
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getUpdatedPageCount()).isZero();
            assertThat(result.getCreatedPageCount()).isZero();
            assertThat(result.getMergedPageCount()).isZero();
            assertThat(result.getDurationMs()).isZero();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("negative ingestedCount throws IllegalArgumentException")
        void negativeIngestedCountThrowsIae() {
            assertThatThrownBy(() -> IngestResult.builder().ingestedCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative skippedCount throws IllegalArgumentException")
        void negativeSkippedCountThrowsIae() {
            assertThatThrownBy(() -> IngestResult.builder().skippedCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative updatedPageCount throws IllegalArgumentException")
        void negativeUpdatedPageCountThrowsIae() {
            assertThatThrownBy(() -> IngestResult.builder().updatedPageCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative createdPageCount throws IllegalArgumentException")
        void negativeCreatedPageCountThrowsIae() {
            assertThatThrownBy(() -> IngestResult.builder().createdPageCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative mergedPageCount throws IllegalArgumentException")
        void negativeMergedPageCountThrowsIae() {
            assertThatThrownBy(() -> IngestResult.builder().mergedPageCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative durationMs throws IllegalArgumentException")
        void negativeDurationMsThrowsIae() {
            assertThatThrownBy(() -> IngestResult.builder().durationMs(-1L).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("errors list is unmodifiable")
        void errorsAreUnmodifiable() {
            IngestResult result = IngestResult.builder().errors(new ArrayList<>(List.of("error1"))).build();

            assertThatThrownBy(() -> result.getErrors().add("new-error"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
