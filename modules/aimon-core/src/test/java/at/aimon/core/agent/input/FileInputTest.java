package at.aimon.core.agent.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FileInput Tests")
class FileInputTest {

    private static final byte[] TEXT_DATA = "Hello, world!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BINARY_DATA = {0x25, 0x50, 0x44, 0x46};

    @Test
    @DisplayName("Should create file input via factory method")
    void shouldCreateFileInput() {
        FileInput input = FileInput.of(TEXT_DATA, "text/plain", "notes.txt");

        assertThat(input.getData()).isEqualTo(TEXT_DATA);
        assertThat(input.getMimeType()).isEqualTo("text/plain");
        assertThat(input.getFileName()).isEqualTo("notes.txt");
        assertThat(input.getSize()).isEqualTo(TEXT_DATA.length);
    }

    @Test
    @DisplayName("Should return FILE type")
    void shouldReturnFileType() {
        FileInput input = FileInput.of(TEXT_DATA, "text/plain", "notes.txt");

        assertThat(input.getType()).isEqualTo(InputType.FILE);
    }

    @Test
    @DisplayName("Should return UTF-8 content for text MIME types")
    void shouldReturnUtf8ContentForTextMime() {
        FileInput input = FileInput.of(TEXT_DATA, "text/plain", "notes.txt");

        assertThat(input.asText()).isEqualTo("Hello, world!");
    }

    @Test
    @DisplayName("Should return UTF-8 content for text/markdown MIME type")
    void shouldReturnUtf8ContentForMarkdownMime() {
        byte[] mdData = "# Title".getBytes(StandardCharsets.UTF_8);
        FileInput input = FileInput.of(mdData, "text/markdown", "README.md");

        assertThat(input.asText()).isEqualTo("# Title");
    }

    @Test
    @DisplayName("Should return description string for binary MIME types")
    void shouldReturnDescriptionForBinaryMime() {
        FileInput input = FileInput.of(BINARY_DATA, "application/pdf", "report.pdf");

        assertThat(input.asText()).isEqualTo("[File: report.pdf, application/pdf, 4 bytes]");
    }

    @Test
    @DisplayName("Should defensively copy data on construction")
    void shouldDefensivelyCopyDataOnConstruction() {
        byte[] originalData = {1, 2, 3};
        FileInput input = FileInput.of(originalData, "text/plain", "test.txt");

        originalData[0] = 99;
        assertThat(input.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should defensively copy data on retrieval")
    void shouldDefensivelyCopyDataOnRetrieval() {
        FileInput input = FileInput.of(new byte[]{1, 2, 3}, "text/plain", "test.txt");

        byte[] retrieved = input.getData();
        retrieved[0] = 99;
        assertThat(input.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should reject null data")
    void shouldRejectNullData() {
        assertThatThrownBy(() -> FileInput.of(null, "text/plain", "test.txt")).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("File data cannot be null");
    }

    @Test
    @DisplayName("Should reject null MIME type")
    void shouldRejectNullMimeType() {
        assertThatThrownBy(() -> FileInput.of(TEXT_DATA, null, "test.txt")).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MIME type cannot be null");
    }

    @Test
    @DisplayName("Should reject null file name")
    void shouldRejectNullFileName() {
        assertThatThrownBy(() -> FileInput.of(TEXT_DATA, "text/plain", null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("File name cannot be null");
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEquals() {
        FileInput input1 = FileInput.of(new byte[]{1, 2, 3}, "text/plain", "a.txt");
        FileInput input2 = FileInput.of(new byte[]{1, 2, 3}, "text/plain", "a.txt");
        FileInput input3 = FileInput.of(new byte[]{4, 5, 6}, "text/plain", "a.txt");
        FileInput input4 = FileInput.of(new byte[]{1, 2, 3}, "text/markdown", "a.txt");
        FileInput input5 = FileInput.of(new byte[]{1, 2, 3}, "text/plain", "b.txt");

        assertThat(input1).isEqualTo(input2);
        assertThat(input1).isNotEqualTo(input3);
        assertThat(input1).isNotEqualTo(input4);
        assertThat(input1).isNotEqualTo(input5);
        assertThat(input1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCode() {
        FileInput input1 = FileInput.of(new byte[]{1, 2, 3}, "text/plain", "a.txt");
        FileInput input2 = FileInput.of(new byte[]{1, 2, 3}, "text/plain", "a.txt");

        assertThat(input1.hashCode()).isEqualTo(input2.hashCode());
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        FileInput input = FileInput.of(TEXT_DATA, "text/plain", "notes.txt");

        assertThat(input.toString()).contains("FileInput").contains("notes.txt").contains("text/plain");
    }
}
