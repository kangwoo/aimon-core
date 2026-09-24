package at.aimon.core.agent.session.transcript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What the LLM view leaves out of the session log, kept beside the log instead of by rewriting it.
 *
 * <p>
 * The view is the log from {@code floorSeq} on, with
 *
 * <ol>
 * <li>every entry in a {@linkplain #getDroppedRanges() dropped range} left out — prompt-too-long recovery;
 * <li>the {@linkplain #getSummarySpan() summary span} replaced by its boundary / summary marker pair — compaction;
 * <li>the tool result bodies of every {@linkplain #getElisions() elided} entry replaced by a placeholder, keeping the
 * tool_use ids and error flags — pruning.
 * </ol>
 *
 * <p>
 * The view state only points at the log. It never holds a message, so the log stays the record of what was said and
 * everything that reads it — memory ingest, a rewind — sees the original. Design:
 * {@code docs/design/session/session-log.md} §4.
 *
 * <p>
 * The operations that change it live on {@link SessionLogState} ({@code summarize}, {@code drop}, {@code elide}),
 * because their invariant — every cut is a legal cut of the log — can only be checked against the log. This type
 * keeps its own shape normalized: dropped ranges are sorted and merged, and nothing inside the summary span is kept as
 * a separate range or elision.
 *
 * <p>
 * Immutable and therefore thread-safe.
 */
public final class SessionViewState {

    private static final SessionViewState EMPTY = new SessionViewState(null, List.of(), new TreeMap<>());

    private final SummarySpan summarySpan;

    /** Sorted, merged, unmodifiable. */
    private final List<SeqRange> droppedRanges;

    /** Unmodifiable, owned by this instance. */
    private final SortedMap<Long, String> elisions;

    private SessionViewState(SummarySpan summarySpan, List<SeqRange> droppedRanges, SortedMap<Long, String> elisions) {
        this.summarySpan = summarySpan;
        this.droppedRanges = Collections.unmodifiableList(normalize(droppedRanges, summarySpan));
        final SortedMap<Long, String> ownedElisions = new TreeMap<>();
        for (Map.Entry<Long, String> elision : elisions.entrySet()) {
            Objects.requireNonNull(elision.getKey(), "elision seq cannot be null");
            Objects.requireNonNull(elision.getValue(), "elision placeholder cannot be null");
            if (summarySpan == null || !summarySpan.getRange().contains(elision.getKey())) {
                ownedElisions.put(elision.getKey(), elision.getValue());
            }
        }
        this.elisions = Collections.unmodifiableSortedMap(ownedElisions);
    }

    /**
     * Returns the view state that leaves nothing out.
     *
     * @return the shared empty state (never null)
     */
    public static SessionViewState empty() {
        return EMPTY;
    }

    /**
     * Creates a view state from its parts. The ranges are sorted and merged, and whatever the span already covers is
     * dropped from the ranges and the elisions.
     *
     * @param summarySpan
     *            the summary span, or null for none
     * @param droppedRanges
     *            the dropped ranges, in any order (must not be null nor contain null)
     * @param elisions
     *            placeholder by seq (must not be null nor contain null keys or values)
     * @return the view state (never null)
     */
    public static SessionViewState of(SummarySpan summarySpan, List<SeqRange> droppedRanges,
            Map<Long, String> elisions) {
        Objects.requireNonNull(droppedRanges, "droppedRanges cannot be null");
        Objects.requireNonNull(elisions, "elisions cannot be null");
        if (summarySpan == null && droppedRanges.isEmpty() && elisions.isEmpty()) {
            return EMPTY;
        }
        return new SessionViewState(summarySpan, droppedRanges, new TreeMap<>(elisions));
    }

    /**
     * @return whether this view state leaves nothing out
     */
    public boolean isEmpty() {
        return summarySpan == null && droppedRanges.isEmpty() && elisions.isEmpty();
    }

    /**
     * @return the summary span, or empty when nothing is summarized (never null)
     */
    public Optional<SummarySpan> getSummarySpan() {
        return Optional.ofNullable(summarySpan);
    }

    /**
     * @return the dropped ranges, sorted and merged (never null)
     */
    public List<SeqRange> getDroppedRanges() {
        return droppedRanges;
    }

    /**
     * @return the placeholder of every elided entry, by seq (never null)
     */
    public SortedMap<Long, String> getElisions() {
        return elisions;
    }

    /**
     * Returns whether the view leaves the entry at {@code seq} out as it is — summarized or dropped. Those are the
     * entries the view never reads.
     *
     * @param seq
     *            the seq to test
     * @return whether the original does not appear in the view
     */
    public boolean hidesOriginal(long seq) {
        return isDropped(seq) || (summarySpan != null && summarySpan.getRange().contains(seq));
    }

    /**
     * @param seq
     *            the seq to test
     * @return whether {@code seq} lies in a dropped range
     */
    public boolean isDropped(long seq) {
        for (SeqRange range : droppedRanges) {
            if (range.contains(seq)) {
                return true;
            }
            if (range.getFromSeq() > seq) {
                return false;
            }
        }
        return false;
    }

    /**
     * Returns whether every seq this view state points at lies within {@code [floorSeq, nextSeq]}.
     *
     * @param floorSeq
     *            the log's floor
     * @param nextSeq
     *            the log's next seq
     * @return whether every seq this state points at lies within the bounds
     */
    boolean liesWithin(long floorSeq, long nextSeq) {
        if (summarySpan != null && (summarySpan.getFromSeq() < floorSeq || summarySpan.getToSeq() > nextSeq)) {
            return false;
        }
        for (SeqRange range : droppedRanges) {
            if (range.getFromSeq() < floorSeq || range.getToSeq() > nextSeq) {
                return false;
            }
        }
        return elisions.isEmpty() || (elisions.firstKey() >= floorSeq && elisions.lastKey() < nextSeq);
    }

    SessionViewState withSummarySpan(SummarySpan span) {
        return new SessionViewState(Objects.requireNonNull(span, "span cannot be null"), droppedRanges, elisions);
    }

    SessionViewState withDroppedRange(SeqRange range) {
        final List<SeqRange> ranges = new ArrayList<>(droppedRanges);
        ranges.add(Objects.requireNonNull(range, "range cannot be null"));
        return new SessionViewState(summarySpan, ranges, elisions);
    }

    SessionViewState withElision(long seq, String placeholder) {
        final SortedMap<Long, String> elided = new TreeMap<>(elisions);
        elided.put(seq, Objects.requireNonNull(placeholder, "placeholder cannot be null"));
        return new SessionViewState(summarySpan, droppedRanges, elided);
    }

    /**
     * Returns this view state with everything at or after {@code seq} forgotten: ranges are cut back to it, a span
     * lying wholly after it is dropped, and a span reaching past it is cut back to it with its summary kept — the
     * rewind rule of session-log §6.1.
     *
     * @param seq
     *            the first seq the log no longer holds
     * @return the view state (never null)
     */
    SessionViewState truncatedFrom(long seq) {
        SummarySpan span = summarySpan;
        if (span != null && span.getFromSeq() >= seq) {
            span = null;
        } else if (span != null && span.getToSeq() > seq) {
            span = span.endingAt(seq);
        }
        final List<SeqRange> ranges = new ArrayList<>(droppedRanges.size());
        for (SeqRange range : droppedRanges) {
            if (range.getToSeq() <= seq) {
                ranges.add(range);
            } else if (range.getFromSeq() < seq) {
                ranges.add(SeqRange.of(range.getFromSeq(), seq));
            }
        }
        final SortedMap<Long, String> kept = new TreeMap<>(elisions.headMap(seq));
        return of(span, ranges, kept);
    }

    private static List<SeqRange> normalize(List<SeqRange> ranges, SummarySpan span) {
        final List<SeqRange> sorted = new ArrayList<>(ranges.size());
        for (SeqRange range : ranges) {
            final SeqRange owned = Objects.requireNonNull(range, "droppedRanges must not contain null elements");
            if (span == null || owned.getFromSeq() < span.getFromSeq() || owned.getToSeq() > span.getToSeq()) {
                sorted.add(owned);
            }
        }
        sorted.sort(Comparator.comparingLong(SeqRange::getFromSeq));
        final List<SeqRange> merged = new ArrayList<>(sorted.size());
        for (SeqRange range : sorted) {
            final int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).getToSeq() >= range.getFromSeq()) {
                final SeqRange previous = merged.get(last);
                merged.set(last, SeqRange.of(previous.getFromSeq(), Math.max(previous.getToSeq(), range.getToSeq())));
            } else {
                merged.add(range);
            }
        }
        return merged;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionViewState other)) {
            return false;
        }
        return Objects.equals(summarySpan, other.summarySpan) && droppedRanges.equals(other.droppedRanges)
                && elisions.equals(other.elisions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(summarySpan, droppedRanges, elisions);
    }

    @Override
    public String toString() {
        return "SessionViewState{span=" + summarySpan + ", dropped=" + droppedRanges + ", elisions=" + elisions.size()
                + "}";
    }
}
