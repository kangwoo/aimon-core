package at.aimon.core.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DocumentChunk Tests")
class DocumentChunkTest {

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("Should create chunk with all fields")
        void testFullBuild() {
            final DocumentChunk chunk = DocumentChunk.builder().documentPath("/docs/runbook.md").content("Some content")
                    .chunkIndex(2).metadata(Map.of("section", "troubleshooting")).build();

            assertThat(chunk.getDocumentPath()).isEqualTo("/docs/runbook.md");
            assertThat(chunk.getContent()).isEqualTo("Some content");
            assertThat(chunk.getChunkIndex()).isEqualTo(2);
            assertThat(chunk.getMetadata()).containsEntry("section", "troubleshooting");
        }

        @Test
        @DisplayName("Should create chunk with minimal fields")
        void testMinimalBuild() {
            final DocumentChunk chunk = DocumentChunk.builder().documentPath("/doc.md").content("text").chunkIndex(0)
                    .build();

            assertThat(chunk.getMetadata()).isEmpty();
        }

        @Test
        @DisplayName("Should reject null documentPath")
        void testNullDocumentPath() {
            assertThatThrownBy(() -> DocumentChunk.builder().content("text").chunkIndex(0).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject empty documentPath")
        void testEmptyDocumentPath() {
            assertThatThrownBy(() -> DocumentChunk.builder().documentPath("").content("text").chunkIndex(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject null content")
        void testNullContent() {
            assertThatThrownBy(() -> DocumentChunk.builder().documentPath("/doc.md").chunkIndex(0).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject empty content")
        void testEmptyContent() {
            assertThatThrownBy(() -> DocumentChunk.builder().documentPath("/doc.md").content("").chunkIndex(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject negative chunkIndex")
        void testNegativeChunkIndex() {
            assertThatThrownBy(
                    () -> DocumentChunk.builder().documentPath("/doc.md").content("text").chunkIndex(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("Metadata should be unmodifiable")
        void testMetadataUnmodifiable() {
            final DocumentChunk chunk = DocumentChunk.builder().documentPath("/doc.md").content("text").chunkIndex(0)
                    .metadata(Map.of("key", "value")).build();

            assertThatThrownBy(() -> chunk.getMetadata().put("new", "val"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("Chunks with same path and index should be equal")
        void testEqual() {
            final DocumentChunk a = DocumentChunk.builder().documentPath("/doc.md").content("text1").chunkIndex(0)
                    .build();
            final DocumentChunk b = DocumentChunk.builder().documentPath("/doc.md").content("text2").chunkIndex(0)
                    .build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Chunks with different index should not be equal")
        void testNotEqual() {
            final DocumentChunk a = DocumentChunk.builder().documentPath("/doc.md").content("text").chunkIndex(0)
                    .build();
            final DocumentChunk b = DocumentChunk.builder().documentPath("/doc.md").content("text").chunkIndex(1)
                    .build();

            assertThat(a).isNotEqualTo(b);
        }
    }
}
