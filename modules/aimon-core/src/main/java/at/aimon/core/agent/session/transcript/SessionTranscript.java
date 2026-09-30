package at.aimon.core.agent.session.transcript;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.store.SessionRecord;
import at.aimon.core.llm.Message;

/**
 * The immutable {@code (systemPrompt, SessionLogState)} pair that makes up a session's stored transcript.
 *
 * <p>
 * {@link SessionRecord} is an aggregate of two unrelated halves: the transcript below, and a set of bookkeeping side
 * fields ({@code compactionFailureCount}, {@code agentRef}, {@code sessionTotals}, {@code budgetOverride}) that
 * are written by different owners at different times. Splitting the transcript out lets the history be shared by
 * reference between a session record and its copies — {@code InMemorySessionRecordStore} previously copied the
 * message list twice on every {@code save}/{@code load} round trip purely to keep the two instances independent.
 *
 * <p>
 * <strong>Null tolerance is deliberate.</strong> The backing list is built with {@code new ArrayList<>(..)} and not
 * {@code List.copyOf(..)}, matching the constructor this type replaced. Two consequences that callers depend on:
 * null elements are accepted rather than rejected, and {@link #getMessages()} answers {@code contains(null)} with
 * {@code false} instead of throwing.
 *
 * <p>
 * <strong>Appending is O(n).</strong> {@link #append(Message)} copies the backing list, so building a transcript one
 * message at a time is quadratic. That is the right trade here — this type sits on the persistence and copy paths,
 * where whole transcripts are handed around, not appended to. The append-heavy hot path is
 * {@link TranscriptBuffer}, which keeps a mutable list on purpose and bridges to persistence via
 * {@link SessionSnapshot}.
 *
 * <p>
 * <strong>The log is one value.</strong> Everything but the prompt — entries, seqs, rewind point, format — is held as
 * a single {@link SessionLogState} and handed on whole. {@link #getMessages()} is that state's messages, the part of
 * the log the record carries.
 *
 * <p>
 * Instances are immutable and therefore thread-safe.
 */
public final class SessionTranscript {

    private static final SessionTranscript EMPTY = new SessionTranscript(null, SessionLogState.empty());

    private final String systemPrompt;

    /**
     * The log: entries, seqs, and the rewind point.
     *
     * <p>
     * The rewind point is held in here rather than as a side field of the record because it addresses <em>these</em>
     * entries. A write that replaces the log replaces the point along with it, which is what keeps the two from
     * disagreeing.
     */
    private final SessionLogState logState;

    private SessionTranscript(String systemPrompt, SessionLogState logState) {
        this.systemPrompt = systemPrompt;
        this.logState = logState;
    }

    /**
     * Returns the empty transcript — no system prompt, no messages.
     *
     * @return the shared empty transcript (never null)
     */
    public static SessionTranscript empty() {
        return EMPTY;
    }

    /**
     * Creates a transcript from a system prompt and a message history.
     *
     * <p>
     * The messages become a version-1 log (see {@link SessionLogState#ofMessages(List)}); the list is defensively
     * copied, so later mutations of {@code messages} do not affect the result.
     *
     * @param systemPrompt
     *            the system prompt (may be null)
     * @param messages
     *            the message history (must not be null, may be empty, may contain null elements)
     * @return a new transcript (never null)
     * @throws NullPointerException
     *             if {@code messages} is null
     */
    public static SessionTranscript of(String systemPrompt, List<Message> messages) {
        return of(systemPrompt, messages, null);
    }

    /**
     * Creates a transcript that also carries a rewind point.
     *
     * @param systemPrompt
     *            the system prompt (may be null)
     * @param messages
     *            the message history (must not be null, may be empty, may contain null elements)
     * @param rewindPoint
     *            where the last turn began if it was interrupted (may be null)
     * @return a new transcript (never null)
     * @throws NullPointerException
     *             if {@code messages} is null
     * @throws IllegalArgumentException
     *             if {@code rewindPoint} points past the end of the messages
     */
    public static SessionTranscript of(String systemPrompt, List<Message> messages, SessionRewindPoint rewindPoint) {
        Objects.requireNonNull(messages, "messages cannot be null");
        return fromLog(systemPrompt, SessionLogState.ofMessages(messages, rewindPoint));
    }

    /**
     * Creates a transcript around an already-built log, adopted whole.
     *
     * @param systemPrompt
     *            the system prompt (may be null)
     * @param logState
     *            the log (must not be null)
     * @return a new transcript (never null)
     * @throws NullPointerException
     *             if {@code logState} is null
     */
    public static SessionTranscript fromLog(String systemPrompt, SessionLogState logState) {
        Objects.requireNonNull(logState, "logState cannot be null");
        if (systemPrompt == null && logState.equals(SessionLogState.empty())) {
            return EMPTY;
        }
        return new SessionTranscript(systemPrompt, logState);
    }

    /**
     * Gets the system prompt.
     *
     * @return the system prompt (may be null)
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Gets the log, whole.
     *
     * @return the log state (never null)
     */
    public SessionLogState getLogState() {
        return logState;
    }

    /**
     * Gets the messages of the log entries this transcript carries.
     *
     * <p>
     * The returned list is unmodifiable and, because this type is immutable, is a stable snapshot — it is safe to
     * hold on to across subsequent {@link #append(Message)} calls, which return a new transcript rather than
     * mutating this one.
     *
     * @return the messages (never null, may be empty)
     */
    public List<Message> getMessages() {
        return logState.getMessages();
    }

    /**
     * @return the number of messages in this transcript
     */
    public int size() {
        return logState.getMessages().size();
    }

    /**
     * @return true if this transcript has no messages
     */
    public boolean isEmpty() {
        return logState.getMessages().isEmpty();
    }

    /**
     * Returns a copy of this transcript with a different system prompt.
     *
     * @param newSystemPrompt
     *            the replacement system prompt (may be null to clear it)
     * @return a transcript with the same log and the given prompt (never null)
     */
    public SessionTranscript withSystemPrompt(String newSystemPrompt) {
        if (Objects.equals(this.systemPrompt, newSystemPrompt)) {
            return this;
        }
        return fromLog(newSystemPrompt, logState);
    }

    /**
     * Returns where the most recent turn began, if that turn was interrupted and can therefore be retried.
     *
     * @return the rewind point, or empty when the last turn ended some other way (never null)
     */
    public Optional<SessionRewindPoint> getRewindPoint() {
        return logState.getRewindPoint();
    }

    /**
     * Returns a copy of this transcript carrying {@code newRewindPoint}.
     *
     * @param newRewindPoint
     *            the point to carry, or null to drop the one held
     * @return a transcript with the same prompt and entries (never null)
     * @throws IllegalArgumentException
     *             if {@code newRewindPoint}'s seq lies outside {@code [floorSeq, nextSeq]}
     */
    public SessionTranscript withRewindPoint(SessionRewindPoint newRewindPoint) {
        final SessionLogState updated = logState.withRewindPoint(newRewindPoint);
        return updated == logState ? this : fromLog(systemPrompt, updated);
    }

    /**
     * Returns this transcript with the interrupted turn taken back out — the entries it added are dropped and the
     * rewind point with them.
     *
     * <p>
     * Dropping the point in the same step is what makes a retry safe to repeat: the rewound transcript no longer
     * claims to have an interrupted turn, so a second rewind cannot cut into the turn before it.
     *
     * @return the transcript as it was before the interrupted turn, or {@code this} when there is no point to rewind
     *         to (never null)
     */
    public SessionTranscript rewind() {
        final SessionLogState rewound = logState.rewind();
        return rewound == logState ? this : fromLog(systemPrompt, rewound);
    }

    /**
     * Returns a copy of this transcript with {@code message} appended as {@link LogOrigin#CONVERSATION}.
     *
     * <p>
     * Runs in O(n): the backing list is copied. See the class javadoc for why that is acceptable here.
     *
     * @param message
     *            the message to append (must not be null)
     * @return a transcript one message longer (never null)
     * @throws NullPointerException
     *             if {@code message} is null
     */
    public SessionTranscript append(Message message) {
        Objects.requireNonNull(message, "message cannot be null");
        return new SessionTranscript(systemPrompt, logState.append(message, LogOrigin.CONVERSATION));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionTranscript other)) {
            return false;
        }
        return Objects.equals(systemPrompt, other.systemPrompt) && logState.equals(other.logState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systemPrompt, logState);
    }

    @Override
    public String toString() {
        return "SessionTranscript{hasSystemPrompt=" + (systemPrompt != null) + ", messages=" + size() + ", rewindable="
                + logState.getRewindPoint().isPresent() + "}";
    }
}
