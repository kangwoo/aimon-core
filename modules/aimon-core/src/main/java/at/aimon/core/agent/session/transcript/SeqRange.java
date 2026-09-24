package at.aimon.core.agent.session.transcript;

/**
 * A half-open range {@code [fromSeq, toSeq)} of session log seqs.
 *
 * <p>
 * A range names seqs, not entries: seqs a rewind cut away inside it are simply not there, and nothing needs to be
 * re-mapped when that happens. It is never empty.
 *
 * <p>
 * Immutable and therefore thread-safe.
 */
public final class SeqRange {

    private final long fromSeq;
    private final long toSeq;

    private SeqRange(long fromSeq, long toSeq) {
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
    }

    /**
     * Creates a range.
     *
     * @param fromSeq
     *            the first seq in the range (must not be negative)
     * @param toSeq
     *            the first seq after the range (must be greater than {@code fromSeq})
     * @return the range (never null)
     * @throws IllegalArgumentException
     *             if {@code fromSeq} is negative or the range would be empty
     */
    public static SeqRange of(long fromSeq, long toSeq) {
        if (fromSeq < 0) {
            throw new IllegalArgumentException("fromSeq cannot be negative, got: " + fromSeq);
        }
        if (toSeq <= fromSeq) {
            throw new IllegalArgumentException("toSeq (" + toSeq + ") must be greater than fromSeq (" + fromSeq + ")");
        }
        return new SeqRange(fromSeq, toSeq);
    }

    /**
     * @return the first seq in the range
     */
    public long getFromSeq() {
        return fromSeq;
    }

    /**
     * @return the first seq after the range
     */
    public long getToSeq() {
        return toSeq;
    }

    /**
     * @param seq
     *            the seq to test
     * @return whether {@code seq} lies in {@code [fromSeq, toSeq)}
     */
    public boolean contains(long seq) {
        return seq >= fromSeq && seq < toSeq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SeqRange other)) {
            return false;
        }
        return fromSeq == other.fromSeq && toSeq == other.toSeq;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(fromSeq) * 31 + Long.hashCode(toSeq);
    }

    @Override
    public String toString() {
        return "[" + fromSeq + ", " + toSeq + ")";
    }
}
