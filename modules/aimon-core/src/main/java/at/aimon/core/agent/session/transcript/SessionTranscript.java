package at.aimon.core.agent.session.transcript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.store.SessionRecord;
import at.aimon.core.llm.Message;

/**
 * The immutable {@code (systemPrompt, messages)} pair that makes up a session's LLM-visible conversation history.
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
 * Instances are immutable and therefore thread-safe.
 */
public final class SessionTranscript {

    private static final SessionTranscript EMPTY = new SessionTranscript(null,
            Collections.unmodifiableList(new ArrayList<>()), null);

    private final String systemPrompt;

    /** Always an unmodifiable view over a list this instance exclusively owns. */
    private final List<Message> messages;

    /**
     * Where the most recent turn began, when that turn was interrupted; null otherwise.
     *
     * <p>
     * Held here rather than as a side field of the record because it counts <em>these</em> messages. A write that
     * replaces the message list replaces the point along with it, which is what keeps the two from disagreeing —
     * compaction rewrites the history through {@code replaceWith}, and a point that survived that would index into a
     * history that no longer exists.
     */
    private final SessionRewindPoint rewindPoint;

    /**
     * @param systemPrompt
     *            the system prompt (may be null)
     * @param ownedUnmodifiableMessages
     *            an unmodifiable view over a list no other object holds a reference to
     * @param rewindPoint
     *            where the last turn began if it was interrupted (may be null)
     */
    private SessionTranscript(String systemPrompt, List<Message> ownedUnmodifiableMessages,
            SessionRewindPoint rewindPoint) {
        this.systemPrompt = systemPrompt;
        this.messages = ownedUnmodifiableMessages;
        this.rewindPoint = rewindPoint;
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
     * The message list is defensively copied, so later mutations of {@code messages} do not affect the result.
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
     *             if {@code rewindPoint} counts more messages than there are
     */
    public static SessionTranscript of(String systemPrompt, List<Message> messages, SessionRewindPoint rewindPoint) {
        Objects.requireNonNull(messages, "messages cannot be null");
        if (rewindPoint != null && rewindPoint.getMessageCount() > messages.size()) {
            throw new IllegalArgumentException("rewindPoint counts " + rewindPoint.getMessageCount()
                    + " messages but the transcript holds " + messages.size());
        }
        if (systemPrompt == null && messages.isEmpty() && rewindPoint == null) {
            return EMPTY;
        }
        return new SessionTranscript(systemPrompt, Collections.unmodifiableList(new ArrayList<>(messages)),
                rewindPoint);
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
     * Gets the message history.
     *
     * <p>
     * The returned list is unmodifiable and, because this type is immutable, is a stable snapshot — it is safe to
     * hold on to across subsequent {@link #append(Message)} calls, which return a new transcript rather than
     * mutating this one.
     *
     * @return the messages (never null, may be empty)
     */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * @return the number of messages in this transcript
     */
    public int size() {
        return messages.size();
    }

    /**
     * @return true if this transcript has no messages
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }

    /**
     * Returns a copy of this transcript with a different system prompt.
     *
     * @param newSystemPrompt
     *            the replacement system prompt (may be null to clear it)
     * @return a transcript with the same messages and the given prompt (never null)
     */
    public SessionTranscript withSystemPrompt(String newSystemPrompt) {
        if (Objects.equals(this.systemPrompt, newSystemPrompt)) {
            return this;
        }
        return new SessionTranscript(newSystemPrompt, messages, rewindPoint);
    }

    /**
     * Returns where the most recent turn began, if that turn was interrupted and can therefore be retried.
     *
     * @return the rewind point, or empty when the last turn ended some other way (never null)
     */
    public Optional<SessionRewindPoint> getRewindPoint() {
        return Optional.ofNullable(rewindPoint);
    }

    /**
     * Returns a copy of this transcript carrying {@code newRewindPoint}.
     *
     * @param newRewindPoint
     *            the point to carry, or null to drop the one held
     * @return a transcript with the same prompt and messages (never null)
     * @throws IllegalArgumentException
     *             if {@code newRewindPoint} counts more messages than there are
     */
    public SessionTranscript withRewindPoint(SessionRewindPoint newRewindPoint) {
        if (Objects.equals(rewindPoint, newRewindPoint)) {
            return this;
        }
        if (newRewindPoint != null && newRewindPoint.getMessageCount() > messages.size()) {
            throw new IllegalArgumentException("rewindPoint counts " + newRewindPoint.getMessageCount()
                    + " messages but the transcript holds " + messages.size());
        }
        return new SessionTranscript(systemPrompt, messages, newRewindPoint);
    }

    /**
     * Returns this transcript with the interrupted turn taken back out — the messages it added are dropped and the
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
        if (rewindPoint == null) {
            return this;
        }
        final List<Message> kept = new ArrayList<>(messages.subList(0, rewindPoint.getMessageCount()));
        return of(systemPrompt, kept, null);
    }

    /**
     * Returns a copy of this transcript with {@code message} appended.
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
        final List<Message> appended = new ArrayList<>(messages.size() + 1);
        appended.addAll(messages);
        appended.add(message);
        return new SessionTranscript(systemPrompt, Collections.unmodifiableList(appended), rewindPoint);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionTranscript other)) {
            return false;
        }
        return Objects.equals(systemPrompt, other.systemPrompt) && messages.equals(other.messages)
                && Objects.equals(rewindPoint, other.rewindPoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systemPrompt, messages, rewindPoint);
    }

    @Override
    public String toString() {
        return "SessionTranscript{hasSystemPrompt=" + (systemPrompt != null) + ", messages=" + messages.size()
                + ", rewindable=" + (rewindPoint != null) + "}";
    }
}
