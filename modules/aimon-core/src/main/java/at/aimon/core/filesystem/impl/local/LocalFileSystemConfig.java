package at.aimon.core.filesystem.impl.local;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import at.aimon.core.filesystem.VirtualFileSystem;

/** Configuration for local filesystem backend. Immutable and thread-safe. */
public final class LocalFileSystemConfig {
    /** Default buffer size for I/O operations (8KB). */
    public static final int DEFAULT_BUFFER_SIZE = 8192;
    /** No maximum file size limit. Alias of the contract-wide {@link VirtualFileSystem#NO_MAX_FILE_SIZE}. */
    public static final long NO_MAX_FILE_SIZE = VirtualFileSystem.NO_MAX_FILE_SIZE;
    private final Path basePath;
    private final int bufferSize;
    private final boolean createDirectories;
    private final long maxFileSize;
    private final boolean contentHashEtag;

    /**
     * Creates a new LocalFileSystemConfig with custom settings.
     *
     * @param basePath
     *            Base directory path for file storage
     * @param bufferSize
     *            Buffer size for I/O operations (in bytes)
     * @param createDirectories
     *            Whether to automatically create parent directories
     * @param maxFileSize
     *            Maximum file size in bytes (-1 for no limit)
     */
    public LocalFileSystemConfig(String basePath, int bufferSize, boolean createDirectories, long maxFileSize) {
        this(basePath, bufferSize, createDirectories, maxFileSize, false);
    }

    private LocalFileSystemConfig(String basePath, int bufferSize, boolean createDirectories, long maxFileSize,
            boolean contentHashEtag) {
        Objects.requireNonNull(basePath, "Base path cannot be null");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        if (maxFileSize < -1) {
            throw new IllegalArgumentException("Max file size must be -1 (no limit) or positive");
        }
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
        this.bufferSize = bufferSize;
        this.createDirectories = createDirectories;
        this.maxFileSize = maxFileSize;
        this.contentHashEtag = contentHashEtag;
    }

    /**
     * Creates a new LocalFileSystemConfig with custom settings (no file size limit).
     *
     * @param basePath
     *            Base directory path for file storage
     * @param bufferSize
     *            Buffer size for I/O operations (in bytes)
     * @param createDirectories
     *            Whether to automatically create parent directories
     */
    public LocalFileSystemConfig(String basePath, int bufferSize, boolean createDirectories) {
        this(basePath, bufferSize, createDirectories, NO_MAX_FILE_SIZE);
    }

    /**
     * Creates a new LocalFileSystemConfig with default buffer size and auto-create enabled.
     *
     * @param basePath
     *            Base directory path for file storage
     */
    public LocalFileSystemConfig(String basePath) {
        this(basePath, DEFAULT_BUFFER_SIZE, true, NO_MAX_FILE_SIZE);
    }

    public Path getBasePath() {
        return basePath;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public boolean isCreateDirectories() {
        return createDirectories;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    /**
     * Check if maximum file size limit is enabled.
     *
     * @return true if max file size is set (not -1), false otherwise
     */
    public boolean hasMaxFileSize() {
        return maxFileSize != NO_MAX_FILE_SIZE;
    }

    /**
     * Whether a regular file's metadata carries an etag that is a hash of its content. Off by default; see
     * {@link Builder#contentHashEtag(boolean)} for what it buys and what it costs.
     *
     * @return true if {@code getMetadata} hashes file content into the etag
     */
    public boolean isContentHashEtag() {
        return contentHashEtag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LocalFileSystemConfig that = (LocalFileSystemConfig) o;
        return bufferSize == that.bufferSize && createDirectories == that.createDirectories
                && maxFileSize == that.maxFileSize && contentHashEtag == that.contentHashEtag
                && Objects.equals(basePath, that.basePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(basePath, bufferSize, createDirectories, maxFileSize, contentHashEtag);
    }

    @Override
    public String toString() {
        return "LocalFileSystemConfig{" + "basePath=" + basePath + ", bufferSize=" + bufferSize + ", createDirectories="
                + createDirectories + ", maxFileSize=" + maxFileSize + ", contentHashEtag=" + contentHashEtag + '}';
    }

    /**
     * Creates a new Builder instance for constructing LocalFileSystemConfig.
     *
     * @param basePath
     *            Base directory path for file storage
     * @return A new Builder instance
     */
    public static Builder builder(String basePath) {
        return new Builder(basePath);
    }

    /** Builder for LocalFileSystemConfig. Provides a fluent API for configuration. */
    public static final class Builder {
        private final String basePath;
        private int bufferSize = DEFAULT_BUFFER_SIZE;
        private boolean createDirectories = true;
        private long maxFileSize = NO_MAX_FILE_SIZE;
        private boolean contentHashEtag;

        private Builder(String basePath) {
            this.basePath = basePath;
        }

        /**
         * Sets the buffer size for I/O operations.
         *
         * @param bufferSize
         *            Buffer size in bytes (must be positive)
         * @return This builder instance
         */
        public Builder bufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        /**
         * Sets whether to automatically create parent directories.
         *
         * @param createDirectories
         *            true to auto-create directories, false otherwise
         * @return This builder instance
         */
        public Builder createDirectories(boolean createDirectories) {
            this.createDirectories = createDirectories;
            return this;
        }

        /**
         * Sets the maximum file size limit.
         *
         * @param maxFileSize
         *            Maximum file size in bytes (-1 for no limit)
         * @return This builder instance
         */
        public Builder maxFileSize(long maxFileSize) {
            this.maxFileSize = maxFileSize;
            return this;
        }

        /**
         * Sets whether {@code getMetadata} of a regular file carries an etag that is the SHA-256 of its content
         * ({@code "sha256:"} plus lowercase hex). Off by default.
         *
         * <p>
         * <b>What it buys.</b> Without an etag, the file tools' read stamp is size and modification time. That is
         * enough where the modification time is fine-grained (APFS, ext4: nanoseconds) and misses a rewrite that
         * keeps both where it is not — the same size, within the same second, on a filesystem that counts in seconds
         * (HFS+, some network mounts). With the hash a stamp changes exactly when the bytes do: such a rewrite is
         * caught, and a rewrite of the same bytes, or a {@code touch}, no longer reads as a change.
         *
         * <p>
         * <b>What it costs.</b> One full read of the file for every {@code getMetadata} of a regular file — not only
         * the stamp taken at each {@code Read}, {@code Edit} and {@code Write}, but every other caller of
         * {@code getMetadata} as well (a workflow merge, an artifact archive). The file is streamed through the
         * digest, so memory does not grow with it; time and disk reads do. Leave it off unless the workspace is on a
         * filesystem with a coarse modification time.
         *
         * @param contentHashEtag
         *            true to hash file content into the etag
         * @return This builder instance
         */
        public Builder contentHashEtag(boolean contentHashEtag) {
            this.contentHashEtag = contentHashEtag;
            return this;
        }

        /**
         * Builds a new LocalFileSystemConfig instance.
         *
         * @return A new LocalFileSystemConfig with the configured settings
         * @throws IllegalArgumentException
         *             If any configuration value is invalid
         */
        public LocalFileSystemConfig build() {
            return new LocalFileSystemConfig(basePath, bufferSize, createDirectories, maxFileSize, contentHashEtag);
        }
    }
}
