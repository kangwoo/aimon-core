package at.aimon.core.agent.session.store;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.agent.session.SessionId;

/**
 * One sealed range of a session log, as it is stored outside the record: the entries of {@code [fromSeq, toSeq)} that
 * the view no longer shows verbatim (session-log §5).
 *
 * <p>
 * The entries travel as {@link #getPayload() payload}, an opaque string written by {@link SessionLogSegmentCodec} —
 * the same message encoding the record's transcript uses. Backends store it as text and never parse it, for the same
 * reasons the transcript is text (see {@link SessionRecordCodec}). The record's manifest keeps the payload's content
 * hash, so a reader can tell a segment that is not the one the record sealed.
 *
 * <p>
 * A segment exists only while a record's manifest points at it; one that is stored but not pointed at is an orphan,
 * which nobody reads and garbage collection deletes (session-log §5.2, §5.4).
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class SessionLogSegment {

    private final SessionId sessionId;
    private final SegmentId id;
    private final long fromSeq;
    private final long toSeq;
    private final int entryCount;
    private final String payload;
    private final Instant createdAt;

    private SessionLogSegment(Builder builder) {
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId cannot be null");
        this.id = Objects.requireNonNull(builder.id, "id cannot be null");
        this.payload = Objects.requireNonNull(builder.payload, "payload cannot be null");
        this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt cannot be null");
        this.fromSeq = builder.fromSeq;
        this.toSeq = builder.toSeq;
        this.entryCount = builder.entryCount;
        if (fromSeq < 0 || toSeq <= fromSeq) {
            throw new IllegalArgumentException("segment range [" + fromSeq + ", " + toSeq + ") is empty or negative");
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
     * @return the session the segment belongs to (never null)
     */
    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * @return the segment id (never null)
     */
    public SegmentId getId() {
        return id;
    }

    /**
     * @return the first seq of the sealed range
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
     * Returns how many entries the range holds — fewer than {@code toSeq - fromSeq} when a rewind cut seqs out of it
     * before it was sealed.
     *
     * @return the entry count (positive)
     */
    public int getEntryCount() {
        return entryCount;
    }

    /**
     * @return the encoded entries, opaque to backends (never null)
     */
    public String getPayload() {
        return payload;
    }

    /**
     * @return when the segment was written (never null)
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * @return what {@link SessionLogSegmentStore#list} reports about this segment (never null)
     */
    public SegmentInfo info() {
        return SegmentInfo.of(id, createdAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionLogSegment other)) {
            return false;
        }
        return fromSeq == other.fromSeq && toSeq == other.toSeq && entryCount == other.entryCount
                && sessionId.equals(other.sessionId) && id.equals(other.id) && payload.equals(other.payload)
                && createdAt.equals(other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, id, fromSeq, toSeq, entryCount, payload, createdAt);
    }

    @Override
    public String toString() {
        return "SessionLogSegment{sessionId=" + sessionId + ", id=" + id + ", range=[" + fromSeq + ", " + toSeq
                + "), entries=" + entryCount + ", createdAt=" + createdAt + "}";
    }

    /** Builder for {@link SessionLogSegment}. */
    public static final class Builder {

        private SessionId sessionId;
        private SegmentId id;
        private long fromSeq;
        private long toSeq;
        private int entryCount;
        private String payload;
        private Instant createdAt;

        private Builder() {
        }

        /**
         * @param sessionId
         *            the session (must not be null)
         * @return this builder
         */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * @param id
         *            the segment id (must not be null)
         * @return this builder
         */
        public Builder id(SegmentId id) {
            this.id = id;
            return this;
        }

        /**
         * @param fromSeq
         *            the first seq of the range
         * @return this builder
         */
        public Builder fromSeq(long fromSeq) {
            this.fromSeq = fromSeq;
            return this;
        }

        /**
         * @param toSeq
         *            the first seq after the range
         * @return this builder
         */
        public Builder toSeq(long toSeq) {
            this.toSeq = toSeq;
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
         * @param payload
         *            the encoded entries (must not be null)
         * @return this builder
         */
        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        /**
         * @param createdAt
         *            when the segment was written (must not be null)
         * @return this builder
         */
        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /**
         * @return the segment (never null)
         * @throws NullPointerException
         *             if a required value is missing
         * @throws IllegalArgumentException
         *             if the range or count is inconsistent
         */
        public SessionLogSegment build() {
            return new SessionLogSegment(this);
        }
    }
}
