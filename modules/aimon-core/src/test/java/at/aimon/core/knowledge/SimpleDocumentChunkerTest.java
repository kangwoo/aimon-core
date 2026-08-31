package at.aimon.core.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SimpleDocumentChunker Tests")
class SimpleDocumentChunkerTest {

    private final SimpleDocumentChunker chunker = new SimpleDocumentChunker();

    @Nested
    @DisplayName("Markdown Section Splitting")
    class MarkdownSectionSplitting {

        @Test
        @DisplayName("Should split by markdown headers")
        void testSplitByHeaders() {
            final String content = """
                    # Introduction
                    This is the intro.

                    ## Section One
                    Content of section one.

                    ## Section Two
                    Content of section two.
                    """;

            final List<DocumentChunk> chunks = chunker.chunk("/doc.md", content);

            assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
            assertThat(chunks.get(0).getContent()).contains("Introduction");
            assertThat(chunks.get(1).getContent()).contains("Section One");
            assertThat(chunks.get(2).getContent()).contains("Section Two");
        }

        @Test
        @DisplayName("Should preserve chunk indices in order")
        void testChunkIndices() {
            final String content = """
                    # Header 1
                    Content 1.

                    # Header 2
                    Content 2.
                    """;

            final List<DocumentChunk> chunks = chunker.chunk("/doc.md", content);

            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
                assertThat(chunks.get(i).getDocumentPath()).isEqualTo("/doc.md");
            }
        }
    }

    @Nested
    @DisplayName("Plain Text Fallback")
    class PlainTextFallback {

        @Test
        @DisplayName("Should split plain text by size")
        void testPlainTextSplit() {
            final String content = "A".repeat(1200);
            final SimpleDocumentChunker smallChunker = new SimpleDocumentChunker(500, 0);

            final List<DocumentChunk> chunks = smallChunker.chunk("/doc.txt", content);

            assertThat(chunks).hasSizeGreaterThan(1);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should return empty list for empty content")
        void testEmptyContent() {
            final List<DocumentChunk> chunks = chunker.chunk("/doc.md", "");

            assertThat(chunks).isEmpty();
        }

        @Test
        @DisplayName("Should reject null documentPath")
        void testNullDocumentPath() {
            assertThatThrownBy(() -> chunker.chunk(null, "content")).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null content")
        void testNullContent() {
            assertThatThrownBy(() -> chunker.chunk("/doc.md", null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should handle single-line content")
        void testSingleLine() {
            final List<DocumentChunk> chunks = chunker.chunk("/doc.md", "Single line content.");

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getContent()).isEqualTo("Single line content.");
        }
    }

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorValidation {

        @Test
        @DisplayName("Should reject chunkSize <= 0")
        void testInvalidChunkSize() {
            assertThatThrownBy(() -> new SimpleDocumentChunker(0, 0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject negative overlap")
        void testNegativeOverlap() {
            assertThatThrownBy(() -> new SimpleDocumentChunker(500, -1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject overlap >= chunkSize")
        void testOverlapTooLarge() {
            assertThatThrownBy(() -> new SimpleDocumentChunker(500, 500)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
