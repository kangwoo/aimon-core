package at.aimon.core.agent.input;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents image-based user input.
 *
 * <p>
 * This input type supports image data for multimodal AI models like GPT-4 Vision, Claude 3, Gemini Pro Vision, etc.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     byte[] imageData = Files.readAllBytes(Path.of("image.png"));
 *     UserInput input = ImageInput.of(imageData, "image/png");
 * }
 * </pre>
 */
public final class ImageInput implements UserInput {
    private final byte[] data;
    private final String mimeType;

    /**
     * Creates a new ImageInput.
     *
     * @param data
     *            The image data (must not be null)
     * @param mimeType
     *            The MIME type (e.g., "image/png", "image/jpeg")
     * @throws NullPointerException
     *             if any parameter is null
     * @throws IllegalArgumentException
     *             if mimeType does not start with "image/"
     */
    private ImageInput(byte[] data, String mimeType) {
        this.data = Objects.requireNonNull(data, "Image data cannot be null").clone();
        Objects.requireNonNull(mimeType, "MIME type cannot be null");
        if (!mimeType.startsWith("image/")) {
            throw new IllegalArgumentException("MIME type must start with 'image/', got: " + mimeType);
        }
        this.mimeType = mimeType;
    }

    /**
     * Creates a new ImageInput with the given data and MIME type.
     *
     * @param data
     *            The image data (must not be null)
     * @param mimeType
     *            The MIME type (e.g., "image/png", "image/jpeg")
     * @return A new ImageInput
     * @throws NullPointerException
     *             if any parameter is null
     * @throws IllegalArgumentException
     *             if mimeType does not start with "image/"
     */
    public static ImageInput of(byte[] data, String mimeType) {
        return new ImageInput(data, mimeType);
    }

    @Override
    public InputType getType() {
        return InputType.IMAGE;
    }

    @Override
    public String asText() {
        return "[Image: " + mimeType + ", " + data.length + " bytes]";
    }

    /**
     * Gets the image data.
     *
     * @return A copy of the image data (never null)
     */
    public byte[] getData() {
        return data.clone();
    }

    /**
     * Gets the MIME type.
     *
     * @return The MIME type (never null)
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * Gets the size of the image data in bytes.
     *
     * @return The size in bytes
     */
    public int getSize() {
        return data.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ImageInput that = (ImageInput) o;
        return Arrays.equals(data, that.data) && mimeType.equals(that.mimeType);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mimeType);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "ImageInput{" + "mimeType='" + mimeType + '\'' + ", size=" + data.length + " bytes" + '}';
    }
}
