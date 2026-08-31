package at.aimon.core.subagent.task;

import java.util.Objects;

/**
 * An immutable window of a background task's output log, returned by {@link TaskOutputStore#read(String, long, int)}.
 *
 * <p>
 * The slice carries a run of characters starting at the requested {@code fromOffset} together with the cursor the
 * caller
 * should present next ({@link #getNextOffset()}), so incremental "tail the progress log" polling is a simple
 * offset-advancing loop:
 *
 * <pre>
 * {@code
 * long cursor = 0;
 * OutputSlice slice;
 * do {
 *     slice = store.read(taskId, cursor, 8_000);
 *     consume(slice.getText());
 *     cursor = slice.getNextOffset();
 * } while (slice.hasMore());
 * }
 * </pre>
 *
 * <p>
 * Offsets are measured in <b>characters</b> (not bytes) so a delta never splits a multi-byte UTF-8 code point.
 *
 * <p>
 * Immutable value object.
 */
public final class OutputSlice {

    private final String text;
    private final long nextOffset;
    private final boolean truncatedHead;
    private final boolean hasMore;

    private OutputSlice(String text, long nextOffset, boolean truncatedHead, boolean hasMore) {
        this.text = Objects.requireNonNull(text, "text cannot be null");
        this.nextOffset = nextOffset;
        this.truncatedHead = truncatedHead;
        this.hasMore = hasMore;
    }

    /**
     * Creates an output slice.
     *
     * @param text
     *            the characters in this window (must not be null; empty when nothing was available at the offset)
     * @param nextOffset
     *            the character offset the caller should request next to continue reading
     * @param truncatedHead
     *            {@code true} when characters before the requested {@code fromOffset} exist but were not included (i.e.
     *            the caller started mid-stream)
     * @param hasMore
     *            {@code true} when characters beyond {@code nextOffset} are already available
     * @return a new slice (never null)
     */
    public static OutputSlice of(String text, long nextOffset, boolean truncatedHead, boolean hasMore) {
        return new OutputSlice(text, nextOffset, truncatedHead, hasMore);
    }

    /**
     * Returns an empty slice positioned at {@code offset} (no characters available there yet).
     *
     * @param offset
     *            the requested offset, echoed back as {@link #getNextOffset()}
     * @return an empty slice (never null)
     */
    public static OutputSlice empty(long offset) {
        return new OutputSlice("", offset, offset > 0, false);
    }

    /**
     * Gets the characters in this window.
     *
     * @return the text (never null, possibly empty)
     */
    public String getText() {
        return text;
    }

    /**
     * Gets the character offset the caller should request next to continue reading.
     *
     * @return the next offset
     */
    public long getNextOffset() {
        return nextOffset;
    }

    /**
     * Indicates whether characters before the requested {@code fromOffset} exist but were skipped.
     *
     * @return {@code true} when the caller began reading past the start of the log
     */
    public boolean isTruncatedHead() {
        return truncatedHead;
    }

    /**
     * Indicates whether more characters are already available beyond {@link #getNextOffset()}.
     *
     * @return {@code true} when a follow-up read would return additional output
     */
    public boolean hasMore() {
        return hasMore;
    }

    @Override
    public String toString() {
        return "OutputSlice{length=" + text.length() + ", nextOffset=" + nextOffset + ", truncatedHead=" + truncatedHead
                + ", hasMore=" + hasMore + '}';
    }
}
