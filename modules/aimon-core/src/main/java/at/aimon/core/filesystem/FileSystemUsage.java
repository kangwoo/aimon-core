package at.aimon.core.filesystem;

import java.util.Objects;

/**
 * Immutable summary of file system usage statistics. Thread-safe and suitable for concurrent access.
 *
 * <p>
 * Attributes: - totalSize (long): Total file size in bytes (non-negative) - fileCount (long): Total number of files
 * (non-negative) - directoryCount (long): Total number of directories, excluding root (non-negative)
 */
public final class FileSystemUsage {

    private final long totalSize;
    private final long fileCount;
    private final long directoryCount;

    private FileSystemUsage(Builder builder) {
        if (builder.totalSize < 0) {
            throw new IllegalArgumentException("totalSize must be non-negative, got: " + builder.totalSize);
        }
        if (builder.fileCount < 0) {
            throw new IllegalArgumentException("fileCount must be non-negative, got: " + builder.fileCount);
        }
        if (builder.directoryCount < 0) {
            throw new IllegalArgumentException("directoryCount must be non-negative, got: " + builder.directoryCount);
        }
        totalSize = builder.totalSize;
        fileCount = builder.fileCount;
        directoryCount = builder.directoryCount;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public long getFileCount() {
        return fileCount;
    }

    public long getDirectoryCount() {
        return directoryCount;
    }

    /** FileSystemUsage Builder를 반환한다. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FileSystemUsage that = (FileSystemUsage) o;
        return totalSize == that.totalSize && fileCount == that.fileCount && directoryCount == that.directoryCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(totalSize, fileCount, directoryCount);
    }

    @Override
    public String toString() {
        return "FileSystemUsage{" + "totalSize=" + totalSize + ", fileCount=" + fileCount + ", directoryCount="
                + directoryCount + '}';
    }

    /** Builder for constructing FileSystemUsage instances. */
    public static final class Builder {

        private long totalSize;
        private long fileCount;
        private long directoryCount;

        /** totalSize를 설정한다. */
        public Builder totalSize(long totalSize) {
            this.totalSize = totalSize;
            return this;
        }

        /** fileCount를 설정한다. */
        public Builder fileCount(long fileCount) {
            this.fileCount = fileCount;
            return this;
        }

        /** directoryCount를 설정한다. */
        public Builder directoryCount(long directoryCount) {
            this.directoryCount = directoryCount;
            return this;
        }

        /** FileSystemUsage를 생성한다. */
        public FileSystemUsage build() {
            return new FileSystemUsage(this);
        }
    }
}
