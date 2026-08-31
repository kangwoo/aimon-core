package at.aimon.core.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("IndexResult Tests")
class IndexResultTest {

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("Should create result with all fields")
        void testFullBuild() {
            final IndexResult result = IndexResult.builder().indexedDocumentCount(5).indexedChunkCount(20)
                    .skippedDocumentCount(1).durationMs(150).errors(List.of("file.txt: read error")).build();

            assertThat(result.getIndexedDocumentCount()).isEqualTo(5);
            assertThat(result.getIndexedChunkCount()).isEqualTo(20);
            assertThat(result.getSkippedDocumentCount()).isEqualTo(1);
            assertThat(result.getDurationMs()).isEqualTo(150);
            assertThat(result.getErrors()).containsExactly("file.txt: read error");
        }

        @Test
        @DisplayName("Should default to empty errors list")
        void testDefaultErrors() {
            final IndexResult result = IndexResult.builder().build();

            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("Should reject negative counts")
        void testNegativeCounts() {
            assertThatThrownBy(() -> IndexResult.builder().indexedDocumentCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> IndexResult.builder().indexedChunkCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> IndexResult.builder().skippedDocumentCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> IndexResult.builder().durationMs(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("Errors list should be unmodifiable")
        void testErrorsUnmodifiable() {
            final IndexResult result = IndexResult.builder().errors(List.of("error")).build();

            assertThatThrownBy(() -> result.getErrors().add("another"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
