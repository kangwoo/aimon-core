package at.aimon.core.agent.session.transcript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongPredicate;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;

/**
 * Everything a session record keeps about its transcript apart from the system prompt, as one immutable value.
 *
 * <p>
 * <b>Why one value.</b> A transcript crosses several types on its way between a backend and a
 * {@link TranscriptBuffer} — the codec, {@code StoredSessionRecord}, the record view, {@link SessionSnapshot},
 * {@code SessionRecord}, {@link SessionTranscript} — and each of those used to copy it field by field. A field added
 * to that set and forgotten at one hop disappears without a word. Bundled here, every hop hands the whole value on and
 * nothing is picked apart.
 *
 * <p>
 * <b>The log.</b> Entries are addressed by seq, not by position. {@code nextSeq} is the seq the next append receives,
 * and seqs are never reused: a rewind ({@link #truncateFrom(long)}) and {@code /clear} ({@link #clear()}) both leave
 * {@code nextSeq} where it was, so whatever pointed at a dropped seq can never come to mean a different message.
 * {@code floorSeq} is where the log restarts after {@code /clear}; nothing below it is part of the session any more.
 *
 * <p>
 * The entries held here are the ones the record itself carries — the <em>hot</em> entries. {@link #getMessages()} is
 * therefore "the part of the log that is in the record", not the whole log: a range the view no longer shows verbatim
 * can be sealed out of the record into a segment ({@link #seal(SessionLogManifestEntry)}), and the {@linkplain
 * #getManifest() manifest} is then what remembers it (session-log §5). Reading the whole log, sealed ranges included,
 * is {@link SessionLogReader}'s job.
 *
 * <p>
 * <b>Invariants</b>, checked on construction:
 *
 * <ul>
 * <li>{@code 0 <= floorSeq <= nextSeq}
 * <li>entry seqs are strictly increasing and every one lies in {@code [floorSeq, nextSeq)}
 * <li>a rewind point's seq lies in {@code [floorSeq, nextSeq]}
 * <li>manifest ranges are sorted, disjoint and lie in {@code [floorSeq, nextSeq)}; no carried entry lies in one; each
 * is hidden by the view state; no rewind point lies strictly inside one; a non-empty manifest requires version 2
 * </ul>
 *
 * <p>
 * <b>Format.</b> {@link #getFormat()} is the format this state must at least be written in. A state read from a
 * version-2 document carries {@link SessionLogFormat#V2}, so writing it back as version 1 is not possible — see
 * {@link SessionLogFormat} for why the upgrade is sticky.
 *
 * <p>
 * <b>View state.</b> {@link #getViewState()} says what the LLM view leaves out of the log — a summarized span, dropped
 * ranges, elided tool results. It is changed by {@link #summarize(SummarySpan)}, {@link #drop(long, long)} and
 * {@link #elide(long, String)}, never by rewriting entries; each checks that its cuts are
 * {@linkplain #isLegalCut(long) legal}. A view state can only be written in version 2, so a non-empty one requires
 * {@link SessionLogFormat#V2}, and the three operations refuse a version-1 state: a version-1 write mode compacts by
 * rewriting the log instead. The two paths that cut the log — {@link #truncateFrom(long)} and {@link #clear()} — clean
 * up whatever pointed at the cut seqs (session-log §6).
 *
 * <p>
 * Immutable and therefore thread-safe. Every operation returns a new instance.
 */
public final class SessionLogState {

    private static final SessionLogState EMPTY = builder().build();

    private final long nextSeq;
    private final long floorSeq;

    /** Unmodifiable, owned by this instance. */
    private final List<SessionLogEntry> entries;

    /** Unmodifiable, owned by this instance, index-aligned with {@link #entries}. */
    private final List<Message> messages;

    private final SessionRewindPoint rewindPoint;
    private final SessionLogFormat format;
    private final SessionViewState viewState;

    /** Unmodifiable, owned by this instance, sorted by {@code fromSeq}. */
    private final List<SessionLogManifestEntry> manifest;

    private SessionLogState(Builder builder) {
        this.nextSeq = builder.nextSeq;
        this.floorSeq = builder.floorSeq;
        this.rewindPoint = builder.rewindPoint;
        this.format = Objects.requireNonNull(builder.format, "format cannot be null");
        this.viewState = Objects.requireNonNull(builder.viewState, "viewState cannot be null");
        if (floorSeq < 0 || floorSeq > nextSeq) {
            throw new IllegalArgumentException(
                    "floorSeq must lie in [0, nextSeq], got floorSeq=" + floorSeq + ", nextSeq=" + nextSeq);
        }
        final List<SessionLogEntry> ownedEntries = new ArrayList<>(builder.entries.size());
        // ArrayList rather than List.copyOf: the message view must tolerate contains(null), see SessionTranscript.
        final List<Message> ownedMessages = new ArrayList<>(builder.entries.size());
        long previous = -1;
        for (SessionLogEntry entry : builder.entries) {
            Objects.requireNonNull(entry, "entries must not contain null elements");
            if (entry.getSeq() <= previous) {
                throw new IllegalArgumentException(
                        "entry seqs must strictly increase, got " + entry.getSeq() + " after " + previous);
            }
            if (entry.getSeq() < floorSeq || entry.getSeq() >= nextSeq) {
                throw new IllegalArgumentException("entry seq " + entry.getSeq() + " lies outside [floorSeq=" + floorSeq
                        + ", nextSeq=" + nextSeq + ")");
            }
            previous = entry.getSeq();
            ownedEntries.add(entry);
            ownedMessages.add(entry.getMessage());
        }
        if (rewindPoint != null && (rewindPoint.getSeq() < floorSeq || rewindPoint.getSeq() > nextSeq)) {
            throw new IllegalArgumentException("rewindPoint seq " + rewindPoint.getSeq() + " lies outside [floorSeq="
                    + floorSeq + ", nextSeq=" + nextSeq + "]");
        }
        if (!viewState.isEmpty() && format != SessionLogFormat.V2) {
            throw new IllegalArgumentException("a view state can only be carried by a version-2 log, got " + format);
        }
        if (!viewState.liesWithin(floorSeq, nextSeq)) {
            throw new IllegalArgumentException(
                    "view state " + viewState + " points outside [floorSeq=" + floorSeq + ", nextSeq=" + nextSeq + "]");
        }
        this.entries = Collections.unmodifiableList(ownedEntries);
        this.messages = Collections.unmodifiableList(ownedMessages);
        this.manifest = List.copyOf(Objects.requireNonNull(builder.manifest, "manifest cannot be null"));
        checkManifest();
    }

    private void checkManifest() {
        if (manifest.isEmpty()) {
            return;
        }
        if (format != SessionLogFormat.V2) {
            throw new IllegalArgumentException("a manifest can only be carried by a version-2 log, got " + format);
        }
        long previousTo = floorSeq;
        for (SessionLogManifestEntry line : manifest) {
            if (line.getFromSeq() < previousTo || line.getToSeq() > nextSeq) {
                throw new IllegalArgumentException("manifest range " + line.getRange() + " overlaps another or lies"
                        + " outside [floorSeq=" + floorSeq + ", nextSeq=" + nextSeq + ")");
            }
            if (!viewState.hidesRange(line.getFromSeq(), line.getToSeq())) {
                throw new IllegalArgumentException(
                        "manifest range " + line.getRange() + " is not hidden by the view state " + viewState);
            }
            if (rewindPoint != null && rewindPoint.getSeq() > line.getFromSeq()
                    && rewindPoint.getSeq() < line.getToSeq()) {
                throw new IllegalArgumentException(
                        "rewindPoint seq " + rewindPoint.getSeq() + " lies inside the sealed range " + line.getRange());
            }
            previousTo = line.getToSeq();
        }
        for (SessionLogEntry entry : entries) {
            if (sealedLineOf(entry.getSeq()) != null) {
                throw new IllegalArgumentException(
                        "entry seq " + entry.getSeq() + " lies in a sealed range; a sealed entry is not carried");
            }
        }
    }

    private SessionLogManifestEntry sealedLineOf(long seq) {
        for (SessionLogManifestEntry line : manifest) {
            if (line.contains(seq)) {
                return line;
            }
            if (line.getFromSeq() > seq) {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the empty version-1 state: no entries, both seqs at 0, no rewind point.
     *
     * @return the shared empty state (never null)
     */
    public static SessionLogState empty() {
        return EMPTY;
    }

    /**
     * Builds the state a version-1 message list migrates to: seqs {@code 0..n-1}, every entry
     * {@link LogOrigin#CONVERSATION}, format {@link SessionLogFormat#V1}.
     *
     * @param messages
     *            the message history (must not be null, may contain null elements)
     * @return the state (never null)
     * @throws NullPointerException
     *             if {@code messages} is null
     */
    public static SessionLogState ofMessages(List<Message> messages) {
        return ofMessages(messages, null);
    }

    /**
     * Builds the state a version-1 message list and its rewind point migrate to.
     *
     * <p>
     * Because the migrated seqs are the list positions, a version-1 rewind point's message count is already the seq it
     * needs to be.
     *
     * @param messages
     *            the message history (must not be null, may contain null elements)
     * @param rewindPoint
     *            where the last turn began if it was interrupted (may be null)
     * @return the state (never null)
     * @throws NullPointerException
     *             if {@code messages} is null
     * @throws IllegalArgumentException
     *             if {@code rewindPoint} points past the end of {@code messages}
     */
    public static SessionLogState ofMessages(List<Message> messages, SessionRewindPoint rewindPoint) {
        Objects.requireNonNull(messages, "messages cannot be null");
        if (messages.isEmpty() && rewindPoint == null) {
            return EMPTY;
        }
        final List<SessionLogEntry> migrated = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            migrated.add(SessionLogEntry.of(i, messages.get(i), LogOrigin.CONVERSATION));
        }
        return builder().entries(migrated).nextSeq(messages.size()).rewindPoint(rewindPoint).build();
    }

    /**
     * Starts building a state. Defaults: no entries, both seqs 0, no rewind point, {@link SessionLogFormat#V1}, an
     * empty view state.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder pre-filled with this state's values.
     *
     * @return a new builder (never null)
     */
    public Builder toBuilder() {
        return new Builder().entries(entries).nextSeq(nextSeq).floorSeq(floorSeq).rewindPoint(rewindPoint)
                .format(format).viewState(viewState).manifest(manifest);
    }

    /**
     * @return the seq the next appended entry receives (never negative)
     */
    public long getNextSeq() {
        return nextSeq;
    }

    /**
     * @return where the log restarts after {@code /clear}; no live entry lies below it (never negative)
     */
    public long getFloorSeq() {
        return floorSeq;
    }

    /**
     * Returns the entries this state carries, in seq order.
     *
     * @return an unmodifiable list (never null, may be empty)
     */
    public List<SessionLogEntry> getEntries() {
        return entries;
    }

    /**
     * Returns the messages of {@link #getEntries()}, in the same order.
     *
     * @return an unmodifiable list (never null, may be empty, may contain null elements)
     */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * Returns where the most recent turn began, when that turn was interrupted.
     *
     * @return the rewind point, or empty (never null)
     */
    public Optional<SessionRewindPoint> getRewindPoint() {
        return Optional.ofNullable(rewindPoint);
    }

    /**
     * Returns the format this state must at least be written in.
     *
     * @return the format (never null)
     */
    public SessionLogFormat getFormat() {
        return format;
    }

    /**
     * Returns what the LLM view leaves out of this log.
     *
     * @return the view state (never null; empty for a version-1 state)
     */
    public SessionViewState getViewState() {
        return viewState;
    }

    /**
     * Returns the sealed ranges of this log: each line names the segment its range was moved into.
     *
     * @return an unmodifiable list sorted by {@code fromSeq} (never null; empty for a version-1 state)
     */
    public List<SessionLogManifestEntry> getManifest() {
        return manifest;
    }

    /**
     * Returns the carried entries whose seq lies in {@code [fromSeq, toSeq)}, in seq order.
     *
     * @param fromSeq
     *            the first seq
     * @param toSeq
     *            the first seq after the range
     * @return an unmodifiable list (never null, may be empty)
     */
    public List<SessionLogEntry> entriesIn(long fromSeq, long toSeq) {
        final int from = countBefore(fromSeq);
        final int to = Math.max(from, countBefore(toSeq));
        return entries.subList(from, to);
    }

    /**
     * Returns this state with the range of {@code line} sealed: its entries leave the record and the manifest names
     * the segment that now holds them (session-log §5). The view does not change — a sealable range is one the view
     * already leaves out.
     *
     * @param line
     *            the manifest line for the segment just written (must not be null)
     * @return the state (never null)
     * @throws IllegalStateException
     *             if this is a version-1 state
     * @throws IllegalArgumentException
     *             if the range lies outside {@code [floorSeq, nextSeq)}, overlaps a sealed range, is not hidden by the
     *             view state, contains a rewind point, has a cut that is not legal, or holds a different number of
     *             entries than the line says
     */
    public SessionLogState seal(SessionLogManifestEntry line) {
        Objects.requireNonNull(line, "line cannot be null");
        if (format != SessionLogFormat.V2) {
            throw new IllegalStateException("only a version-2 log can be sealed");
        }
        final long from = line.getFromSeq();
        final long to = line.getToSeq();
        if (!isLegalCut(from) || !isLegalCut(to)) {
            throw new IllegalArgumentException(
                    "sealed range " + line.getRange() + " would split a tool_use from its tool_result");
        }
        final List<SessionLogEntry> sealed = entriesIn(from, to);
        if (sealed.size() != line.getEntryCount()) {
            throw new IllegalArgumentException("sealed range " + line.getRange() + " holds " + sealed.size()
                    + " carried entries, the manifest line says " + line.getEntryCount());
        }
        final List<SessionLogEntry> kept = new ArrayList<>(entries.size() - sealed.size());
        kept.addAll(entries.subList(0, countBefore(from)));
        kept.addAll(entries.subList(countBefore(to), entries.size()));
        final List<SessionLogManifestEntry> lines = new ArrayList<>(manifest.size() + 1);
        lines.addAll(manifest);
        lines.add(line);
        lines.sort((a, b) -> Long.compare(a.getFromSeq(), b.getFromSeq()));
        // The constructor checks the rest: bounds, overlap, hidden by the view, no rewind point inside.
        return toBuilder().entries(kept).manifest(lines).build();
    }

    /**
     * Returns the messages of the {@link LogOrigin#CONVERSATION} entries, in seq order — the log as a record of what
     * was said, without what the runtime injected. This is what memory ingest reads.
     *
     * @return an unmodifiable list (never null, may be empty)
     */
    public List<Message> getConversationMessages() {
        final List<Message> conversation = new ArrayList<>(entries.size());
        for (SessionLogEntry entry : entries) {
            if (entry.getOrigin() == LogOrigin.CONVERSATION) {
                conversation.add(entry.getMessage());
            }
        }
        return Collections.unmodifiableList(conversation);
    }

    /**
     * Returns whether a cut at {@code seq} — between the entries below it and those at or above it — splits no
     * {@code tool_use} from its {@code tool_result}. See {@link LegalCuts} for the rule; it is checked against the
     * entries this state carries.
     *
     * @param seq
     *            the cut
     * @return whether the cut is legal; false when {@code seq} lies outside {@code [floorSeq, nextSeq]}
     */
    public boolean isLegalCut(long seq) {
        if (seq < floorSeq || seq > nextSeq) {
            return false;
        }
        final SessionLogManifestEntry sealed = sealedLineOf(seq);
        if (sealed != null && seq != sealed.getFromSeq()) {
            // Inside a sealed range there is nothing carried to check against, and nothing should cut there.
            return false;
        }
        return LegalCuts.isLegal(messages, countBefore(seq));
    }

    /**
     * Returns {@link #isLegalCut(long)} for this state as a predicate whose answers are computed once, for callers that
     * test many cuts along the log. {@code isLegalCut} rescans the carried entries per call, which makes a scan over
     * every position quadratic; this is one pass up front and a binary search per question.
     *
     * @return the predicate (never null); it answers for this state only, which is immutable
     */
    public LongPredicate legalCuts() {
        final boolean[] legal = LegalCuts.legalPositions(messages);
        return seq -> {
            if (seq < floorSeq || seq > nextSeq) {
                return false;
            }
            final SessionLogManifestEntry sealed = sealedLineOf(seq);
            if (sealed != null && seq != sealed.getFromSeq()) {
                return false;
            }
            return legal[countBefore(seq)];
        };
    }

    /**
     * Returns this state with {@code span} as its summary span: the view shows the span's marker pair instead of the
     * entries in its range. The log is not touched.
     *
     * <p>
     * There is one span. A new one must cover the one held — a span only ever widens — and absorbs it; dropped ranges
     * and elisions inside it are forgotten, since the span hides them anyway.
     *
     * @param span
     *            the span (must not be null)
     * @return the state (never null)
     * @throws IllegalStateException
     *             if this is a version-1 state
     * @throws IllegalArgumentException
     *             if the span lies outside {@code [floorSeq, nextSeq]}, does not cover the span held, or either of its
     *             cuts is not legal
     */
    public SessionLogState summarize(SummarySpan span) {
        Objects.requireNonNull(span, "span cannot be null");
        requireViewCapable();
        requireLegalRange(span.getFromSeq(), span.getToSeq());
        final Optional<SummarySpan> held = viewState.getSummarySpan();
        if (held.isPresent()
                && (span.getFromSeq() > held.get().getFromSeq() || span.getToSeq() < held.get().getToSeq())) {
            throw new IllegalArgumentException(
                    "a summary span only widens: " + span.getRange() + " does not cover " + held.get().getRange());
        }
        return toBuilder().viewState(viewState.withSummarySpan(span)).build();
    }

    /**
     * Returns this state with {@code [fromSeq, toSeq)} left out of the view — without a summary. Prompt-too-long
     * recovery uses it. The log is not touched.
     *
     * @param fromSeq
     *            the first seq to drop
     * @param toSeq
     *            the first seq after the dropped range
     * @return the state (never null)
     * @throws IllegalStateException
     *             if this is a version-1 state
     * @throws IllegalArgumentException
     *             if the range is empty, lies outside {@code [floorSeq, nextSeq]}, or either of its cuts is not legal
     */
    public SessionLogState drop(long fromSeq, long toSeq) {
        requireViewCapable();
        requireLegalRange(fromSeq, toSeq);
        return toBuilder().viewState(viewState.withDroppedRange(SeqRange.of(fromSeq, toSeq))).build();
    }

    /**
     * Returns this state with the tool result bodies of the entry at {@code seq} shown as {@code placeholder}. The
     * tool_use ids and error flags stay, so no pair is broken — which is why no cut needs checking. The log is not
     * touched.
     *
     * @param seq
     *            the seq of a {@link Role#TOOL} entry this state carries
     * @param placeholder
     *            the text shown instead (must not be null)
     * @return the state (never null)
     * @throws IllegalStateException
     *             if this is a version-1 state
     * @throws IllegalArgumentException
     *             if no carried entry has that seq or it is not a tool result
     */
    public SessionLogState elide(long seq, String placeholder) {
        Objects.requireNonNull(placeholder, "placeholder cannot be null");
        requireViewCapable();
        final int index = countBefore(seq);
        if (index >= entries.size() || entries.get(index).getSeq() != seq) {
            throw new IllegalArgumentException("no entry with seq " + seq + " in this log");
        }
        final Message message = entries.get(index).getMessage();
        if (message == null || message.getRole() != Role.TOOL) {
            throw new IllegalArgumentException("only a tool result can be elided, seq " + seq + " is not one");
        }
        return toBuilder().viewState(viewState.withElision(seq, placeholder)).build();
    }

    private void requireViewCapable() {
        if (format != SessionLogFormat.V2) {
            throw new IllegalStateException(
                    "a view state can only be kept by a version-2 log; a version-1 log is compacted in place");
        }
    }

    private void requireLegalRange(long fromSeq, long toSeq) {
        if (fromSeq < floorSeq || toSeq > nextSeq || toSeq <= fromSeq) {
            throw new IllegalArgumentException("range [" + fromSeq + ", " + toSeq
                    + ") is empty or lies outside [floorSeq=" + floorSeq + ", nextSeq=" + nextSeq + "]");
        }
        if (!isLegalCut(fromSeq) || !isLegalCut(toSeq)) {
            throw new IllegalArgumentException("range [" + fromSeq + ", " + toSeq
                    + ") would split a tool_use from its tool_result; both ends must be legal cuts");
        }
    }

    /**
     * Returns whether the session has any conversation to speak of: a live {@link LogOrigin#CONVERSATION} entry
     * whose message is a user message, or a sealed range.
     *
     * <p>
     * Synthetic user messages do not count — a session whose only user-role entries are runtime injections has not
     * been spoken to yet. Nor does the count of seqs handed out: a new session whose first turn was interrupted and
     * rewound has used seqs but holds no conversation, and must be treated as new.
     *
     * <p>
     * A sealed range counts without being read: ranges are sealed only once a compaction summarized or dropped them,
     * which happens to conversations, and the execution loop never reads sealed entries (session-log §3.4).
     *
     * @return true if a live conversation user message exists
     */
    public boolean hasConversation() {
        if (!manifest.isEmpty()) {
            return true;
        }
        for (SessionLogEntry entry : entries) {
            if (entry.getOrigin() == LogOrigin.CONVERSATION && entry.getMessage() != null
                    && entry.getMessage().getRole() == Role.USER) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns how many entries of the log are still alive — neither cut by a rewind nor cleared by {@code /clear}.
     *
     * <p>
     * Not {@code nextSeq - floorSeq}: that also counts the seqs a rewind cut away. Sealed entries count — they are
     * out of the record, not out of the log (session-log §6.2).
     *
     * @return the live entry count (never negative)
     */
    public int liveEntryCount() {
        int count = entries.size();
        for (SessionLogManifestEntry line : manifest) {
            count += line.getEntryCount();
        }
        return count;
    }

    /**
     * Returns how many carried entries have a seq below {@code seq} — the position {@code seq} maps to in
     * {@link #getMessages()}.
     *
     * @param seq
     *            the seq to position
     * @return the number of entries before it (never negative)
     */
    public int countBefore(long seq) {
        // Entries are in ascending seq order, so this is a lower-bound binary search.
        int low = 0;
        int high = entries.size();
        while (low < high) {
            final int mid = (low + high) >>> 1;
            if (entries.get(mid).getSeq() < seq) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Returns this state with one entry appended at {@code nextSeq}.
     *
     * <p>
     * Runs in O(n): the entry list is copied. Like {@link SessionTranscript#append(Message)}, this sits on the copy
     * paths; the append-heavy hot path is {@link TranscriptBuffer}.
     *
     * @param message
     *            the message to append (must not be null)
     * @param origin
     *            where the message came from (must not be null)
     * @return the longer state (never null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public SessionLogState append(Message message, LogOrigin origin) {
        Objects.requireNonNull(message, "message cannot be null");
        final List<SessionLogEntry> appended = new ArrayList<>(entries.size() + 1);
        appended.addAll(entries);
        appended.add(SessionLogEntry.of(nextSeq, message, origin));
        return toBuilder().entries(appended).nextSeq(nextSeq + 1).build();
    }

    /**
     * Returns this state cut back to before {@code seq}: every entry at or after it is dropped.
     *
     * <p>
     * {@code nextSeq} does not move — seqs are not reused. A rewind point at or after the cut described a turn that
     * no longer exists and is dropped with it; one before the cut survives.
     *
     * <p>
     * The view state forgets what pointed at the cut seqs: dropped ranges and elisions are cut back, a summary span
     * lying wholly after the cut is dropped, and one reaching past it ends at the cut with its summary kept. That
     * summary may describe part of what was cut — a known inaccuracy (session-log §6.1, §11).
     *
     * <p>
     * Manifest lines whose range starts at or after the cut are dropped whole; their segments become orphans for
     * garbage collection. Sealing never lets a range straddle the rewind point, so no segment has to be read or split.
     *
     * @param seq
     *            the first seq to drop
     * @return the shorter state, or {@code this} when there was nothing to drop (never null)
     * @throws IllegalArgumentException
     *             if {@code seq} lies strictly inside a sealed range
     */
    public SessionLogState truncateFrom(long seq) {
        final int keep = countBefore(seq);
        final boolean dropPoint = rewindPoint != null && rewindPoint.getSeq() >= seq;
        final SessionViewState cutView = viewState.truncatedFrom(seq);
        final List<SessionLogManifestEntry> keptLines = new ArrayList<>(manifest.size());
        for (SessionLogManifestEntry line : manifest) {
            if (line.getFromSeq() < seq && line.getToSeq() > seq) {
                throw new IllegalArgumentException("cannot cut the log at seq " + seq + ": it lies inside the sealed"
                        + " range " + line.getRange());
            }
            if (line.getFromSeq() < seq) {
                keptLines.add(line);
            }
        }
        if (keep == entries.size() && !dropPoint && cutView.equals(viewState) && keptLines.size() == manifest.size()) {
            return this;
        }
        return toBuilder().entries(entries.subList(0, keep)).rewindPoint(dropPoint ? null : rewindPoint)
                .viewState(cutView).manifest(keptLines).build();
    }

    /**
     * Returns this state with the interrupted turn taken back out: {@link #truncateFrom(long)} at the rewind point,
     * which drops the point along with the turn.
     *
     * @return the rewound state, or {@code this} when there is no rewind point (never null)
     */
    public SessionLogState rewind() {
        return rewindPoint == null ? this : truncateFrom(rewindPoint.getSeq());
    }

    /**
     * Returns this state emptied the way {@code /clear} empties it: no entries, no manifest, no rewind point, an empty
     * view state, and {@code floorSeq} raised to {@code nextSeq} so the log continues from where it was rather than
     * from 0. The cleared manifest's segments are deleted by whoever saves the result (session-log §6.2).
     *
     * @return the cleared state (never null)
     */
    public SessionLogState clear() {
        return toBuilder().entries(List.of()).floorSeq(nextSeq).rewindPoint(null).viewState(SessionViewState.empty())
                .manifest(List.of()).build();
    }

    /**
     * Returns this state carrying {@code newRewindPoint}.
     *
     * @param newRewindPoint
     *            the point to carry, or null to drop the one held
     * @return the state (never null)
     * @throws IllegalArgumentException
     *             if the point's seq lies outside {@code [floorSeq, nextSeq]}
     */
    public SessionLogState withRewindPoint(SessionRewindPoint newRewindPoint) {
        if (Objects.equals(rewindPoint, newRewindPoint)) {
            return this;
        }
        return toBuilder().rewindPoint(newRewindPoint).build();
    }

    /**
     * Returns this state required to be written in at least {@code minimum}. Never lowers the format.
     *
     * @param minimum
     *            the format to require (must not be null)
     * @return the state (never null)
     */
    public SessionLogState withFormatAtLeast(SessionLogFormat minimum) {
        final SessionLogFormat raised = format.atLeast(Objects.requireNonNull(minimum, "minimum cannot be null"));
        return raised == format ? this : toBuilder().format(raised).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionLogState other)) {
            return false;
        }
        return nextSeq == other.nextSeq && floorSeq == other.floorSeq && format == other.format
                && entries.equals(other.entries) && Objects.equals(rewindPoint, other.rewindPoint)
                && viewState.equals(other.viewState) && manifest.equals(other.manifest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nextSeq, floorSeq, entries, rewindPoint, format, viewState, manifest);
    }

    @Override
    public String toString() {
        return "SessionLogState{entries=" + entries.size() + ", floorSeq=" + floorSeq + ", nextSeq=" + nextSeq
                + ", rewindable=" + (rewindPoint != null) + ", format=" + format
                + (viewState.isEmpty() ? "" : ", viewState=" + viewState)
                + (manifest.isEmpty() ? "" : ", sealed=" + manifest.size()) + "}";
    }

    /** Builder for {@link SessionLogState}. Invariants are checked by {@link #build()}. */
    public static final class Builder {

        private List<SessionLogEntry> entries = List.of();
        private long nextSeq;
        private long floorSeq;
        private SessionRewindPoint rewindPoint;
        private SessionLogFormat format = SessionLogFormat.V1;
        private SessionViewState viewState = SessionViewState.empty();
        private List<SessionLogManifestEntry> manifest = List.of();

        private Builder() {
        }

        /**
         * @param manifest
         *            the sealed ranges (must not be null; copied on build; must be empty unless the format is
         *            {@link SessionLogFormat#V2})
         * @return this builder
         */
        public Builder manifest(List<SessionLogManifestEntry> manifest) {
            this.manifest = Objects.requireNonNull(manifest, "manifest cannot be null");
            return this;
        }

        /**
         * @param viewState
         *            what the view leaves out of the log (must not be null; must be empty unless the format is
         *            {@link SessionLogFormat#V2})
         * @return this builder
         */
        public Builder viewState(SessionViewState viewState) {
            this.viewState = Objects.requireNonNull(viewState, "viewState cannot be null");
            return this;
        }

        /**
         * @param entries
         *            the entries in seq order (must not be null; copied on build)
         * @return this builder
         */
        public Builder entries(List<SessionLogEntry> entries) {
            this.entries = Objects.requireNonNull(entries, "entries cannot be null");
            return this;
        }

        /**
         * @param nextSeq
         *            the seq the next append receives
         * @return this builder
         */
        public Builder nextSeq(long nextSeq) {
            this.nextSeq = nextSeq;
            return this;
        }

        /**
         * @param floorSeq
         *            where the log restarts after {@code /clear}
         * @return this builder
         */
        public Builder floorSeq(long floorSeq) {
            this.floorSeq = floorSeq;
            return this;
        }

        /**
         * @param rewindPoint
         *            the rewind point (may be null)
         * @return this builder
         */
        public Builder rewindPoint(SessionRewindPoint rewindPoint) {
            this.rewindPoint = rewindPoint;
            return this;
        }

        /**
         * @param format
         *            the format the state must at least be written in (must not be null)
         * @return this builder
         */
        public Builder format(SessionLogFormat format) {
            this.format = Objects.requireNonNull(format, "format cannot be null");
            return this;
        }

        /**
         * @return the state (never null)
         * @throws IllegalArgumentException
         *             if an invariant is violated
         */
        public SessionLogState build() {
            return new SessionLogState(this);
        }
    }
}
