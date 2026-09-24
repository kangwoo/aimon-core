package at.aimon.core.agent.session.transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.Message;

/**
 * Reads the log of a session that is running right now — its carried entries as the running execution's buffer holds
 * them, and its sealed ranges from the segment store.
 *
 * <p>
 * The buffer, not the stored record, is the source of truth mid-execution: entries appended since the last checkpoint
 * are not in the record yet. So this reads {@link TranscriptBuffer#getLogState()} on every call, and hands sealed
 * ranges to the {@link SessionLogReader} when there is one. Without a reader, a sealed range is reported as a gap —
 * the same way the reader reports a segment it cannot read.
 *
 * <p>
 * Thread-safe: the buffer's state is an immutable snapshot, read under its lock.
 */
public final class SessionLogSource {

    private final TranscriptBuffer buffer;
    private final SessionLogReader reader;

    private SessionLogSource(TranscriptBuffer buffer, SessionLogReader reader) {
        this.buffer = Objects.requireNonNull(buffer, "buffer cannot be null");
        this.reader = reader;
    }

    /**
     * @param buffer
     *            the running execution's buffer (must not be null)
     * @param reader
     *            reads sealed ranges, or {@code null} when the session seals nothing
     * @return the source (never null)
     */
    public static SessionLogSource of(TranscriptBuffer buffer, SessionLogReader reader) {
        return new SessionLogSource(buffer, reader);
    }

    /** The session the log belongs to. */
    public SessionId getSessionId() {
        return buffer.getSessionId();
    }

    /** The log as it stands now. */
    public SessionLogState currentLog() {
        return buffer.getLogState();
    }

    /** The reader for sealed ranges, if the session has somewhere to seal to. */
    public Optional<SessionLogReader> getReader() {
        return Optional.ofNullable(reader);
    }

    /**
     * Reads every entry of {@code state} in {@code [fromSeq, toSeq)}, sealed ranges included, in seq order. A range
     * that cannot be read comes back as the reader's gap entry.
     *
     * @param state
     *            the log to read (must not be null)
     * @param fromSeq
     *            the first seq
     * @param toSeq
     *            the first seq not to read
     * @return the entries (never null)
     */
    public List<SessionLogEntry> read(SessionLogState state, long fromSeq, long toSeq) {
        return readRange(state, fromSeq, toSeq, SessionLogReadCache.create()).getEntries();
    }

    /**
     * Reads every entry of {@code state} in {@code [fromSeq, toSeq)} as one page: the entries in seq order, gap entries
     * included, and the unreadable ranges as {@link SessionLogPage#getGaps()} — so a caller need not recognise a gap by
     * its text. Sealed segments are loaded at most once across every call given the same {@code cache}.
     *
     * @param state
     *            the log to read (must not be null)
     * @param fromSeq
     *            the first seq
     * @param toSeq
     *            the first seq not to read
     * @param cache
     *            the caller's cache for this operation (must not be null)
     * @return the whole range as a last page (never null)
     */
    public SessionLogPage readRange(SessionLogState state, long fromSeq, long toSeq, SessionLogReadCache cache) {
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(cache, "cache cannot be null");
        final List<SessionLogEntry> entries = new ArrayList<>();
        final List<SeqRange> gaps = new ArrayList<>();
        if (reader != null) {
            long from = fromSeq;
            while (true) {
                final SessionLogPage page = reader.read(getSessionId(), state, from, toSeq, cache);
                entries.addAll(page.getEntries());
                gaps.addAll(page.getGaps());
                if (!page.hasMore()) {
                    return SessionLogPage.of(entries, gaps, OptionalLong.empty());
                }
                from = page.getNextFromSeq().getAsLong();
            }
        }
        entries.addAll(state.entriesIn(fromSeq, toSeq));
        for (SessionLogManifestEntry line : state.getManifest()) {
            final long from = Math.max(fromSeq, line.getFromSeq());
            final long to = Math.min(toSeq, line.getToSeq());
            if (from < to) {
                final SeqRange gap = SeqRange.of(from, to);
                gaps.add(gap);
                entries.add(SessionLogEntry.of(from, Message.user(SessionLogPage.gapText(gap)), LogOrigin.SYNTHETIC));
            }
        }
        entries.sort((a, b) -> Long.compare(a.getSeq(), b.getSeq()));
        return SessionLogPage.of(entries, gaps, OptionalLong.empty());
    }
}
