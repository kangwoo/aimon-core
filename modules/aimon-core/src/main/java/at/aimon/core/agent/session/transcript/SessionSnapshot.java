package at.aimon.core.agent.session.transcript;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.llm.Message;

/**
 * Represents a snapshot of a session's transcript at a specific point in time.
 *
 * <p>
 * Contains the system prompt that was in force and the complete conversation history — every message exchanged
 * between the user, the assistant, and tool results.
 *
 * <p>
 * This snapshot is immutable and captures the exact state of the transcript at the time of execution, including the
 * system prompt that was active at that time. This ensures temporal consistency - even if the agent's configuration
 * changes later, the historical conversation can be understood in its original context.
 *
 * <p>
 * Use cases:
 *
 * <ul>
 * <li>Saving a session's transcript to persistent storage
 * <li>Restoring a stored transcript for multi-turn interactions
 * <li>Analyzing and debugging agent behavior
 * <li>Auditing what instructions the agent was following
 * </ul>
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentExecutionResult result = agent.execute("What is 2 + 2?");
 *     SessionSnapshot snapshot = result.getSnapshot();
 *
 *     // Save to database
 *     database.save(snapshot.getSystemPrompt(), snapshot.getConversationHistory());
 *
 *     // Later, restore the transcript
 *     AgentExecutionRequest followUp = AgentExecutionRequest.builder()
 *             .userInput("Can you explain how you calculated that?")
 *             .conversationHistory(snapshot.getConversationHistory()).build();
 * }
 * </pre>
 */
public final class SessionSnapshot {
    private final SessionId sessionId;
    private final String systemPrompt;
    private final List<Message> conversationHistory;
    private final SessionRewindPoint rewindPoint;

    /**
     * Creates a new SessionSnapshot.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt in force for this session (may be null)
     * @param conversationHistory
     *            The conversation history (must not be null)
     * @throws NullPointerException
     *             if sessionId or conversationHistory is null
     */
    private SessionSnapshot(SessionId sessionId, String systemPrompt, List<Message> conversationHistory,
            SessionRewindPoint rewindPoint) {
        this.sessionId = Objects.requireNonNull(sessionId, "Session id cannot be null");
        this.systemPrompt = systemPrompt;
        this.conversationHistory = List
                .copyOf(Objects.requireNonNull(conversationHistory, "Conversation history cannot be null"));
        this.rewindPoint = rewindPoint;
    }

    /**
     * Creates a new SessionSnapshot.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt in force for this session (may be null)
     * @param conversationHistory
     *            The conversation history (must not be null)
     * @return A new SessionSnapshot
     * @throws NullPointerException
     *             if sessionId or conversationHistory is null
     */
    public static SessionSnapshot of(SessionId sessionId, String systemPrompt, List<Message> conversationHistory) {
        return new SessionSnapshot(sessionId, systemPrompt, conversationHistory, null);
    }

    /**
     * Creates a new SessionSnapshot that also carries a rewind point.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt in force for this session (may be null)
     * @param conversationHistory
     *            The conversation history (must not be null)
     * @param rewindPoint
     *            Where the last turn began if it was interrupted (may be null)
     * @return A new SessionSnapshot
     * @throws NullPointerException
     *             if sessionId or conversationHistory is null
     */
    public static SessionSnapshot of(SessionId sessionId, String systemPrompt, List<Message> conversationHistory,
            SessionRewindPoint rewindPoint) {
        return new SessionSnapshot(sessionId, systemPrompt, conversationHistory, rewindPoint);
    }

    /**
     * Creates a new SessionSnapshot with no system prompt and empty conversation history.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @return A new SessionSnapshot
     * @throws NullPointerException
     *             if sessionId is null
     */
    public static SessionSnapshot of(SessionId sessionId) {
        return new SessionSnapshot(sessionId, null, List.of(), null);
    }

    /**
     * Creates a new SessionSnapshot from a session record view.
     *
     * <p>
     * Accepts {@link SessionRecordView} so callers that loaded a record through
     * {@link SessionRecordStore#load(SessionId)} can build a snapshot without downcasting back to the concrete
     * mutable record type. The reverse direction lives on the store side as {@code SessionRecord.fromSnapshot} —
     * this package deliberately does not depend on the mutable record (design §3.4 rule 3).
     *
     * <p>
     * Only the transcript is carried over. The record's side fields — {@code compactionFailureCount},
     * {@code agentRef}, {@code sessionTotals}, {@code budgetOverride} — stay behind on the record, where their
     * writers keep them; see {@link SessionRecordStore#mergeFromSnapshot(SessionSnapshot)}.
     *
     * @param record
     *            The record view to create snapshot from (must not be null)
     * @return A new SessionSnapshot
     * @throws NullPointerException
     *             if record is null
     */
    public static SessionSnapshot from(SessionRecordView record) {
        Objects.requireNonNull(record, "Record cannot be null");
        return new SessionSnapshot(record.getId(), record.getSystemPrompt(), record.getMessages(),
                record.getRewindPoint().orElse(null));
    }

    /**
     * Gets the session id.
     *
     * @return where the last turn began if it was interrupted, or empty (never null)
     */
    public Optional<SessionRewindPoint> getRewindPoint() {
        return Optional.ofNullable(rewindPoint);
    }

    /**
     * Gets the session id.
     *
     * @return The session id (never null)
     */
    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * Gets the system prompt that was in force for this session.
     *
     * <p>
     * This is the system prompt that was active at the time of execution. Even if the agent configuration changes
     * later, this value remains unchanged, preserving the historical context.
     *
     * @return The system prompt (may be null if no system prompt was set)
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Gets the conversation history.
     *
     * <p>
     * Contains all messages exchanged during the conversation, including:
     *
     * <ul>
     * <li>User messages
     * <li>Assistant responses
     * <li>Tool use requests
     * <li>Tool results
     * </ul>
     *
     * @return An immutable list of messages (never null)
     */
    public List<Message> getConversationHistory() {
        return conversationHistory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SessionSnapshot that = (SessionSnapshot) o;
        return sessionId.equals(that.sessionId) && Objects.equals(systemPrompt, that.systemPrompt)
                && conversationHistory.equals(that.conversationHistory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, systemPrompt, conversationHistory);
    }

    @Override
    public String toString() {
        String promptPreview;
        if (systemPrompt == null) {
            promptPreview = "null";
        } else {
            promptPreview = systemPrompt.length() > 50 ? systemPrompt.substring(0, 50) + "...'" : systemPrompt + "'";
        }
        return "SessionSnapshot{" + "sessionId=" + sessionId + ", systemPrompt='" + promptPreview + ", messageCount="
                + conversationHistory.size() + '}';
    }
}
