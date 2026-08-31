package at.aimon.core.agent.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ImageInput Tests")
class ImageInputTest {

    private static final byte[] IMAGE_DATA = {(byte) 0x89, 0x50, 0x4E, 0x47};

    @Test
    @DisplayName("Should create image input via factory method")
    void shouldCreateImageInput() {
        ImageInput input = ImageInput.of(IMAGE_DATA, "image/png");

        assertThat(input.getData()).isEqualTo(IMAGE_DATA);
        assertThat(input.getMimeType()).isEqualTo("image/png");
        assertThat(input.getSize()).isEqualTo(IMAGE_DATA.length);
    }

    @Test
    @DisplayName("Should return IMAGE type")
    void shouldReturnImageType() {
        ImageInput input = ImageInput.of(IMAGE_DATA, "image/png");

        assertThat(input.getType()).isEqualTo(InputType.IMAGE);
    }

    @Test
    @DisplayName("Should return descriptive text via asText")
    void shouldReturnDescriptiveText() {
        ImageInput input = ImageInput.of(IMAGE_DATA, "image/png");

        assertThat(input.asText()).isEqualTo("[Image: image/png, 4 bytes]");
    }

    @Test
    @DisplayName("Should defensively copy data on construction")
    void shouldDefensivelyCopyDataOnConstruction() {
        byte[] originalData = {1, 2, 3};
        ImageInput input = ImageInput.of(originalData, "image/png");

        originalData[0] = 99;
        assertThat(input.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should defensively copy data on retrieval")
    void shouldDefensivelyCopyDataOnRetrieval() {
        ImageInput input = ImageInput.of(new byte[]{1, 2, 3}, "image/png");

        byte[] retrieved = input.getData();
        retrieved[0] = 99;
        assertThat(input.getData()).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Should accept valid image MIME types")
    void shouldAcceptValidImageMimeTypes() {
        assertThat(ImageInput.of(IMAGE_DATA, "image/png").getMimeType()).isEqualTo("image/png");
        assertThat(ImageInput.of(IMAGE_DATA, "image/jpeg").getMimeType()).isEqualTo("image/jpeg");
        assertThat(ImageInput.of(IMAGE_DATA, "image/gif").getMimeType()).isEqualTo("image/gif");
        assertThat(ImageInput.of(IMAGE_DATA, "image/webp").getMimeType()).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("Should reject non-image MIME type")
    void shouldRejectNonImageMimeType() {
        assertThatThrownBy(() -> ImageInput.of(IMAGE_DATA, "text/plain")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME type must start with 'image/'");
    }

    @Test
    @DisplayName("Should reject null data")
    void shouldRejectNullData() {
        assertThatThrownBy(() -> ImageInput.of(null, "image/png")).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Image data cannot be null");
    }

    @Test
    @DisplayName("Should reject null MIME type")
    void shouldRejectNullMimeType() {
        assertThatThrownBy(() -> ImageInput.of(IMAGE_DATA, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MIME type cannot be null");
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEquals() {
        ImageInput input1 = ImageInput.of(new byte[]{1, 2, 3}, "image/png");
        ImageInput input2 = ImageInput.of(new byte[]{1, 2, 3}, "image/png");
        ImageInput input3 = ImageInput.of(new byte[]{4, 5, 6}, "image/png");
        ImageInput input4 = ImageInput.of(new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(input1).isEqualTo(input2);
        assertThat(input1).isNotEqualTo(input3);
        assertThat(input1).isNotEqualTo(input4);
        assertThat(input1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCode() {
        ImageInput input1 = ImageInput.of(new byte[]{1, 2, 3}, "image/png");
        ImageInput input2 = ImageInput.of(new byte[]{1, 2, 3}, "image/png");

        assertThat(input1.hashCode()).isEqualTo(input2.hashCode());
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        ImageInput input = ImageInput.of(IMAGE_DATA, "image/png");

        assertThat(input.toString()).contains("ImageInput").contains("image/png");
    }
}
