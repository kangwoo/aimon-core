package at.aimon.core.agent.input;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents file-based user input with metadata preservation.
 *
 * <p>
 * Unlike {@link TextInput}, this input type preserves the file name and MIME type, allowing LLM providers to handle the
 * file as a structured document (e.g., via Anthropic's {@code DocumentBlockParam}).
 *
 * <p>
 * Supports text-based files (text/plain, text/markdown, etc.) and binary files (application/pdf, images, etc.).
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     byte[] fileData = Files.readAllBytes(Path.of("config.yaml"));
 *     UserInput input = FileInput.of(fileData, "text/plain", "config.yaml");
 *
 *     InputType type = input.getType(); // InputType.FILE
 *     String text = input.asText(); // file content as UTF-8 string (for text/* MIME types)
 * }
 * </pre>
 *
 * @see TextInput
 * @see ImageInput
 * @see MultimodalInput
 */
public final class FileInput implements UserInput {
    private final byte[] data;
    private final String mimeType;
    private final String fileName;

    /**
     * Creates a new FileInput.
     *
     * @param data
     *            The file data (must not be null)
     * @param mimeType
     *            The MIME type (e.g., "text/plain", "application/pdf")
     * @param fileName
     *            The file name (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    private FileInput(byte[] data, String mimeType, String fileName) {
        this.data = Objects.requireNonNull(data, "File data cannot be null").clone();
        this.mimeType = Objects.requireNonNull(mimeType, "MIME type cannot be null");
        this.fileName = Objects.requireNonNull(fileName, "File name cannot be null");
    }

    /**
     * Creates a new FileInput with the given data, MIME type, and file name.
     *
     * @param data
     *            The file data (must not be null)
     * @param mimeType
     *            The MIME type (e.g., "text/plain", "application/pdf")
     * @param fileName
     *            The file name (must not be null)
     * @return A new FileInput
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static FileInput of(byte[] data, String mimeType, String fileName) {
        return new FileInput(data, mimeType, fileName);
    }

    @Override
    public InputType getType() {
        return InputType.FILE;
    }

    @Override
    public String asText() {
        if (mimeType.startsWith("text/")) {
            return new String(data, StandardCharsets.UTF_8);
        }
        return "[File: " + fileName + ", " + mimeType + ", " + data.length + " bytes]";
    }

    /**
     * Gets the file data.
     *
     * @return A copy of the file data (never null)
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
     * @return The file name (never null)
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Gets the size of the file data in bytes.
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
        FileInput that = (FileInput) o;
        return Arrays.equals(data, that.data) && mimeType.equals(that.mimeType) && fileName.equals(that.fileName);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mimeType, fileName);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "FileInput{" + "fileName='" + fileName + '\'' + ", mimeType='" + mimeType + '\'' + ", size="
                + data.length + " bytes" + '}';
    }
}
