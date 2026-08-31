package at.aimon.core.llm.content;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * A content block containing a document.
 *
 * <p>
 * Supports PDF and text-based documents. Documents are stored as binary data with defensive copying.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     byte[] pdfData = Files.readAllBytes(Path.of("document.pdf"));
 *     ContentBlock block = DocumentContentBlock.of(pdfData, "application/pdf");
 *     ContentBlock namedBlock = DocumentContentBlock.of(pdfData, "application/pdf", "report.pdf");
 *
 *     byte[] textData = "Hello, world!".getBytes(StandardCharsets.UTF_8);
 *     ContentBlock textDoc = DocumentContentBlock.of(textData, "text/plain", "notes.txt");
 * }
 * </pre>
 */
public final class DocumentContentBlock implements ContentBlock {

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("application/pdf", "text/plain", "text/markdown",
            "text/html", "text/csv");

    private final byte[] data;
    private final String mimeType;
    private final String fileName;

    private DocumentContentBlock(byte[] data, String mimeType, String fileName) {
        Objects.requireNonNull(data, "Data cannot be null");
        Objects.requireNonNull(mimeType, "MIME type cannot be null");
        validateMimeType(mimeType);

        this.data = data.clone();
        this.mimeType = mimeType;
        this.fileName = fileName;
    }

    /**
     * Creates a DocumentContentBlock with the given data and MIME type.
     *
     * @param data
     *            The document data (must not be null)
     * @param mimeType
     *            The MIME type (must be a supported document type)
     * @return A new DocumentContentBlock
     * @throws NullPointerException
     *             if data or mimeType is null
     * @throws IllegalArgumentException
     *             if mimeType is not supported
     */
    public static DocumentContentBlock of(byte[] data, String mimeType) {
        return new DocumentContentBlock(data, mimeType, null);
    }

    /**
     * Creates a DocumentContentBlock with the given data, MIME type, and file name.
     *
     * @param data
     *            The document data (must not be null)
     * @param mimeType
     *            The MIME type (must be a supported document type)
     * @param fileName
     *            The file name (can be null)
     * @return A new DocumentContentBlock
     * @throws NullPointerException
     *             if data or mimeType is null
     * @throws IllegalArgumentException
     *             if mimeType is not supported
     */
    public static DocumentContentBlock of(byte[] data, String mimeType, String fileName) {
        return new DocumentContentBlock(data, mimeType, fileName);
    }

    private static void validateMimeType(String mimeType) {
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException(
                    "Unsupported document MIME type: " + mimeType + ". Supported types: " + SUPPORTED_MIME_TYPES);
        }
    }

    @Override
    public String getType() {
        return "document";
    }

    @Override
    public String asText() {
        if (fileName != null) {
            return "[Document: " + mimeType + ", " + fileName + ", " + data.length + " bytes]";
        }
        return "[Document: " + mimeType + ", " + data.length + " bytes]";
    }

    /**
     * Gets the document data.
     *
     * @return A copy of the document data (never null)
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
     * Gets the file name.
     *
     * @return The file name (can be null)
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Returns whether this document has a text-based MIME type.
     *
     * @return {@code true} if the MIME type starts with "text/"
     */
    public boolean isTextBased() {
        return mimeType.startsWith("text/");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DocumentContentBlock that = (DocumentContentBlock) o;
        return mimeType.equals(that.mimeType) && Objects.equals(fileName, that.fileName)
                && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mimeType, fileName);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        String nameStr = fileName != null ? ", fileName='" + fileName + "'" : "";
        return "DocumentContentBlock{mimeType='" + mimeType + "'" + nameStr + ", size=" + data.length + " bytes}";
    }
}
