package at.aimon.core.agent.session.transcript;

import java.util.Objects;

import at.aimon.core.agent.session.store.SegmentId;

/**
 * One line of a session log's manifest: the range {@code [fromSeq, toSeq)} was sealed into the segment
 * {@link #getSegmentId()} (session-log §5.2).
 *
 * <p>
 * The manifest is owned by the record, so this line is what makes that segment exist — a segment the manifest does not
 * name is an orphan. {@link #getContentHash()} is the hash of the segment's payload as it was written; a reader
 * compares it before trusting what it read. {@link #getEntryCount()} is how many entries the range holds, which can be
 * fewer than its seqs when a rewind cut some of them before sealing; {@code /clear} counts removed messages with it.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class SessionLogManifestEntry {

    private final long fromSeq;
    private final long toSeq;
    private final SegmentId segmentId;
    private final String contentHash;
    private final int entryCount;

    private SessionLogManifestEntry(Builder builder) {
        this.fromSeq = builder.fromSeq;
        this.toSeq = builder.toSeq;
        this.segmentId = Objects.requireNonNull(builder.segmentId, "segmentId cannot be null");
        this.contentHash = Objects.requireNonNull(builder.contentHash, "contentHash cannot be null");
        this.entryCount = builder.entryCount;
        if (fromSeq < 0 || toSeq <= fromSeq) {
            throw new IllegalArgumentException("manifest range [" + fromSeq + ", " + toSeq + ") is empty or negative");
        }
        if (entryCount <= 0 || entryCount > toSeq - fromSeq) {
            throw new IllegalArgumentException(
                    "entryCount " + entryCount + " does not fit range [" + fromSeq + ", " + toSeq + ")");
        }
    }

    /**
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the first sealed seq
     */
    public long getFromSeq() {
        return fromSeq;
    }

    /**
     * @return the first seq after the sealed range
     */
    public long getToSeq() {
        return toSeq;
    }

    /**
     * @return the sealed range (never null)
     */
    public SeqRange getRange() {
        return SeqRange.of(fromSeq, toSeq);
    }

    /**
     * @return the segment holding the range (never null)
     */
    public SegmentId getSegmentId() {
        return segmentId;
    }

    /**
     * @return the hash of the segment's payload as written (never null)
     */
    public String getContentHash() {
        return contentHash;
    }

    /**
     * @return how many entries the range holds (positive)
     */
    public int getEntryCount() {
        return entryCount;
    }

    /**
     * Returns whether {@code seq} lies inside this range.
     *
     * @param seq
     *            the seq
     * @return true if {@code fromSeq <= seq < toSeq}
     */
    public boolean contains(long seq) {
        return seq >= fromSeq && seq < toSeq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionLogManifestEntry other)) {
            return false;
        }
        return fromSeq == other.fromSeq && toSeq == other.toSeq && entryCount == other.entryCount
                && segmentId.equals(other.segmentId) && contentHash.equals(other.contentHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromSeq, toSeq, segmentId, contentHash, entryCount);
    }

    @Override
    public String toString() {
        return "SessionLogManifestEntry{[" + fromSeq + ", " + toSeq + ") -> " + segmentId + ", entries=" + entryCount
                + "}";
    }

    /** Builder for {@link SessionLogManifestEntry}. */
    public static final class Builder {

        private long fromSeq;
        private long toSeq;
        private SegmentId segmentId;
        private String contentHash;
        private int entryCount;

        private Builder() {
        }

        /**
         * @param fromSeq
         *            the first sealed seq
         * @return this builder
         */
        public Builder fromSeq(long fromSeq) {
            this.fromSeq = fromSeq;
            return this;
        }

        /**
         * @param toSeq
         *            the first seq after the sealed range
         * @return this builder
         */
        public Builder toSeq(long toSeq) {
            this.toSeq = toSeq;
            return this;
        }

        /**
         * @param segmentId
         *            the segment holding the range (must not be null)
         * @return this builder
         */
        public Builder segmentId(SegmentId segmentId) {
            this.segmentId = segmentId;
            return this;
        }

        /**
         * @param contentHash
         *            the hash of the segment's payload (must not be null)
         * @return this builder
         */
        public Builder contentHash(String contentHash) {
            this.contentHash = contentHash;
            return this;
        }

        /**
         * @param entryCount
         *            how many entries the range holds
         * @return this builder
         */
        public Builder entryCount(int entryCount) {
            this.entryCount = entryCount;
            return this;
        }

        /**
         * @return the entry (never null)
         */
        public SessionLogManifestEntry build() {
            return new SessionLogManifestEntry(this);
        }
    }
}
