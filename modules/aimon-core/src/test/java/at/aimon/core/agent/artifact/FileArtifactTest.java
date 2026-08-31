package at.aimon.core.agent.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.ArtifactMetadata;
import at.aimon.core.llm.MessageArtifact;

@DisplayName("FileArtifact Tests")
class FileArtifactTest {

    @Test
    @DisplayName("Should create artifact with all fields")
    void shouldCreateArtifactWithAllFields() {
        FileArtifact artifact = FileArtifact.builder().path("/reports/sales.csv").size(15360).mimeType("text/csv")
                .fileName("sales.csv").toolUseId("toolu_abc123").downloadToken("dl_token_xyz").build();

        assertThat(artifact.getPath()).isEqualTo("/reports/sales.csv");
        assertThat(artifact.getSize()).isEqualTo(15360);
        assertThat(artifact.getMimeType()).hasValue("text/csv");
        assertThat(artifact.getFileName()).isEqualTo("sales.csv");
        assertThat(artifact.getToolUseId()).hasValue("toolu_abc123");
        assertThat(artifact.getDownloadToken()).hasValue("dl_token_xyz");
    }

    @Test
    @DisplayName("Should create artifact with required fields only")
    void shouldCreateArtifactWithRequiredFieldsOnly() {
        FileArtifact artifact = FileArtifact.builder().path("/output/result.txt").fileName("result.txt").build();

        assertThat(artifact.getPath()).isEqualTo("/output/result.txt");
        assertThat(artifact.getSize()).isZero();
        assertThat(artifact.getMimeType()).isEmpty();
        assertThat(artifact.getFileName()).isEqualTo("result.txt");
        assertThat(artifact.getToolUseId()).isEmpty();
        assertThat(artifact.getDownloadToken()).isEmpty();
    }

    @Test
    @DisplayName("Should reject null path")
    void shouldRejectNullPath() {
        assertThatThrownBy(() -> FileArtifact.builder().fileName("test.txt").build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Path cannot be null");
    }

    @Test
    @DisplayName("Should reject blank path")
    void shouldRejectBlankPath() {
        assertThatThrownBy(() -> FileArtifact.builder().path("  ").fileName("test.txt").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Path cannot be null or blank");
    }

    @Test
    @DisplayName("Should reject null fileName")
    void shouldRejectNullFileName() {
        assertThatThrownBy(() -> FileArtifact.builder().path("/test/file.txt").build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("File name cannot be null");
    }

    @Test
    @DisplayName("Should reject blank fileName")
    void shouldRejectBlankFileName() {
        assertThatThrownBy(() -> FileArtifact.builder().path("/test/file.txt").fileName("").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("File name cannot be null or blank");
    }

    @Test
    @DisplayName("Should reject negative size")
    void shouldRejectNegativeSize() {
        assertThatThrownBy(() -> FileArtifact.builder().path("/test/file.txt").fileName("file.txt").size(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Size must be non-negative");
    }

    @Test
    @DisplayName("Should allow zero size")
    void shouldAllowZeroSize() {
        FileArtifact artifact = FileArtifact.builder().path("/test/empty.txt").fileName("empty.txt").size(0).build();

        assertThat(artifact.getSize()).isZero();
    }

    @Test
    @DisplayName("Should allow null mimeType")
    void shouldAllowNullMimeType() {
        FileArtifact artifact = FileArtifact.builder().path("/test/file.bin").fileName("file.bin").mimeType(null)
                .build();

        assertThat(artifact.getMimeType()).isEmpty();
    }

    @Test
    @DisplayName("Should allow null toolUseId")
    void shouldAllowNullToolUseId() {
        FileArtifact artifact = FileArtifact.builder().path("/test/file.txt").fileName("file.txt").toolUseId(null)
                .build();

        assertThat(artifact.getToolUseId()).isEmpty();
    }

    @Test
    @DisplayName("Should allow null downloadToken")
    void shouldAllowNullDownloadToken() {
        FileArtifact artifact = FileArtifact.builder().path("/test/file.txt").fileName("file.txt").downloadToken(null)
                .build();

        assertThat(artifact.getDownloadToken()).isEmpty();
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEquals() {
        FileArtifact artifact1 = FileArtifact.builder().path("/a/b.csv").size(100).mimeType("text/csv")
                .fileName("b.csv").toolUseId("id1").build();
        FileArtifact artifact2 = FileArtifact.builder().path("/a/b.csv").size(100).mimeType("text/csv")
                .fileName("b.csv").toolUseId("id1").build();
        FileArtifact artifact3 = FileArtifact.builder().path("/a/c.csv").size(100).mimeType("text/csv")
                .fileName("c.csv").toolUseId("id1").build();

        assertThat(artifact1).isEqualTo(artifact2);
        assertThat(artifact1).isNotEqualTo(artifact3);
        assertThat(artifact1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCode() {
        FileArtifact artifact1 = FileArtifact.builder().path("/a/b.csv").size(100).fileName("b.csv").build();
        FileArtifact artifact2 = FileArtifact.builder().path("/a/b.csv").size(100).fileName("b.csv").build();

        assertThat(artifact1.hashCode()).isEqualTo(artifact2.hashCode());
    }

    @Test
    @DisplayName("Should include all fields in toString")
    void shouldIncludeAllFieldsInToString() {
        FileArtifact artifact = FileArtifact.builder().path("/reports/sales.csv").size(15360).mimeType("text/csv")
                .fileName("sales.csv").toolUseId("toolu_abc").downloadToken("dl_token_xyz").build();

        String str = artifact.toString();
        assertThat(str).contains("FileArtifact");
        assertThat(str).contains("/reports/sales.csv");
        assertThat(str).contains("15360");
        assertThat(str).contains("text/csv");
        assertThat(str).contains("sales.csv");
        assertThat(str).contains("toolu_abc");
        assertThat(str).contains("dl_token_xyz");
    }

    @Test
    @DisplayName("Should differentiate by size in equals")
    void shouldDifferentiateBySize() {
        FileArtifact artifact1 = FileArtifact.builder().path("/a/b.csv").size(100).fileName("b.csv").build();
        FileArtifact artifact2 = FileArtifact.builder().path("/a/b.csv").size(200).fileName("b.csv").build();

        assertThat(artifact1).isNotEqualTo(artifact2);
    }

    @Test
    @DisplayName("Should differentiate by downloadToken in equals")
    void shouldDifferentiateByDownloadToken() {
        FileArtifact artifact1 = FileArtifact.builder().path("/a/b.csv").size(100).fileName("b.csv")
                .downloadToken("token1").build();
        FileArtifact artifact2 = FileArtifact.builder().path("/a/b.csv").size(100).fileName("b.csv")
                .downloadToken("token2").build();

        assertThat(artifact1).isNotEqualTo(artifact2);
    }

    @Test
    @DisplayName("Should differentiate by mimeType in equals")
    void shouldDifferentiateByMimeType() {
        FileArtifact artifact1 = FileArtifact.builder().path("/a/b").size(100).fileName("b").mimeType("text/csv")
                .build();
        FileArtifact artifact2 = FileArtifact.builder().path("/a/b").size(100).fileName("b").mimeType("text/plain")
                .build();

        assertThat(artifact1).isNotEqualTo(artifact2);
    }

    @Test
    @DisplayName("Should convert to MessageArtifact with all fields")
    void shouldConvertToMessageArtifactWithAllFields() {
        FileArtifact fileArtifact = FileArtifact.builder().path("/reports/sales.csv").size(15360).mimeType("text/csv")
                .fileName("sales.csv").toolUseId("toolu_abc123").downloadToken("dl_token_xyz").build();

        MessageArtifact messageArtifact = fileArtifact.toMessageArtifact();

        assertThat(messageArtifact.getPath()).isEqualTo(fileArtifact.getPath());
        assertThat(messageArtifact.getFileName()).isEqualTo(fileArtifact.getFileName());
        assertThat(messageArtifact.getSize()).isEqualTo(fileArtifact.getSize());
        assertThat(messageArtifact.getMimeType()).isEqualTo(fileArtifact.getMimeType());
        assertThat(messageArtifact.getToolUseId()).isEqualTo(fileArtifact.getToolUseId());
        assertThat(messageArtifact.getDownloadToken()).isEqualTo(fileArtifact.getDownloadToken());
    }

    @Test
    @DisplayName("Should convert to MessageArtifact with optional fields absent")
    void shouldConvertToMessageArtifactWithOptionalFieldsAbsent() {
        FileArtifact fileArtifact = FileArtifact.builder().path("/output/result.txt").fileName("result.txt").build();

        MessageArtifact messageArtifact = fileArtifact.toMessageArtifact();

        assertThat(messageArtifact.getPath()).isEqualTo("/output/result.txt");
        assertThat(messageArtifact.getFileName()).isEqualTo("result.txt");
        assertThat(messageArtifact.getSize()).isZero();
        assertThat(messageArtifact.getMimeType()).isEmpty();
        assertThat(messageArtifact.getToolUseId()).isEmpty();
        assertThat(messageArtifact.getDownloadToken()).isEmpty();
    }

    @Test
    @DisplayName("Should implement ArtifactMetadata interface")
    void shouldImplementArtifactMetadataInterface() {
        FileArtifact artifact = FileArtifact.builder().path("/test/file.txt").fileName("file.txt").build();

        assertThat(artifact).isInstanceOf(ArtifactMetadata.class);
    }

    @Test
    @DisplayName("Should be accessible through ArtifactMetadata reference")
    void shouldBeAccessibleThroughArtifactMetadataReference() {
        ArtifactMetadata metadata = FileArtifact.builder().path("/reports/sales.csv").size(1024).mimeType("text/csv")
                .fileName("sales.csv").toolUseId("tool_1").build();

        assertThat(metadata.getPath()).isEqualTo("/reports/sales.csv");
        assertThat(metadata.getFileName()).isEqualTo("sales.csv");
        assertThat(metadata.getSize()).isEqualTo(1024);
        assertThat(metadata.getMimeType()).hasValue("text/csv");
        assertThat(metadata.getToolUseId()).hasValue("tool_1");
    }
}
