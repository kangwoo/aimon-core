package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.ArtifactMetadata;

@DisplayName("MessageArtifact Tests")
class MessageArtifactTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should create artifact with all fields")
        void shouldCreateArtifactWithAllFields() {
            MessageArtifact artifact = MessageArtifact.builder().path("/reports/sales.csv").fileName("sales.csv")
                    .size(15360).mimeType("text/csv").toolUseId("toolu_abc123").downloadToken("dl_token_xyz").build();

            assertThat(artifact.getPath()).isEqualTo("/reports/sales.csv");
            assertThat(artifact.getFileName()).isEqualTo("sales.csv");
            assertThat(artifact.getSize()).isEqualTo(15360);
            assertThat(artifact.getMimeType()).hasValue("text/csv");
            assertThat(artifact.getToolUseId()).hasValue("toolu_abc123");
            assertThat(artifact.getDownloadToken()).hasValue("dl_token_xyz");
        }

        @Test
        @DisplayName("Should create artifact with required fields only")
        void shouldCreateArtifactWithRequiredFieldsOnly() {
            MessageArtifact artifact = MessageArtifact.builder().path("/output/result.txt").fileName("result.txt")
                    .build();

            assertThat(artifact.getPath()).isEqualTo("/output/result.txt");
            assertThat(artifact.getFileName()).isEqualTo("result.txt");
            assertThat(artifact.getSize()).isZero();
            assertThat(artifact.getMimeType()).isEmpty();
            assertThat(artifact.getToolUseId()).isEmpty();
            assertThat(artifact.getDownloadToken()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("Should reject null path")
        void shouldRejectNullPath() {
            assertThatThrownBy(() -> MessageArtifact.builder().fileName("test.txt").build())
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Path cannot be null");
        }

        @Test
        @DisplayName("Should reject blank path")
        void shouldRejectBlankPath() {
            assertThatThrownBy(() -> MessageArtifact.builder().path("  ").fileName("test.txt").build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Path cannot be null or blank");
        }

        @Test
        @DisplayName("Should reject null fileName")
        void shouldRejectNullFileName() {
            assertThatThrownBy(() -> MessageArtifact.builder().path("/test/file.txt").build())
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("File name cannot be null");
        }

        @Test
        @DisplayName("Should reject blank fileName")
        void shouldRejectBlankFileName() {
            assertThatThrownBy(() -> MessageArtifact.builder().path("/test/file.txt").fileName("").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("File name cannot be null or blank");
        }

        @Test
        @DisplayName("Should reject negative size")
        void shouldRejectNegativeSize() {
            assertThatThrownBy(
                    () -> MessageArtifact.builder().path("/test/file.txt").fileName("file.txt").size(-1).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Size must be non-negative");
        }

        @Test
        @DisplayName("Should allow zero size")
        void shouldAllowZeroSize() {
            MessageArtifact artifact = MessageArtifact.builder().path("/test/empty.txt").fileName("empty.txt").size(0)
                    .build();

            assertThat(artifact.getSize()).isZero();
        }

        @Test
        @DisplayName("Should allow null mimeType")
        void shouldAllowNullMimeType() {
            MessageArtifact artifact = MessageArtifact.builder().path("/test/file.bin").fileName("file.bin")
                    .mimeType(null).build();

            assertThat(artifact.getMimeType()).isEmpty();
        }

        @Test
        @DisplayName("Should allow null toolUseId")
        void shouldAllowNullToolUseId() {
            MessageArtifact artifact = MessageArtifact.builder().path("/test/file.txt").fileName("file.txt")
                    .toolUseId(null).build();

            assertThat(artifact.getToolUseId()).isEmpty();
        }

        @Test
        @DisplayName("Should allow null downloadToken")
        void shouldAllowNullDownloadToken() {
            MessageArtifact artifact = MessageArtifact.builder().path("/test/file.txt").fileName("file.txt")
                    .downloadToken(null).build();

            assertThat(artifact.getDownloadToken()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("Should be equal for same fields")
        void shouldBeEqualForSameFields() {
            MessageArtifact artifact1 = MessageArtifact.builder().path("/a/b.csv").fileName("b.csv").size(100)
                    .mimeType("text/csv").toolUseId("id1").build();
            MessageArtifact artifact2 = MessageArtifact.builder().path("/a/b.csv").fileName("b.csv").size(100)
                    .mimeType("text/csv").toolUseId("id1").build();

            assertThat(artifact1).isEqualTo(artifact2);
            assertThat(artifact1.hashCode()).isEqualTo(artifact2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different path")
        void shouldNotBeEqualForDifferentPath() {
            MessageArtifact artifact1 = MessageArtifact.builder().path("/a/b.csv").fileName("b.csv").build();
            MessageArtifact artifact2 = MessageArtifact.builder().path("/a/c.csv").fileName("b.csv").build();

            assertThat(artifact1).isNotEqualTo(artifact2);
        }

        @Test
        @DisplayName("Should not be equal for different size")
        void shouldNotBeEqualForDifferentSize() {
            MessageArtifact artifact1 = MessageArtifact.builder().path("/a/b.csv").fileName("b.csv").size(100).build();
            MessageArtifact artifact2 = MessageArtifact.builder().path("/a/b.csv").fileName("b.csv").size(200).build();

            assertThat(artifact1).isNotEqualTo(artifact2);
        }

        @Test
        @DisplayName("Should not be equal for different downloadToken")
        void shouldNotBeEqualForDifferentDownloadToken() {
            MessageArtifact artifact1 = MessageArtifact.builder().path("/a/b.csv").fileName("b.csv")
                    .downloadToken("token1").build();
            MessageArtifact artifact2 = MessageArtifact.builder().path("/a/b.csv").fileName("b.csv")
                    .downloadToken("token2").build();

            assertThat(artifact1).isNotEqualTo(artifact2);
        }

        @Test
        @DisplayName("Should not be equal to null or other type")
        void shouldNotBeEqualToNullOrOtherType() {
            MessageArtifact artifact = MessageArtifact.builder().path("/a/b.csv").fileName("b.csv").build();

            assertThat(artifact).isNotEqualTo(null);
            assertThat(artifact).isNotEqualTo("string");
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("Should include all fields in toString")
        void shouldIncludeAllFieldsInToString() {
            MessageArtifact artifact = MessageArtifact.builder().path("/reports/sales.csv").fileName("sales.csv")
                    .size(15360).mimeType("text/csv").toolUseId("toolu_abc").downloadToken("dl_token_xyz").build();

            String str = artifact.toString();
            assertThat(str).contains("MessageArtifact");
            assertThat(str).contains("/reports/sales.csv");
            assertThat(str).contains("sales.csv");
            assertThat(str).contains("15360");
            assertThat(str).contains("text/csv");
            assertThat(str).contains("toolu_abc");
            assertThat(str).contains("dl_token_xyz");
        }
    }

    @Nested
    @DisplayName("WithDownloadToken")
    class WithDownloadTokenTests {

        @Test
        @DisplayName("Should create new instance with download token")
        void shouldCreateNewInstanceWithDownloadToken() {
            MessageArtifact original = MessageArtifact.builder().path("/reports/sales.csv").fileName("sales.csv")
                    .size(15360).mimeType("text/csv").toolUseId("toolu_abc").build();

            MessageArtifact withToken = original.withDownloadToken("dl_token_123");

            assertThat(withToken.getDownloadToken()).hasValue("dl_token_123");
            assertThat(withToken.getPath()).isEqualTo(original.getPath());
            assertThat(withToken.getFileName()).isEqualTo(original.getFileName());
            assertThat(withToken.getSize()).isEqualTo(original.getSize());
            assertThat(withToken.getMimeType()).isEqualTo(original.getMimeType());
            assertThat(withToken.getToolUseId()).isEqualTo(original.getToolUseId());
        }

        @Test
        @DisplayName("Should not modify original instance")
        void shouldNotModifyOriginalInstance() {
            MessageArtifact original = MessageArtifact.builder().path("/test/file.txt").fileName("file.txt").build();

            original.withDownloadToken("dl_token_123");

            assertThat(original.getDownloadToken()).isEmpty();
        }

        @Test
        @DisplayName("Should allow null download token")
        void shouldAllowNullDownloadToken() {
            MessageArtifact artifact = MessageArtifact.builder().path("/test/file.txt").fileName("file.txt")
                    .downloadToken("existing_token").build();

            MessageArtifact withNull = artifact.withDownloadToken(null);

            assertThat(withNull.getDownloadToken()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ArtifactMetadata Interface")
    class ArtifactMetadataInterface {

        @Test
        @DisplayName("Should implement ArtifactMetadata interface")
        void shouldImplementArtifactMetadataInterface() {
            MessageArtifact artifact = MessageArtifact.builder().path("/test/file.txt").fileName("file.txt").build();

            assertThat(artifact).isInstanceOf(ArtifactMetadata.class);
        }

        @Test
        @DisplayName("Should be accessible through ArtifactMetadata reference")
        void shouldBeAccessibleThroughArtifactMetadataReference() {
            ArtifactMetadata metadata = MessageArtifact.builder().path("/reports/sales.csv").fileName("sales.csv")
                    .size(1024).mimeType("text/csv").toolUseId("tool_1").build();

            assertThat(metadata.getPath()).isEqualTo("/reports/sales.csv");
            assertThat(metadata.getFileName()).isEqualTo("sales.csv");
            assertThat(metadata.getSize()).isEqualTo(1024);
            assertThat(metadata.getMimeType()).hasValue("text/csv");
            assertThat(metadata.getToolUseId()).hasValue("tool_1");
        }
    }
}
