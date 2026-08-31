package at.aimon.core.agent.compact;

/**
 * Half-open range {@code [fromIndex, toIndex)} of conversation messages to be summarized by a partial-compaction
 * request (design §4.3).
 *
 * <p>
 * When attached to a {@link CompactionRequest}, the engine summarizes only the in-range messages and preserves the
 * surrounding prefix and tail verbatim, inserting the marker pair (boundary + summary) at the position originally
 * held by {@code fromIndex}. When no range is attached, the engine performs full-conversation compaction (current
 * behavior).
 *
 * <p>
 * Bounds against the actual conversation length are validated by the engine at invocation time. This object only
 * enforces basic structural invariants:
 * <ul>
 * <li>{@code fromIndex >= 0}
 * <li>{@code toIndex > fromIndex} (range must contain at least one message)
 * </ul>
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class CompactionRange {

    private final int fromIndex;
    private final int toIndex;

    private CompactionRange(int fromIndex, int toIndex) {
        if (fromIndex < 0) {
            throw new IllegalArgumentException("fromIndex must be >= 0, got: " + fromIndex);
        }
        if (toIndex <= fromIndex) {
            throw new IllegalArgumentException("toIndex (" + toIndex + ") must be > fromIndex (" + fromIndex + ")");
        }
        this.fromIndex = fromIndex;
        this.toIndex = toIndex;
    }

    /**
     * Creates a half-open range {@code [fromIndex, toIndex)} of messages to summarize.
     *
     * @param fromIndex
     *            inclusive lower bound (must be {@code >= 0})
     * @param toIndex
     *            exclusive upper bound (must be {@code > fromIndex})
     * @return a new range
     * @throws IllegalArgumentException
     *             if the bounds violate the structural invariants above
     */
    public static CompactionRange of(int fromIndex, int toIndex) {
        return new CompactionRange(fromIndex, toIndex);
    }

    /**
     * Convenience factory for prefix compaction: summarize the first {@code length} messages and keep everything from
     * {@code length} onward.
     *
     * @param length
     *            number of leading messages to summarize (must be {@code >= 1})
     * @return a new range {@code [0, length)}
     */
    public static CompactionRange prefix(int length) {
        return new CompactionRange(0, length);
    }

    public int getFromIndex() {
        return fromIndex;
    }

    public int getToIndex() {
        return toIndex;
    }

    /**
     * Returns the number of messages covered by this range ({@code toIndex - fromIndex}).
     */
    public int size() {
        return toIndex - fromIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CompactionRange that)) {
            return false;
        }
        return fromIndex == that.fromIndex && toIndex == that.toIndex;
    }

    @Override
    public int hashCode() {
        return 31 * fromIndex + toIndex;
    }

    @Override
    public String toString() {
        return "CompactionRange[" + fromIndex + ", " + toIndex + ")";
    }
}
