package at.aimon.core.environment;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.filesystem.FileMetadata;

/**
 * What a file looked like when the model last read it: size, modification time and, when the backend has one, an
 * etag (design §7). {@code Edit}, and {@code Write} over an existing file, refuse to modify a file whose current stamp
 * differs from the recorded one — whatever changed it (another execution, the shell, a person).
 *
 * <p>
 * Two stamps are equal when both carry an etag and the etags are equal; otherwise when size and modification time are
 * equal.
 */
public final class FileStamp {

    private final long size;
    private final Instant modifiedAt;
    private final String etag;

    private FileStamp(long size, Instant modifiedAt, String etag) {
        this.size = size;
        this.modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt must not be null");
        this.etag = etag;
    }

    /**
     * Stamps a file from its metadata.
     *
     * @param metadata
     *            the metadata (must not be null)
     * @return the stamp
     */
    public static FileStamp of(FileMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        return new FileStamp(metadata.getSize(), metadata.getModifiedAt(), metadata.getEtag().orElse(null));
    }

    /** @return the size in bytes */
    public long getSize() {
        return size;
    }

    /** @return the modification time */
    public Instant getModifiedAt() {
        return modifiedAt;
    }

    /** @return the etag, if the backend supplies one */
    public Optional<String> getEtag() {
        return Optional.ofNullable(etag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FileStamp that)) {
            return false;
        }
        if (etag != null && that.etag != null) {
            return etag.equals(that.etag);
        }
        return size == that.size && modifiedAt.equals(that.modifiedAt);
    }

    @Override
    public int hashCode() {
        // Consistent with equals: two stamps may be equal by etag while differing in size/mtime (and vice versa when
        // only one side has an etag), so no field can take part in the hash.
        return 0;
    }

    @Override
    public String toString() {
        return "FileStamp{size=" + size + ", modifiedAt=" + modifiedAt + ", etag=" + etag + '}';
    }
}
