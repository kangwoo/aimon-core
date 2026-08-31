package at.aimon.core.knowledge;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SearchResult Tests")
class SearchResultTest {

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("Should create result with all fields")
        void testFullBuild() {
            final SearchResult result = SearchResult.builder().documentPath("/docs/runbook.md")
                    .chunkContent("Some relevant text").score(0.85).chunkIndex(3).build();

            assertThat(result.getDocumentPath()).isEqualTo("/docs/runbook.md");
            assertThat(result.getChunkContent()).isEqualTo("Some relevant text");
            assertThat(result.getScore()).isEqualTo(0.85);
            assertThat(result.getChunkIndex()).isEqualTo(3);
            assertThat(result.getMetadata()).isEmpty();
        }

        @Test
        @DisplayName("Should reject score out of range")
        void testInvalidScore() {
            assertThatThrownBy(() -> SearchResult.builder().documentPath("/doc.md").chunkContent("text").score(-0.1)
                    .chunkIndex(0).build()).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> SearchResult.builder().documentPath("/doc.md").chunkContent("text").score(1.1)
                    .chunkIndex(0).build()).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject negative chunkIndex")
        void testNegativeChunkIndex() {
            assertThatThrownBy(() -> SearchResult.builder().documentPath("/doc.md").chunkContent("text").score(0.5)
                    .chunkIndex(-1).build()).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject null documentPath")
        void testNullDocumentPath() {
            assertThatThrownBy(() -> SearchResult.builder().chunkContent("text").score(0.5).chunkIndex(0).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null chunkContent")
        void testNullChunkContent() {
            assertThatThrownBy(() -> SearchResult.builder().documentPath("/doc.md").score(0.5).chunkIndex(0).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should accept boundary scores 0.0 and 1.0")
        void testBoundaryScores() {
            assertThatNoException().isThrownBy(() -> SearchResult.builder().documentPath("/doc.md").chunkContent("text")
                    .score(0.0).chunkIndex(0).build());
            assertThatNoException().isThrownBy(() -> SearchResult.builder().documentPath("/doc.md").chunkContent("text")
                    .score(1.0).chunkIndex(0).build());
        }
    }
}
