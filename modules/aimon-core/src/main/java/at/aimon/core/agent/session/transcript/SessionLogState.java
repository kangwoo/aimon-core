package at.aimon.core.agent.session.transcript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 * The entries held here are the ones the record itself carries. {@link #getMessages()} is therefore "the part of the
 * log that is in the record" — today that is the whole log, but it is not a promise that it always will be.
 *
 * <p>
 * <b>Invariants</b>, checked on construction:
 *
 * <ul>
 * <li>{@code 0 <= floorSeq <= nextSeq}
 * <li>entry seqs are strictly increasing and every one lies in {@code [floorSeq, nextSeq)}
 * <li>a rewind point's seq lies in {@code [floorSeq, nextSeq]}
 * </ul>
 *
 * <p>
 * <b>Format.</b> {@link #getFormat()} is the format this state must at least be written in. A state read from a
 * version-2 document carries {@link SessionLogFormat#V2}, so writing it back as version 1 is not possible — see
 * {@link SessionLogFormat} for why the upgrade is sticky.
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

    private SessionLogState(Builder builder) {
        this.nextSeq = builder.nextSeq;
        this.floorSeq = builder.floorSeq;
        this.rewindPoint = builder.rewindPoint;
        this.format = Objects.requireNonNull(builder.format, "format cannot be null");
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
        this.entries = Collections.unmodifiableList(ownedEntries);
        this.messages = Collections.unmodifiableList(ownedMessages);
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
     * Starts building a state. Defaults: no entries, both seqs 0, no rewind point, {@link SessionLogFormat#V1}.
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
                .format(format);
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
     * Returns whether the session has any conversation to speak of: a live {@link LogOrigin#CONVERSATION} entry
     * whose message is a user message.
     *
     * <p>
     * Synthetic user messages do not count — a session whose only user-role entries are runtime injections has not
     * been spoken to yet. Nor does the count of seqs handed out: a new session whose first turn was interrupted and
     * rewound has used seqs but holds no conversation, and must be treated as new.
     *
     * @return true if a live conversation user message exists
     */
    public boolean hasConversation() {
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
     * Not {@code nextSeq - floorSeq}: that also counts the seqs a rewind cut away.
     *
     * @return the live entry count (never negative)
     */
    public int liveEntryCount() {
        return entries.size();
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
        int count = 0;
        for (SessionLogEntry entry : entries) {
            if (entry.getSeq() >= seq) {
                break;
            }
            count++;
        }
        return count;
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
     * @param seq
     *            the first seq to drop
     * @return the shorter state, or {@code this} when there was nothing to drop (never null)
     */
    public SessionLogState truncateFrom(long seq) {
        final int keep = countBefore(seq);
        final boolean dropPoint = rewindPoint != null && rewindPoint.getSeq() >= seq;
        if (keep == entries.size() && !dropPoint) {
            return this;
        }
        return toBuilder().entries(entries.subList(0, keep)).rewindPoint(dropPoint ? null : rewindPoint).build();
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
     * Returns this state emptied the way {@code /clear} empties it: no entries, no rewind point, and
     * {@code floorSeq} raised to {@code nextSeq} so the log continues from where it was rather than from 0.
     *
     * @return the cleared state (never null)
     */
    public SessionLogState clear() {
        return toBuilder().entries(List.of()).floorSeq(nextSeq).rewindPoint(null).build();
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
                && entries.equals(other.entries) && Objects.equals(rewindPoint, other.rewindPoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nextSeq, floorSeq, entries, rewindPoint, format);
    }

    @Override
    public String toString() {
        return "SessionLogState{entries=" + entries.size() + ", floorSeq=" + floorSeq + ", nextSeq=" + nextSeq
                + ", rewindable=" + (rewindPoint != null) + ", format=" + format + "}";
    }

    /** Builder for {@link SessionLogState}. Invariants are checked by {@link #build()}. */
    public static final class Builder {

        private List<SessionLogEntry> entries = List.of();
        private long nextSeq;
        private long floorSeq;
        private SessionRewindPoint rewindPoint;
        private SessionLogFormat format = SessionLogFormat.V1;

        private Builder() {
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
