package at.aimon.core.agent.session.transcript;

import java.util.Objects;

import at.aimon.core.llm.Message;

/**
 * One entry of a session log: a message, the seq that addresses it, and where it came from.
 *
 * <p>
 * The seq is the entry's address for as long as the session exists. Seqs start at 0, increase by one per append and
 * are never reused — not after a rewind cuts the log, not after {@code /clear} empties it — so a value that points at
 * an entry cannot come to point at a different one.
 *
 * <p>
 * The message may be {@code null}. That is the null tolerance {@link SessionTranscript} has always had, carried over
 * rather than tightened here; every writer in the runtime appends non-null messages.
 *
 * <p>
 * Immutable and therefore thread-safe.
 */
public final class SessionLogEntry {

    private final long seq;
    private final Message message;
    private final LogOrigin origin;

    private SessionLogEntry(long seq, Message message, LogOrigin origin) {
        this.seq = seq;
        this.message = message;
        this.origin = origin;
    }

    /**
     * Creates an entry.
     *
     * @param seq
     *            the entry's seq (must not be negative)
     * @param message
     *            the message (may be null, see the class javadoc)
     * @param origin
     *            where the message came from (must not be null)
     * @return a new entry (never null)
     * @throws IllegalArgumentException
     *             if {@code seq} is negative
     * @throws NullPointerException
     *             if {@code origin} is null
     */
    public static SessionLogEntry of(long seq, Message message, LogOrigin origin) {
        if (seq < 0) {
            throw new IllegalArgumentException("seq cannot be negative, got: " + seq);
        }
        return new SessionLogEntry(seq, message, Objects.requireNonNull(origin, "origin cannot be null"));
    }

    /**
     * @return the entry's seq (never negative)
     */
    public long getSeq() {
        return seq;
    }

    /**
     * @return the message (may be null, see the class javadoc)
     */
    public Message getMessage() {
        return message;
    }

    /**
     * @return where the message came from (never null)
     */
    public LogOrigin getOrigin() {
        return origin;
    }

    /**
     * Returns a copy of this entry holding a different message, at the same seq and with the same origin.
     *
     * @param newMessage
     *            the replacement message (may be null)
     * @return the copy (never null)
     */
    public SessionLogEntry withMessage(Message newMessage) {
        return new SessionLogEntry(seq, newMessage, origin);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionLogEntry other)) {
            return false;
        }
        return seq == other.seq && origin == other.origin && Objects.equals(message, other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seq, message, origin);
    }

    @Override
    public String toString() {
        return "SessionLogEntry{seq=" + seq + ", origin=" + origin + ", role="
                + (message == null ? "null" : message.getRole()) + "}";
    }
}
