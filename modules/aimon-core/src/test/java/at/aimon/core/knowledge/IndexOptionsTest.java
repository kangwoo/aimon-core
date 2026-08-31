package at.aimon.core.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("IndexOptions Tests")
class IndexOptionsTest {

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("Should have sensible defaults")
        void testDefaults() {
            final IndexOptions options = IndexOptions.defaults();

            assertThat(options.getFilePatterns()).containsExactly("*.md", "*.txt");
            assertThat(options.isRecursive()).isTrue();
            assertThat(options.getMaxDocuments()).isEqualTo(1000);
            assertThat(options.getMaxChunkSize()).isEqualTo(2000);
        }
    }

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("Should create options with custom values")
        void testCustomValues() {
            final IndexOptions options = IndexOptions.builder().filePatterns(List.of("*.yaml")).recursive(false)
                    .maxDocuments(50).maxChunkSize(1000).build();

            assertThat(options.getFilePatterns()).containsExactly("*.yaml");
            assertThat(options.isRecursive()).isFalse();
            assertThat(options.getMaxDocuments()).isEqualTo(50);
            assertThat(options.getMaxChunkSize()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Should reject maxDocuments < 1")
        void testInvalidMaxDocuments() {
            assertThatThrownBy(() -> IndexOptions.builder().maxDocuments(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject maxChunkSize < 1")
        void testInvalidMaxChunkSize() {
            assertThatThrownBy(() -> IndexOptions.builder().maxChunkSize(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("File patterns should be unmodifiable")
        void testFilePatternsUnmodifiable() {
            final IndexOptions options = IndexOptions.builder().filePatterns(List.of("*.md")).build();

            assertThatThrownBy(() -> options.getFilePatterns().add("*.txt"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
