package at.aimon.core.agent.session.transcript;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One page of a session log read through {@link SessionLogReader}: entries in seq order, sealed ones included, and
 * where the next page starts.
 *
 * <p>
 * <b>Gaps.</b> A sealed range whose segment is missing or does not match its manifest line is not a failure. The page
 * carries, in its place, one {@link LogOrigin#SYNTHETIC} user entry reading {@code [history unavailable: seq a..b]} —
 * at the range's first seq — and lists the range in {@link #getGaps()}. A reader that takes only
 * {@link LogOrigin#CONVERSATION} entries, as memory ingest does, therefore skips a gap without special handling
 * (session-log §5.5).
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class SessionLogPage {

    private static final SessionLogPage EMPTY = new SessionLogPage(List.of(), List.of(), OptionalLong.empty());

    private final List<SessionLogEntry> entries;
    private final List<SeqRange> gaps;
    private final OptionalLong nextFromSeq;

    private SessionLogPage(List<SessionLogEntry> entries, List<SeqRange> gaps, OptionalLong nextFromSeq) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries cannot be null"));
        this.gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps cannot be null"));
        this.nextFromSeq = Objects.requireNonNull(nextFromSeq, "nextFromSeq cannot be null");
    }

    /**
     * @param entries
     *            the entries in seq order, gap placeholders included (must not be null)
     * @param gaps
     *            the sealed ranges that could not be read (must not be null)
     * @param nextFromSeq
     *            where the next page starts, or empty when this is the last one (must not be null)
     * @return the page (never null)
     */
    public static SessionLogPage of(List<SessionLogEntry> entries, List<SeqRange> gaps, OptionalLong nextFromSeq) {
        return new SessionLogPage(entries, gaps, nextFromSeq);
    }

    /**
     * @return the empty last page (never null)
     */
    public static SessionLogPage empty() {
        return EMPTY;
    }

    /**
     * Returns the placeholder text a gap is reported with.
     *
     * @param gap
     *            the unreadable range (must not be null)
     * @return {@code [history unavailable: seq a..b]}, with {@code b} the last seq of the range (never null)
     */
    public static String gapText(SeqRange gap) {
        Objects.requireNonNull(gap, "gap cannot be null");
        return "[history unavailable: seq " + gap.getFromSeq() + ".." + (gap.getToSeq() - 1) + "]";
    }

    /**
     * @return the entries of this page in seq order, gap placeholders included (never null)
     */
    public List<SessionLogEntry> getEntries() {
        return entries;
    }

    /**
     * @return the sealed ranges of this page that could not be read (never null)
     */
    public List<SeqRange> getGaps() {
        return gaps;
    }

    /**
     * @return where the next page starts, or empty when this is the last page (never null)
     */
    public OptionalLong getNextFromSeq() {
        return nextFromSeq;
    }

    /**
     * @return whether another page follows
     */
    public boolean hasMore() {
        return nextFromSeq.isPresent();
    }

    @Override
    public String toString() {
        return "SessionLogPage{entries=" + entries.size() + ", gaps=" + gaps + ", next="
                + (nextFromSeq.isPresent() ? nextFromSeq.getAsLong() : "none") + "}";
    }
}
