package at.aimon.core.agent.artifact;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.ArtifactMetadata;
import at.aimon.core.llm.MessageArtifact;

/**
 * Immutable reference to a file generated during agent execution.
 *
 * <p>
 * Actual file content is stored in the {@code VirtualFileSystem}; this object holds only metadata references. The
 * {@code path} corresponds to the VirtualFileSystem path and is backend-agnostic (Local, GridFS, S3).
 *
 * <p>
 * Immutable value object. Use the {@link Builder} to construct instances.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     FileArtifact artifact = FileArtifact.builder().path("/reports/sales-2024.csv").size(15360).mimeType("text/csv")
 *             .fileName("sales-2024.csv").toolUseId("toolu_abc123").build();
 *
 *     String downloadPath = artifact.getPath();
 *     Optional<String> mime = artifact.getMimeType();
 * }
 * </pre>
 *
 * @see ArtifactCollector
 * @see ArtifactMetadata
 */
public final class FileArtifact implements ArtifactMetadata {

    private final String path;
    private final long size;
    private final String mimeType;
    private final String fileName;
    private final String toolUseId;
    private final String downloadToken;

    private FileArtifact(Builder builder) {
        this.path = requireNonBlank(builder.path, "Path cannot be null or blank");
        this.fileName = requireNonBlank(builder.fileName, "File name cannot be null or blank");
        if (builder.size < 0) {
            throw new IllegalArgumentException("Size must be non-negative, got: " + builder.size);
        }
        this.size = builder.size;
        this.mimeType = builder.mimeType;
        this.toolUseId = builder.toolUseId;
        this.downloadToken = builder.downloadToken;
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * Creates a new builder.
     *
     * @return A new FileArtifact.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the VirtualFileSystem path.
     *
     * @return The file path (never null)
     */
    public String getPath() {
        return path;
    }

    /**
     * Gets the file size in bytes.
     *
     * @return The file size (non-negative)
     */
    public long getSize() {
        return size;
    }

    /**
     * Gets the MIME type.
     *
     * @return The MIME type, or empty if unknown
     */
    public Optional<String> getMimeType() {
        return Optional.ofNullable(mimeType);
    }

    /**
     * Gets the file name for user display and Content-Disposition header.
     *
     * @return The file name (never null)
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Gets the tool use ID that created this artifact.
     *
     * @return The tool use ID, or empty if not tracked
     */
    public Optional<String> getToolUseId() {
        return Optional.ofNullable(toolUseId);
    }

    /**
     * Gets the download token for linking to download storage.
     *
     * @return The download token, or empty if not set
     */
    @Override
    public Optional<String> getDownloadToken() {
        return Optional.ofNullable(downloadToken);
    }

    /**
     * Converts this FileArtifact to a {@link MessageArtifact} for storage in conversation messages.
     *
     * @return A new MessageArtifact with the same metadata
     */
    public MessageArtifact toMessageArtifact() {
        return MessageArtifact.builder().path(path).fileName(fileName).size(size).mimeType(mimeType)
                .toolUseId(toolUseId).downloadToken(downloadToken).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FileArtifact that = (FileArtifact) o;
        return size == that.size && path.equals(that.path) && Objects.equals(mimeType, that.mimeType)
                && fileName.equals(that.fileName) && Objects.equals(toolUseId, that.toolUseId)
                && Objects.equals(downloadToken, that.downloadToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, size, mimeType, fileName, toolUseId, downloadToken);
    }

    @Override
    public String toString() {
        return "FileArtifact{" + "path='" + path + '\'' + ", size=" + size + ", mimeType='" + mimeType + '\''
                + ", fileName='" + fileName + '\'' + ", toolUseId='" + toolUseId + '\'' + ", downloadToken='"
                + downloadToken + '\'' + '}';
    }

    /**
     * Builder for constructing {@link FileArtifact} instances.
     *
     * <p>
     * Required fields: {@code path}, {@code fileName}. The {@code size} must be non-negative (defaults to 0).
     * {@code mimeType}, {@code toolUseId} and {@code downloadToken} are optional.
     */
    public static final class Builder {
        private String path;
        private long size;
        private String mimeType;
        private String fileName;
        private String toolUseId;
        private String downloadToken;

        private Builder() {
        }

        /**
         * Sets the VirtualFileSystem path.
         *
         * @param path
         *            The file path (must not be null or blank)
         * @return This builder
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * Sets the file size in bytes.
         *
         * @param size
         *            The file size (must be non-negative)
         * @return This builder
         */
        public Builder size(long size) {
            this.size = size;
            return this;
        }

        /**
         * Sets the MIME type.
         *
         * @param mimeType
         *            The MIME type (nullable)
         * @return This builder
         */
        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        /**
         * Sets the file name for user display.
         *
         * @param fileName
         *            The file name (must not be null or blank)
         * @return This builder
         */
        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        /**
         * Sets the tool use ID that created this artifact.
         *
         * @param toolUseId
         *            The tool use ID (nullable)
         * @return This builder
         */
        public Builder toolUseId(String toolUseId) {
            this.toolUseId = toolUseId;
            return this;
        }

        /**
         * Sets the download token for linking to download storage.
         *
         * @param downloadToken
         *            The download token (nullable)
         * @return This builder
         */
        public Builder downloadToken(String downloadToken) {
            this.downloadToken = downloadToken;
            return this;
        }

        /**
         * Builds a new {@link FileArtifact}.
         *
         * @return A new FileArtifact
         * @throws NullPointerException
         *             if path or fileName is null
         * @throws IllegalArgumentException
         *             if path or fileName is blank, or size is negative
         */
        public FileArtifact build() {
            return new FileArtifact(this);
        }
    }
}
