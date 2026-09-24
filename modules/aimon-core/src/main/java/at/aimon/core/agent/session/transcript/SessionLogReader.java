package at.aimon.core.agent.session.transcript;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionLogSegment;
import at.aimon.core.agent.session.store.SessionLogSegmentCodec;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Reads a session's whole log — carried entries and sealed ranges alike — a page at a time (session-log §3.3–§3.4,
 * §5.5).
 *
 * <p>
 * <b>Only the manifest decides.</b> A sealed range is read from the segment its manifest line names, and a segment
 * no line names is never read, however present it is in the store. After {@code /clear} the manifest is empty, so the
 * cleared conversation is unreadable at once, before its segments are deleted.
 *
 * <p>
 * <b>Gaps, not failures.</b> A segment that is missing, whose payload hash does not match the line, or whose entries
 * do not fit the line is reported as a gap and logged at WARN — see {@link SessionLogPage}. Losing part of the history
 * must not lose the whole of it.
 *
 * <p>
 * <b>Pages.</b> A page holds up to {@code maxReadTokens} estimated tokens — the whole log can be several model windows
 * long, and the API shape is what stops a caller loading it at once. A page ends only at a legal cut (context-engine
 * §3.4, §7), so a page handed to one LLM call never carries an unanswered {@code tool_use} or an orphan
 * {@code tool_result}; a run that cannot be cut is kept whole even when it exceeds the budget. Every page holds at
 * least
 * one entry.
 *
 * <p>
 * The execution loop never uses this; it has no reason to read sealed entries (session-log §9).
 *
 * <p>
 * Thread-safe if the stores are.
 */
public final class SessionLogReader {

    private static final Logger log = LoggerFactory.getLogger(SessionLogReader.class);

    private final SessionRecordStore records;
    private final SessionLogSegmentStore segments;
    private final int maxReadTokens;
    private final TokenEstimator tokenEstimator;

    /**
     * @param records
     *            where the records — and so the manifests — are loaded from (must not be null)
     * @param segments
     *            where sealed ranges are read from (must not be null)
     * @param maxReadTokens
     *            the page size in estimated tokens; zero or less reads to the end in one page
     * @param tokenEstimator
     *            how entries are sized (must not be null)
     */
    public SessionLogReader(SessionRecordStore records, SessionLogSegmentStore segments, int maxReadTokens,
            TokenEstimator tokenEstimator) {
        this.records = Objects.requireNonNull(records, "records cannot be null");
        this.segments = Objects.requireNonNull(segments, "segments cannot be null");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator cannot be null");
        this.maxReadTokens = maxReadTokens;
    }

    /**
     * Reads one page of the log of the stored record of {@code sessionId}.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param fromSeq
     *            the first seq to read
     * @param toSeq
     *            the first seq not to read ({@code Long.MAX_VALUE} for "to the end")
     * @return the page; empty when there is no record (never null)
     */
    public SessionLogPage read(SessionId sessionId, long fromSeq, long toSeq) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        final Optional<SessionRecordView> record = records.load(sessionId);
        return record.isEmpty() ? SessionLogPage.empty() : read(sessionId, record.get().getLogState(), fromSeq, toSeq);
    }

    /**
     * Reads one page of {@code state} — a log already in hand, such as a running turn's buffer, whose carried entries
     * may be newer than the stored record's.
     *
     * @param sessionId
     *            the session the segments belong to (must not be null)
     * @param state
     *            the log (must not be null)
     * @param fromSeq
     *            the first seq to read
     * @param toSeq
     *            the first seq not to read
     * @return the page (never null)
     */
    public SessionLogPage read(SessionId sessionId, SessionLogState state, long fromSeq, long toSeq) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
        final long from = Math.max(fromSeq, state.getFloorSeq());
        final long to = Math.min(toSeq, state.getNextSeq());
        if (from >= to) {
            return SessionLogPage.empty();
        }
        final Cursor cursor = new Cursor(sessionId, state, from, to);
        final List<SessionLogEntry> page = new ArrayList<>();
        final List<SeqRange> gaps = new ArrayList<>();
        final Set<String> pending = new HashSet<>();
        long tokens = 0;
        while (cursor.hasNext()) {
            final Item item = cursor.next();
            page.add(item.entry);
            if (item.gap != null) {
                gaps.add(item.gap);
            } else if (item.entry.getMessage() != null) {
                track(pending, item.entry.getMessage());
                tokens += tokenEstimator.estimateMessage(item.entry.getMessage());
            }
            if (maxReadTokens > 0 && tokens >= maxReadTokens && pending.isEmpty() && cursor.hasNext()
                    && !cursor.peek().isToolResult()) {
                return SessionLogPage.of(page, gaps, OptionalLong.of(cursor.peek().entry.getSeq()));
            }
        }
        return SessionLogPage.of(page, gaps, OptionalLong.empty());
    }

    private static void track(Set<String> pending, Message message) {
        for (ToolUse toolUse : message.getToolUses()) {
            pending.add(toolUse.getId());
        }
        for (ToolUseResult result : message.getToolUseResults()) {
            pending.remove(result.getToolUseId());
        }
    }

    /** One entry of the walk, or the placeholder for an unreadable range. */
    private static final class Item {
        private final SessionLogEntry entry;
        private final SeqRange gap;

        private Item(SessionLogEntry entry, SeqRange gap) {
            this.entry = entry;
            this.gap = gap;
        }

        private boolean isToolResult() {
            return gap == null && entry.getMessage() != null && entry.getMessage().getRole() == Role.TOOL;
        }
    }

    /**
     * Walks carried entries and sealed ranges in seq order, loading one segment at a time and only when the walk
     * reaches it — a page never reads a segment past the one it stops in, plus one for look-ahead.
     */
    private final class Cursor {
        private final SessionId sessionId;
        private final List<SessionLogEntry> carried;
        private final List<SessionLogManifestEntry> lines;
        private final long from;
        private final long to;
        private int carriedIndex;
        private int lineIndex;
        private final Deque<Item> buffered = new ArrayDeque<>();

        private Cursor(SessionId sessionId, SessionLogState state, long from, long to) {
            this.sessionId = sessionId;
            this.carried = state.entriesIn(from, to);
            this.from = from;
            this.to = to;
            final List<SessionLogManifestEntry> overlapping = new ArrayList<>();
            for (SessionLogManifestEntry line : state.getManifest()) {
                if (line.getToSeq() > from && line.getFromSeq() < to) {
                    overlapping.add(line);
                }
            }
            this.lines = overlapping;
        }

        private boolean hasNext() {
            fill();
            return !buffered.isEmpty();
        }

        private Item peek() {
            fill();
            return buffered.peekFirst();
        }

        private Item next() {
            fill();
            return buffered.removeFirst();
        }

        private void fill() {
            if (!buffered.isEmpty()) {
                return;
            }
            final boolean carriedLeft = carriedIndex < carried.size();
            final boolean linesLeft = lineIndex < lines.size();
            if (!carriedLeft && !linesLeft) {
                return;
            }
            if (linesLeft && (!carriedLeft || lines.get(lineIndex).getFromSeq() < carried.get(carriedIndex).getSeq())) {
                expand(lines.get(lineIndex++));
            } else {
                buffered.addLast(new Item(carried.get(carriedIndex++), null));
            }
        }

        private void expand(SessionLogManifestEntry line) {
            final SeqRange wanted = SeqRange.of(Math.max(from, line.getFromSeq()), Math.min(to, line.getToSeq()));
            final List<SessionLogEntry> sealed = load(line);
            if (sealed == null) {
                buffered.addLast(new Item(SessionLogEntry.of(wanted.getFromSeq(),
                        Message.user(SessionLogPage.gapText(wanted)), LogOrigin.SYNTHETIC), wanted));
                return;
            }
            for (SessionLogEntry entry : sealed) {
                if (wanted.contains(entry.getSeq())) {
                    buffered.addLast(new Item(entry, null));
                }
            }
            if (buffered.isEmpty()) {
                // Nothing of this line falls in the window; move on so the caller never sees an empty fill.
                fill();
            }
        }

        /** Returns the entries of the line's segment, or null when the segment cannot be trusted. */
        private List<SessionLogEntry> load(SessionLogManifestEntry line) {
            final Optional<SessionLogSegment> segment;
            try {
                segment = segments.get(sessionId, line.getSegmentId());
            } catch (RuntimeException e) {
                log.warn("Reading segment {} of session {} failed; reporting [{}, {}) as unavailable: {}",
                        line.getSegmentId(), sessionId.value(), line.getFromSeq(), line.getToSeq(), e.toString());
                return null;
            }
            if (segment.isEmpty()) {
                log.warn("Segment {} of session {} is missing; reporting [{}, {}) as unavailable", line.getSegmentId(),
                        sessionId.value(), line.getFromSeq(), line.getToSeq());
                return null;
            }
            final String payload = segment.get().getPayload();
            if (!line.getContentHash().equals(SessionLogSegmentCodec.contentHash(payload))) {
                log.warn(
                        "Segment {} of session {} does not match its manifest hash; reporting [{}, {}) as"
                                + " unavailable",
                        line.getSegmentId(), sessionId.value(), line.getFromSeq(), line.getToSeq());
                return null;
            }
            try {
                final List<SessionLogEntry> entries = SessionLogSegmentCodec.decode(payload);
                if (entries.size() != line.getEntryCount()) {
                    throw new IllegalStateException(
                            "holds " + entries.size() + " entries, the manifest says " + line.getEntryCount());
                }
                long previous = -1;
                for (SessionLogEntry entry : entries) {
                    if (!line.contains(entry.getSeq()) || entry.getSeq() <= previous) {
                        throw new IllegalStateException("entry seq " + entry.getSeq() + " does not fit");
                    }
                    previous = entry.getSeq();
                }
                return entries;
            } catch (RuntimeException e) {
                log.warn("Segment {} of session {} is unreadable; reporting [{}, {}) as unavailable: {}",
                        line.getSegmentId(), sessionId.value(), line.getFromSeq(), line.getToSeq(), e.getMessage());
                return null;
            }
        }
    }
}
