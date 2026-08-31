package at.aimon.core.llm.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DocumentContentBlock Tests")
class DocumentContentBlockTest {

    private static final byte[] SAMPLE_DATA = {0x25, 0x50, 0x44, 0x46}; // %PDF

    @Test
    @DisplayName("Should create document content block")
    void shouldCreateDocumentContentBlock() {
        DocumentContentBlock block = DocumentContentBlock.of(SAMPLE_DATA, "application/pdf");

        assertThat(block.getType()).isEqualTo("document");
        assertThat(block.getData()).isEqualTo(SAMPLE_DATA);
        assertThat(block.getMimeType()).isEqualTo("application/pdf");
        assertThat(block.getFileName()).isNull();
    }

    @Test
    @DisplayName("Should create document content block with file name")
    void shouldCreateDocumentContentBlockWithFileName() {
        DocumentContentBlock block = DocumentContentBlock.of(SAMPLE_DATA, "application/pdf", "report.pdf");

        assertThat(block.getData()).isEqualTo(SAMPLE_DATA);
        assertThat(block.getMimeType()).isEqualTo("application/pdf");
        assertThat(block.getFileName()).isEqualTo("report.pdf");
    }

    @Test
    @DisplayName("Should reject unsupported MIME type")
    void shouldRejectUnsupportedMimeType() {
        assertThatThrownBy(() -> DocumentContentBlock.of(SAMPLE_DATA, "application/msword"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported document MIME type");
    }

    @Test
    @DisplayName("Should reject null data")
    void shouldRejectNullData() {
        assertThatThrownBy(() -> DocumentContentBlock.of(null, "application/pdf"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject null MIME type")
    void shouldRejectNullMimeType() {
        assertThatThrownBy(() -> DocumentContentBlock.of(SAMPLE_DATA, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should defensively copy data on construction")
    void shouldDefensivelyCopyDataOnConstruction() {
        byte[] originalData = {1, 2, 3};
        DocumentContentBlock block = DocumentContentBlock.of(originalData, "application/pdf");

        originalData[0] = 99;
        assertThat(block.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should defensively copy data on retrieval")
    void shouldDefensivelyCopyDataOnRetrieval() {
        DocumentContentBlock block = DocumentContentBlock.of(new byte[]{1, 2, 3}, "application/pdf");

        byte[] retrieved = block.getData();
        retrieved[0] = 99;
        assertThat(block.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should provide text representation without file name")
    void shouldProvideTextRepresentationWithoutFileName() {
        DocumentContentBlock block = DocumentContentBlock.of(SAMPLE_DATA, "application/pdf");

        assertThat(block.asText()).contains("application/pdf").contains("4 bytes");
        assertThat(block.asText()).doesNotContain("report.pdf");
    }

    @Test
    @DisplayName("Should provide text representation with file name")
    void shouldProvideTextRepresentationWithFileName() {
        DocumentContentBlock block = DocumentContentBlock.of(SAMPLE_DATA, "application/pdf", "report.pdf");

        assertThat(block.asText()).contains("application/pdf").contains("report.pdf").contains("4 bytes");
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEquals() {
        DocumentContentBlock block1 = DocumentContentBlock.of(new byte[]{1, 2, 3}, "application/pdf", "a.pdf");
        DocumentContentBlock block2 = DocumentContentBlock.of(new byte[]{1, 2, 3}, "application/pdf", "a.pdf");
        DocumentContentBlock block3 = DocumentContentBlock.of(new byte[]{4, 5, 6}, "application/pdf", "a.pdf");
        DocumentContentBlock block4 = DocumentContentBlock.of(new byte[]{1, 2, 3}, "application/pdf", "b.pdf");

        assertThat(block1).isEqualTo(block2);
        assertThat(block1).isNotEqualTo(block3);
        assertThat(block1).isNotEqualTo(block4);
        assertThat(block1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCode() {
        DocumentContentBlock block1 = DocumentContentBlock.of(new byte[]{1, 2, 3}, "application/pdf", "a.pdf");
        DocumentContentBlock block2 = DocumentContentBlock.of(new byte[]{1, 2, 3}, "application/pdf", "a.pdf");

        assertThat(block1.hashCode()).isEqualTo(block2.hashCode());
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        DocumentContentBlock block = DocumentContentBlock.of(SAMPLE_DATA, "application/pdf", "report.pdf");

        assertThat(block.toString()).contains("DocumentContentBlock").contains("application/pdf")
                .contains("report.pdf");
    }

    @Test
    @DisplayName("Should have toString without file name when not set")
    void shouldHaveToStringWithoutFileNameWhenNotSet() {
        DocumentContentBlock block = DocumentContentBlock.of(SAMPLE_DATA, "application/pdf");

        assertThat(block.toString()).contains("DocumentContentBlock").contains("application/pdf");
        assertThat(block.toString()).doesNotContain("fileName");
    }

    @Test
    @DisplayName("Should create document content block with text/plain MIME type")
    void shouldCreateDocumentContentBlockWithTextPlainMimeType() {
        byte[] textData = "Hello, world!".getBytes();
        DocumentContentBlock block = DocumentContentBlock.of(textData, "text/plain", "notes.txt");

        assertThat(block.getData()).isEqualTo(textData);
        assertThat(block.getMimeType()).isEqualTo("text/plain");
        assertThat(block.getFileName()).isEqualTo("notes.txt");
    }

    @Test
    @DisplayName("Should create document content block with text/markdown MIME type")
    void shouldCreateDocumentContentBlockWithTextMarkdownMimeType() {
        byte[] mdData = "# Title".getBytes();
        DocumentContentBlock block = DocumentContentBlock.of(mdData, "text/markdown", "README.md");

        assertThat(block.getMimeType()).isEqualTo("text/markdown");
    }

    @Test
    @DisplayName("Should create document content block with text/html MIME type")
    void shouldCreateDocumentContentBlockWithTextHtmlMimeType() {
        byte[] htmlData = "<h1>Hello</h1>".getBytes();
        DocumentContentBlock block = DocumentContentBlock.of(htmlData, "text/html", "page.html");

        assertThat(block.getMimeType()).isEqualTo("text/html");
    }

    @Test
    @DisplayName("Should create document content block with text/csv MIME type")
    void shouldCreateDocumentContentBlockWithTextCsvMimeType() {
        byte[] csvData = "a,b,c".getBytes();
        DocumentContentBlock block = DocumentContentBlock.of(csvData, "text/csv", "data.csv");

        assertThat(block.getMimeType()).isEqualTo("text/csv");
    }

    @Test
    @DisplayName("Should return true for isTextBased with text MIME types")
    void shouldReturnTrueForIsTextBasedWithTextMimeTypes() {
        assertThat(DocumentContentBlock.of("data".getBytes(), "text/plain").isTextBased()).isTrue();
        assertThat(DocumentContentBlock.of("data".getBytes(), "text/markdown").isTextBased()).isTrue();
        assertThat(DocumentContentBlock.of("data".getBytes(), "text/html").isTextBased()).isTrue();
        assertThat(DocumentContentBlock.of("data".getBytes(), "text/csv").isTextBased()).isTrue();
    }

    @Test
    @DisplayName("Should return false for isTextBased with non-text MIME types")
    void shouldReturnFalseForIsTextBasedWithNonTextMimeTypes() {
        assertThat(DocumentContentBlock.of(SAMPLE_DATA, "application/pdf").isTextBased()).isFalse();
    }
}
