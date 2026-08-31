package at.aimon.core.filesystem;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable metadata about a file or directory without its content. Thread-safe and suitable for concurrent access.
 *
 * <p>
 * Attributes: - path (String, required): Normalized path to the file or directory - size (long, required): Size in
 * bytes (non-negative, 0 for directories) - createdAt (Instant, required): Creation timestamp in UTC - modifiedAt
 * (Instant, required): Last modification timestamp in UTC - directory (boolean, optional): Whether this entry is a
 * directory (defaults to false) - mimeType (String, optional): MIME type (e.g., "text/plain", "image/jpeg") -
 * customMetadata (Map, optional): Backend-specific metadata
 */
public final class FileMetadata {
    /** FileMetadata Builder를 반환한다. */
    public static Builder builder() {
        return new Builder();
    }

    private final String path;
    private final long size;
    private final Instant createdAt;
    private final Instant modifiedAt;
    private final boolean directory;
    private final String mimeType;
    private final Map<String, String> customMetadata;

    private FileMetadata(Builder builder) {
        path = Objects.requireNonNull(builder.path, "Path cannot be null");
        size = builder.size;
        createdAt = Objects.requireNonNull(builder.createdAt, "CreatedAt cannot be null");
        modifiedAt = Objects.requireNonNull(builder.modifiedAt, "ModifiedAt cannot be null");
        directory = builder.directory;
        mimeType = builder.mimeType;
        customMetadata = Collections.unmodifiableMap(new HashMap<>(builder.customMetadata));

        if (size < 0) {
            throw new IllegalArgumentException("Size must be non-negative, got: " + size);
        }
        if (modifiedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("ModifiedAt cannot be before createdAt");
        }
    }

    public String getPath() {
        return path;
    }

    public long getSize() {
        return size;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public boolean isDirectory() {
        return directory;
    }

    public Optional<String> getMimeType() {
        return Optional.ofNullable(mimeType);
    }

    public Map<String, String> getCustomMetadata() {
        return customMetadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FileMetadata that = (FileMetadata) o;
        return size == that.size && directory == that.directory && Objects.equals(path, that.path)
                && Objects.equals(createdAt, that.createdAt) && Objects.equals(modifiedAt, that.modifiedAt)
                && Objects.equals(mimeType, that.mimeType) && Objects.equals(customMetadata, that.customMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, size, directory, createdAt, modifiedAt, mimeType, customMetadata);
    }

    @Override
    public String toString() {
        return "FileMetadata{" + "path='" + path + '\'' + ", size=" + size + ", directory=" + directory + ", createdAt="
                + createdAt + ", modifiedAt=" + modifiedAt + ", mimeType='" + mimeType + '\'' + ", customMetadata="
                + customMetadata + '}';
    }

    /** Builder for constructing FileMetadata instances. */
    public static class Builder {
        private String path;
        private long size;
        private Instant createdAt;
        private Instant modifiedAt;
        private boolean directory;
        private String mimeType;
        private final Map<String, String> customMetadata = new HashMap<>();

        /** path를 설정한다. */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /** size를 설정한다. */
        public Builder size(long size) {
            this.size = size;
            return this;
        }

        /** createdAt을 설정한다. */
        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /** modifiedAt을 설정한다. */
        public Builder modifiedAt(Instant modifiedAt) {
            this.modifiedAt = modifiedAt;
            return this;
        }

        /** directory를 설정한다. */
        public Builder directory(boolean directory) {
            this.directory = directory;
            return this;
        }

        /** mimeType을 설정한다. */
        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        /** 커스텀 메타데이터 항목을 추가한다. */
        public Builder customMetadata(String key, String value) {
            customMetadata.put(key, value);
            return this;
        }

        /** 커스텀 메타데이터를 일괄 추가한다. */
        public Builder customMetadata(Map<String, String> metadata) {
            customMetadata.putAll(metadata);
            return this;
        }

        /** FileMetadata를 생성한다. */
        public FileMetadata build() {
            return new FileMetadata(this);
        }
    }
}
