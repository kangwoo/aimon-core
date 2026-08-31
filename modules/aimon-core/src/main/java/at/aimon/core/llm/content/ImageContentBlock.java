package at.aimon.core.llm.content;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * A content block containing an image.
 *
 * <p>
 * Supports two source types: base64-encoded binary data and URLs. Images are validated against a whitelist of supported
 * MIME types.
 *
 * <p>
 * Immutable and thread-safe. Binary data is defensively copied on construction and retrieval.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Base64 image
 *     ContentBlock block = ImageContentBlock.ofBase64(imageBytes, "image/png");
 *
 *     // URL image
 *     ContentBlock block = ImageContentBlock.ofUrl("https://example.com/image.png", "image/png");
 * }
 * </pre>
 */
public final class ImageContentBlock implements ContentBlock {

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("image/png", "image/jpeg", "image/gif",
            "image/webp");

    private final Source source;
    private final byte[] data;
    private final String url;
    private final String mimeType;

    private ImageContentBlock(Source source, byte[] data, String url, String mimeType) {
        this.source = Objects.requireNonNull(source, "Source cannot be null");
        this.mimeType = Objects.requireNonNull(mimeType, "MIME type cannot be null");
        validateMimeType(mimeType);

        if (source == Source.BASE64) {
            Objects.requireNonNull(data, "Data cannot be null for BASE64 source");
            this.data = data.clone();
            this.url = null;
        } else {
            Objects.requireNonNull(url, "URL cannot be null for URL source");
            this.data = null;
            this.url = url;
        }
    }

    /**
     * Creates an ImageContentBlock from base64-encoded binary data.
     *
     * @param data
     *            The image data (must not be null)
     * @param mimeType
     *            The MIME type (must be a supported image type)
     * @return A new ImageContentBlock
     * @throws NullPointerException
     *             if data or mimeType is null
     * @throws IllegalArgumentException
     *             if mimeType is not supported
     */
    public static ImageContentBlock ofBase64(byte[] data, String mimeType) {
        return new ImageContentBlock(Source.BASE64, data, null, mimeType);
    }

    /**
     * Creates an ImageContentBlock from a URL.
     *
     * @param url
     *            The image URL (must not be null)
     * @param mimeType
     *            The MIME type (must be a supported image type)
     * @return A new ImageContentBlock
     * @throws NullPointerException
     *             if url or mimeType is null
     * @throws IllegalArgumentException
     *             if mimeType is not supported
     */
    public static ImageContentBlock ofUrl(String url, String mimeType) {
        return new ImageContentBlock(Source.URL, null, url, mimeType);
    }

    private static void validateMimeType(String mimeType) {
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException(
                    "Unsupported image MIME type: " + mimeType + ". Supported types: " + SUPPORTED_MIME_TYPES);
        }
    }

    @Override
    public String getType() {
        return "image";
    }

    @Override
    public String asText() {
        if (source == Source.BASE64) {
            return "[Image: " + mimeType + ", " + data.length + " bytes]";
        }
        return "[Image: " + mimeType + ", " + url + "]";
    }

    /**
     * Gets the image source type.
     *
     * @return The source type (never null)
     */
    public Source getSource() {
        return source;
    }

    /**
     * Gets the image data.
     *
     * @return A copy of the image data (never null)
     * @throws IllegalStateException
     *             if the source is URL
     */
    public byte[] getData() {
        if (source != Source.BASE64) {
            throw new IllegalStateException("Data is not available for URL source. Use getUrl() instead.");
        }
        return data.clone();
    }

    /**
     * Gets the image URL.
     *
     * @return The image URL (never null)
     * @throws IllegalStateException
     *             if the source is BASE64
     */
    public String getUrl() {
        if (source != Source.URL) {
            throw new IllegalStateException("URL is not available for BASE64 source. Use getData() instead.");
        }
        return url;
    }

    /**
     * Gets the MIME type.
     *
     * @return The MIME type (never null)
     */
    public String getMimeType() {
        return mimeType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ImageContentBlock that = (ImageContentBlock) o;
        return source == that.source && mimeType.equals(that.mimeType) && Arrays.equals(data, that.data)
                && Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(source, mimeType, url);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        if (source == Source.BASE64) {
            return "ImageContentBlock{source=BASE64, mimeType='" + mimeType + "', size=" + data.length + " bytes}";
        }
        return "ImageContentBlock{source=URL, mimeType='" + mimeType + "', url='" + url + "'}";
    }

    /** The source type of the image data. */
    public enum Source {
        /** Base64-encoded binary data. */
        BASE64,
        /** URL reference to the image. */
        URL
    }
}
