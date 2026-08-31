package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("IngestOptions")
class IngestOptionsTest {

    @Nested
    @DisplayName("defaults() factory")
    class DefaultsFactory {

        @Test
        @DisplayName("defaults() returns instance with expected default values")
        void defaultsReturnsExpectedValues() {
            IngestOptions options = IngestOptions.defaults();

            assertThat(options.getFilePatterns()).containsExactlyElementsOf(IngestOptions.DEFAULT_FILE_PATTERNS);
            assertThat(options.isRecursive()).isTrue();
            assertThat(options.isOverwrite()).isFalse();
            assertThat(options.isEnableMerge()).isFalse();
            assertThat(options.getMaxDocuments()).isEqualTo(IngestOptions.DEFAULT_MAX_DOCUMENTS);
        }

        @Test
        @DisplayName("DEFAULT_FILE_PATTERNS contains *.md and *.txt")
        void defaultFilePatternsContainExpectedGlobs() {
            assertThat(IngestOptions.DEFAULT_FILE_PATTERNS).containsExactly("*.md", "*.txt");
        }

        @Test
        @DisplayName("DEFAULT_MAX_DOCUMENTS is 500")
        void defaultMaxDocumentsIs500() {
            assertThat(IngestOptions.DEFAULT_MAX_DOCUMENTS).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with custom values stores all values")
        void builderWithCustomValues() {
            IngestOptions options = IngestOptions.builder().filePatterns(List.of("*.md")).recursive(false)
                    .overwrite(true).enableMerge(true).maxDocuments(100).build();

            assertThat(options.getFilePatterns()).containsExactly("*.md");
            assertThat(options.isRecursive()).isFalse();
            assertThat(options.isOverwrite()).isTrue();
            assertThat(options.isEnableMerge()).isTrue();
            assertThat(options.getMaxDocuments()).isEqualTo(100);
        }

        @Test
        @DisplayName("enableMerge defaults to false")
        void enableMergeDefaultsToFalse() {
            IngestOptions options = IngestOptions.builder().build();

            assertThat(options.isEnableMerge()).isFalse();
        }

        @Test
        @DisplayName("autoSynthesize defaults to false")
        void autoSynthesizeDefaultsToFalse() {
            IngestOptions options = IngestOptions.builder().build();

            assertThat(options.isAutoSynthesize()).isFalse();
        }

        @Test
        @DisplayName("autoSynthesize is preserved when set")
        void autoSynthesizePreserved() {
            IngestOptions options = IngestOptions.builder().autoSynthesize(true).build();

            assertThat(options.isAutoSynthesize()).isTrue();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("maxDocuments of zero throws IllegalArgumentException")
        void zeroMaxDocumentsThrowsIae() {
            assertThatThrownBy(() -> IngestOptions.builder().maxDocuments(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative maxDocuments throws IllegalArgumentException")
        void negativeMaxDocumentsThrowsIae() {
            assertThatThrownBy(() -> IngestOptions.builder().maxDocuments(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("filePatterns list is unmodifiable")
        void filePatternsAreUnmodifiable() {
            IngestOptions options = IngestOptions.builder().filePatterns(new ArrayList<>(List.of("*.md"))).build();

            assertThatThrownBy(() -> options.getFilePatterns().add("*.rst"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
