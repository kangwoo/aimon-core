package at.aimon.core.llm.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ImageContentBlock Tests")
class ImageContentBlockTest {

    private static final byte[] SAMPLE_DATA = {(byte) 0x89, 0x50, 0x4E, 0x47};

    @Test
    @DisplayName("Should create base64 image content block")
    void shouldCreateBase64ImageContentBlock() {
        ImageContentBlock block = ImageContentBlock.ofBase64(SAMPLE_DATA, "image/png");

        assertThat(block.getType()).isEqualTo("image");
        assertThat(block.getSource()).isEqualTo(ImageContentBlock.Source.BASE64);
        assertThat(block.getData()).isEqualTo(SAMPLE_DATA);
        assertThat(block.getMimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Should create URL image content block")
    void shouldCreateUrlImageContentBlock() {
        ImageContentBlock block = ImageContentBlock.ofUrl("https://example.com/image.png", "image/png");

        assertThat(block.getType()).isEqualTo("image");
        assertThat(block.getSource()).isEqualTo(ImageContentBlock.Source.URL);
        assertThat(block.getUrl()).isEqualTo("https://example.com/image.png");
        assertThat(block.getMimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Should support all valid MIME types")
    void shouldSupportAllValidMimeTypes() {
        for (String mimeType : new String[]{"image/png", "image/jpeg", "image/gif", "image/webp"}) {
            ImageContentBlock block = ImageContentBlock.ofBase64(SAMPLE_DATA, mimeType);
            assertThat(block.getMimeType()).isEqualTo(mimeType);
        }
    }

    @Test
    @DisplayName("Should reject unsupported MIME type")
    void shouldRejectUnsupportedMimeType() {
        assertThatThrownBy(() -> ImageContentBlock.ofBase64(SAMPLE_DATA, "image/bmp"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported image MIME type");
    }

    @Test
    @DisplayName("Should reject null data for base64")
    void shouldRejectNullDataForBase64() {
        assertThatThrownBy(() -> ImageContentBlock.ofBase64(null, "image/png"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject null URL")
    void shouldRejectNullUrl() {
        assertThatThrownBy(() -> ImageContentBlock.ofUrl(null, "image/png")).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject null MIME type")
    void shouldRejectNullMimeType() {
        assertThatThrownBy(() -> ImageContentBlock.ofBase64(SAMPLE_DATA, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw when accessing data on URL source")
    void shouldThrowWhenAccessingDataOnUrlSource() {
        ImageContentBlock block = ImageContentBlock.ofUrl("https://example.com/image.png", "image/png");

        assertThatThrownBy(block::getData).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Data is not available for URL source");
    }

    @Test
    @DisplayName("Should throw when accessing URL on base64 source")
    void shouldThrowWhenAccessingUrlOnBase64Source() {
        ImageContentBlock block = ImageContentBlock.ofBase64(SAMPLE_DATA, "image/png");

        assertThatThrownBy(block::getUrl).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("URL is not available for BASE64 source");
    }

    @Test
    @DisplayName("Should defensively copy data on construction")
    void shouldDefensivelyCopyDataOnConstruction() {
        byte[] originalData = {1, 2, 3};
        ImageContentBlock block = ImageContentBlock.ofBase64(originalData, "image/png");

        originalData[0] = 99;
        assertThat(block.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should defensively copy data on retrieval")
    void shouldDefensivelyCopyDataOnRetrieval() {
        ImageContentBlock block = ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png");

        byte[] retrieved = block.getData();
        retrieved[0] = 99;
        assertThat(block.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should provide text representation for base64")
    void shouldProvideTextRepresentationForBase64() {
        ImageContentBlock block = ImageContentBlock.ofBase64(SAMPLE_DATA, "image/png");

        assertThat(block.asText()).contains("image/png").contains("4 bytes");
    }

    @Test
    @DisplayName("Should provide text representation for URL")
    void shouldProvideTextRepresentationForUrl() {
        ImageContentBlock block = ImageContentBlock.ofUrl("https://example.com/image.png", "image/png");

        assertThat(block.asText()).contains("image/png").contains("https://example.com/image.png");
    }

    @Test
    @DisplayName("Should implement equals correctly for base64")
    void shouldImplementEqualsForBase64() {
        ImageContentBlock block1 = ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png");
        ImageContentBlock block2 = ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png");
        ImageContentBlock block3 = ImageContentBlock.ofBase64(new byte[]{4, 5, 6}, "image/png");

        assertThat(block1).isEqualTo(block2);
        assertThat(block1).isNotEqualTo(block3);
    }

    @Test
    @DisplayName("Should implement equals correctly for URL")
    void shouldImplementEqualsForUrl() {
        ImageContentBlock block1 = ImageContentBlock.ofUrl("https://example.com/a.png", "image/png");
        ImageContentBlock block2 = ImageContentBlock.ofUrl("https://example.com/a.png", "image/png");
        ImageContentBlock block3 = ImageContentBlock.ofUrl("https://example.com/b.png", "image/png");

        assertThat(block1).isEqualTo(block2);
        assertThat(block1).isNotEqualTo(block3);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCode() {
        ImageContentBlock block1 = ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png");
        ImageContentBlock block2 = ImageContentBlock.ofBase64(new byte[]{1, 2, 3}, "image/png");

        assertThat(block1.hashCode()).isEqualTo(block2.hashCode());
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        ImageContentBlock base64Block = ImageContentBlock.ofBase64(SAMPLE_DATA, "image/png");
        assertThat(base64Block.toString()).contains("BASE64").contains("image/png");

        ImageContentBlock urlBlock = ImageContentBlock.ofUrl("https://example.com/image.png", "image/png");
        assertThat(urlBlock.toString()).contains("URL").contains("image/png");
    }
}
