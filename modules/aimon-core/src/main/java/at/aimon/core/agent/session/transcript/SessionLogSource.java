package at.aimon.core.agent.session.transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
        Objects.requireNonNull(state, "state cannot be null");
        if (reader != null) {
            final List<SessionLogEntry> entries = new ArrayList<>();
            long from = fromSeq;
            while (true) {
                final SessionLogPage page = reader.read(getSessionId(), state, from, toSeq);
                entries.addAll(page.getEntries());
                if (!page.hasMore()) {
                    return entries;
                }
                from = page.getNextFromSeq().getAsLong();
            }
        }
        final List<SessionLogEntry> entries = new ArrayList<>(state.entriesIn(fromSeq, toSeq));
        for (SessionLogManifestEntry line : state.getManifest()) {
            final long from = Math.max(fromSeq, line.getFromSeq());
            final long to = Math.min(toSeq, line.getToSeq());
            if (from < to) {
                entries.add(SessionLogEntry.of(from, Message.user(SessionLogPage.gapText(SeqRange.of(from, to))),
                        LogOrigin.SYNTHETIC));
            }
        }
        entries.sort((a, b) -> Long.compare(a.getSeq(), b.getSeq()));
        return entries;
    }
}
