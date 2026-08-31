package at.aimon.core.agent.session.transcript;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionCheckpointMailbox;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;

/**
 * The append-oriented, mutable conversation history of one session.
 *
 * <p>
 * Maintains a list of messages exchanged between the user and assistant, along with an optional system prompt for
 * persistence.
 *
 * <p>
 * <b>Thread safety:</b> Mutator and reader methods are {@code synchronized} on this instance, so concurrent access
 * from the {@link SessionCheckpointMailbox} writer thread does not race with the agent's main ReAct loop
 * thread.
 * {@link at.aimon.core.agent.compact.CompactionGuard}'s per-{@code SessionId} lock continues to serialize
 * {@link #replaceWith(java.util.List)} against concurrent compaction attempts at the agent layer.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create with session id and system prompt
 *     SessionId id = new SessionId(UUID.randomUUID().toString());
 *     TranscriptBuffer context = new TranscriptBuffer(id, "You are a helpful assistant.");
 *
 *     // Add user message
 *     context.addUserMessage("What is the weather?");
 *
 *     // Add assistant message
 *     context.addAssistantMessage("I'll check the weather for you.");
 *
 *     // Get all messages
 *     List<Message> history = context.getMessages();
 *
 *     // Create with session id, system prompt and initial messages
 *     List<Message> initialMessages = List.of(Message.user("Hello"), Message.assistant("Hi! How can I help you?"));
 *     TranscriptBuffer contextWithMessages = new TranscriptBuffer(id, "You are a helpful assistant.",
 *             initialMessages);
 * }
 * </pre>
 */
public class TranscriptBuffer {

    /**
     * Listener invoked after every mutation. Used by {@link SessionCheckpointMailbox} to raise a mid-turn
     * checkpoint. A memory has at most one listener; {@code null} disables notification.
     */
    public interface DirtyListener {
        /**
         * Called after a mutation has been applied to {@code memory} and its version counter has been bumped.
         * Invoked while holding the memory's intrinsic lock — the listener must not block on long-running work.
         *
         * <p>
         * Listeners may safely call {@code memory.getVersion()} or other {@code synchronized} readers (Java intrinsic
         * locks are reentrant), but should avoid re-entering mutators or doing significant work inside the callback;
         * the recommended pattern is to enqueue a task on a background executor and return immediately.
         *
         * @param memory
         *            the memory that was mutated (never null)
         */
        void onMutate(TranscriptBuffer memory);
    }

    private final SessionId sessionId;
    private final Clock clock;
    private String systemPrompt;
    private final List<Message> messages;
    private final List<Instant> messageTimestamps;
    private long version;
    private volatile DirtyListener dirtyListener;

    /**
     * Where the turn currently being run began, kept so an interrupted turn can be taken back out later.
     *
     * <p>
     * Set at the top of a turn and cleared when the turn ends any way other than interrupted, so at rest it is
     * non-null exactly when the last turn was stopped. It is deliberately <em>not</em> one of the mutations that bump
     * {@link #getVersion()} or notify the dirty listener: it says nothing about the LLM-visible history, and raising a
     * mid-turn checkpoint because a turn started would be a checkpoint for nothing.
     */
    private SessionRewindPoint rewindPoint;

    /**
     * Creates an empty buffer without a system prompt.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @throws NullPointerException
     *             if sessionId is null
     */
    public TranscriptBuffer(SessionId sessionId) {
        this(sessionId, null);
    }

    /**
     * Creates an empty buffer with a system prompt.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt (can be null)
     * @throws NullPointerException
     *             if sessionId is null
     */
    public TranscriptBuffer(SessionId sessionId, String systemPrompt) {
        this(sessionId, systemPrompt, List.of(), Clock.systemUTC());
    }

    /**
     * Creates a buffer with a system prompt and initial messages.
     *
     * <p>
     * Creates a defensive copy of the provided messages list. Each initial message receives the current clock instant
     * as
     * its timestamp — original timestamps from a prior session are not preserved through this entry point. Use
     * {@link #fromSnapshot(SessionSnapshot)} for snapshot rehydration semantics.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt (can be null)
     * @param messages
     *            The initial messages (must not be null, but can be empty)
     * @throws NullPointerException
     *             if sessionId or messages is null
     */
    public TranscriptBuffer(SessionId sessionId, String systemPrompt, List<Message> messages) {
        this(sessionId, systemPrompt, messages, Clock.systemUTC());
    }

    /**
     * Creates a buffer with an injected {@link Clock} for deterministic timestamp generation.
     *
     * <p>
     * Intended for tests and for scenarios that require a frozen or stepped clock (e.g.
     * {@link at.aimon.core.agent.compact.TimeBasedMicrocompact}). Production callers should prefer the simpler
     * constructors which install {@link Clock#systemUTC()}.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt (can be null)
     * @param messages
     *            The initial messages (must not be null, but can be empty)
     * @param clock
     *            the clock used to stamp messages on every mutation (must not be null)
     * @throws NullPointerException
     *             if {@code sessionId}, {@code messages}, or {@code clock} is null
     */
    public TranscriptBuffer(SessionId sessionId, String systemPrompt, List<Message> messages, Clock clock) {
        this.sessionId = Objects.requireNonNull(sessionId, "Session id cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        this.messages = new ArrayList<>(messages);
        this.messageTimestamps = new ArrayList<>(messages.size());
        if (!messages.isEmpty()) {
            final Instant now = clock.instant();
            for (int i = 0; i < messages.size(); i++) {
                this.messageTimestamps.add(now);
            }
        }
        this.systemPrompt = systemPrompt;
    }

    /**
     * Adds a user message to the conversation.
     *
     * @param content
     *            The message content (must not be null)
     * @throws NullPointerException
     *             if content is null
     */
    public synchronized void addUserMessage(String content) {
        Objects.requireNonNull(content, "Content cannot be null");
        messages.add(Message.user(content));
        messageTimestamps.add(clock.instant());
        markDirty();
    }

    /**
     * Adds an assistant message to the conversation.
     *
     * @param content
     *            The message content (must not be null)
     * @throws NullPointerException
     *             if content is null
     */
    public synchronized void addAssistantMessage(String content) {
        Objects.requireNonNull(content, "Content cannot be null");
        messages.add(Message.assistant(content));
        messageTimestamps.add(clock.instant());
        markDirty();
    }

    /**
     * Adds a message to the conversation.
     *
     * @param message
     *            The message to add (must not be null)
     * @throws NullPointerException
     *             if message is null
     */
    public synchronized void addMessage(Message message) {
        Objects.requireNonNull(message, "Message cannot be null");
        messages.add(message);
        messageTimestamps.add(clock.instant());
        markDirty();
    }

    /**
     * Gets all messages in the conversation.
     *
     * <p>
     * Returns an immutable copy to prevent external modification.
     *
     * @return An immutable list of messages (never null, may be empty)
     */
    public synchronized List<Message> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /**
     * Gets the number of messages in the conversation.
     *
     * @return The message count
     */
    public synchronized int size() {
        return messages.size();
    }

    /**
     * Checks if the conversation is empty.
     *
     * @return true if no messages exist, false otherwise
     */
    public synchronized boolean isEmpty() {
        return messages.isEmpty();
    }

    /**
     * Atomically replaces all messages with the supplied list.
     *
     * <p>
     * Used by the transcript compaction engine to swap the existing message history with summary messages while
     * preserving the session id and system prompt. Equivalent to calling {@link #clear()} followed by repeated
     * {@link #addMessage} but performed as a single mutation so no intermediate state is observable.
     *
     * <p>
     * The supplied list is defensively copied; later mutations of the source list are not reflected.
     *
     * @param newMessages
     *            the new message list (must not be null; may be empty)
     * @throws NullPointerException
     *             if {@code newMessages} is null or contains null elements
     */
    public synchronized void replaceWith(List<Message> newMessages) {
        Objects.requireNonNull(newMessages, "newMessages cannot be null");
        for (Message message : newMessages) {
            Objects.requireNonNull(message, "newMessages must not contain null elements");
        }
        // Caller must serialize access (see class-level "Thread safety" Javadoc). The clear+addAll pair is logically
        // atomic from the perspective of the only legal observer (the ReAct loop driving the conversation), since no
        // other thread is allowed to mutate the list concurrently.
        messages.clear();
        messages.addAll(newMessages);
        messageTimestamps.clear();
        if (!newMessages.isEmpty()) {
            final Instant now = clock.instant();
            for (int i = 0; i < newMessages.size(); i++) {
                messageTimestamps.add(now);
            }
        }
        // The history the mark counted is gone, so the mark cannot survive it. Compaction typically leaves far fewer
        // messages than there were, and a count that is no longer a position in the transcript does more than rewind
        // to the wrong place: it is validated where the transcript is rebuilt, so the end-of-turn persist would throw
        // into saveSilently, which swallows it, and the whole turn's history would be dropped in silence. Losing the
        // ability to retry this one turn is the honest price.
        rewindPoint = null;
        markDirty();
    }

    /**
     * Replaces the message at the given index in place, preserving its existing timestamp.
     *
     * <p>
     * Used by {@link at.aimon.core.agent.compact.TimeBasedMicrocompact} to rewrite a single message (typically a tool
     * result whose content is being scrubbed) without disturbing the timestamp side-channel. The original timestamp is
     * intentionally retained so that subsequent microcompact passes can recognise the slot as already aged out and skip
     * it as idempotent.
     *
     * @param index
     *            zero-based index of the message to replace
     * @param newMessage
     *            replacement message (must not be null)
     * @throws IndexOutOfBoundsException
     *             if {@code index} is negative or {@code >= size()}
     * @throws NullPointerException
     *             if {@code newMessage} is null
     */
    public synchronized void replaceMessageAt(int index, Message newMessage) {
        Objects.requireNonNull(newMessage, "newMessage cannot be null");
        if (index < 0 || index >= messages.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + messages.size());
        }
        messages.set(index, newMessage);
        markDirty();
    }

    /**
     * Returns an unmodifiable snapshot of the per-message timestamps.
     *
     * <p>
     * The returned list is index-aligned with {@link #getMessages()}: element {@code i} is the {@link Clock#instant()}
     * captured when message {@code i} was added (or rewritten via {@link #replaceWith(List)}, which records the
     * compaction time uniformly across all replacement messages). The list is a defensive copy and will not reflect
     * subsequent mutations.
     *
     * @return an unmodifiable list of timestamps (never null, may be empty)
     */
    public synchronized List<Instant> getMessageTimestamps() {
        return Collections.unmodifiableList(new ArrayList<>(messageTimestamps));
    }

    /**
     * Clears all messages and system prompt from the conversation.
     *
     * <p>
     * This method resets the conversation to a completely empty state, removing both the message history and the system
     * prompt. The system prompt will be automatically re-initialized on the next agent execution.
     */
    public synchronized void clear() {
        systemPrompt = null;
        rewindPoint = null;
        messages.clear();
        messageTimestamps.clear();
        markDirty();
    }

    /**
     * Gets the last message in the conversation.
     *
     * @return The last message, or null if conversation is empty
     */
    public synchronized Message getLastMessage() {
        if (messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1);
    }

    /**
     * Gets the last N messages from the conversation.
     *
     * <p>
     * If N is greater than the number of messages, returns all messages.
     *
     * @param count
     *            The number of messages to retrieve
     * @return An immutable list of the last N messages (never null, may be empty)
     * @throws IllegalArgumentException
     *             if count is negative
     */
    public synchronized List<Message> getLastMessages(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }

        if (count == 0 || messages.isEmpty()) {
            return List.of();
        }

        int fromIndex = Math.max(0, messages.size() - count);
        return Collections.unmodifiableList(new ArrayList<>(messages.subList(fromIndex, messages.size())));
    }

    /**
     * Gets the number of user messages in the conversation.
     *
     * @return The count of user messages
     */
    public synchronized int countUserMessages() {
        return (int) messages.stream().filter(m -> m.getRole() == Role.USER).count();
    }

    /**
     * Gets the number of assistant messages in the conversation.
     *
     * @return The count of assistant messages
     */
    public synchronized int countAssistantMessages() {
        return (int) messages.stream().filter(m -> m.getRole() == Role.ASSISTANT).count();
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
     * Checks if a system prompt is set.
     *
     * @return true if a system prompt exists, false otherwise
     */
    public synchronized boolean hasSystemPrompt() {
        return systemPrompt != null;
    }

    /**
     * Gets the system prompt.
     *
     * @return The system prompt (can be null)
     */
    public synchronized String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Sets the system prompt.
     *
     * @param systemPrompt
     *            The system prompt (can be null)
     */
    public synchronized void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        markDirty();
    }

    /**
     * Converts this buffer to an immutable {@link SessionSnapshot}.
     *
     * <p>
     * Creates a snapshot capturing the current state of the transcript, including the system prompt and all messages.
     * The snapshot is immutable and will not reflect any future changes to this context.
     *
     * <p>
     * If the system prompt is null (e.g., after {@link #clear()}), an empty string will be used. The system prompt will
     * be re-initialized on the next agent execution.
     *
     * @return A new immutable SessionSnapshot
     */
    public synchronized SessionSnapshot toSnapshot() {
        // Allow null systemPrompt - use empty string as fallback
        // System prompt will be re-initialized on next agent execution
        return SessionSnapshot.of(sessionId, systemPrompt, Collections.unmodifiableList(new ArrayList<>(messages)),
                rewindPoint);
    }

    /**
     * Creates a new mutable transcript buffer from an immutable snapshot.
     *
     * <p>
     * The new context is independent of the snapshot and can be modified without affecting the original snapshot.
     *
     * @param snapshot
     *            The snapshot to convert (must not be null)
     * @return A new mutable TranscriptBuffer
     * @throws NullPointerException
     *             if snapshot is null
     */
    public static TranscriptBuffer fromSnapshot(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Snapshot cannot be null");
        final TranscriptBuffer buffer = new TranscriptBuffer(snapshot.getSessionId(), snapshot.getSystemPrompt(),
                snapshot.getConversationHistory());
        buffer.rewindPoint = snapshot.getRewindPoint().orElse(null);
        return buffer;
    }

    /**
     * Records that a turn is starting here, so it can be taken back out if it ends interrupted.
     *
     * <p>
     * Called before the turn adds anything — including the synthetic context blocks that precede the real user
     * message, which belong to the turn and must go with it. Any point held from an earlier turn is replaced: only
     * the most recent turn is ever retryable.
     *
     * <p>
     * Takes the {@link UserInput} the turn was submitted with rather than the message the executor built from it, so
     * that a retry re-submits the request instead of reconstructing one from its rendering — see
     * {@link SessionRewindPoint}.
     *
     * @param userInput
     *            the input the turn was submitted with (must not be null)
     * @param submitOptions
     *            the per-turn options it was submitted under (must not be null; use {@link SubmitOptions#empty()}
     *            when there were none)
     * @throws NullPointerException
     *             if either argument is null
     */
    public synchronized void beginTurn(UserInput userInput, SubmitOptions submitOptions) {
        rewindPoint = SessionRewindPoint.of(messages.size(), userInput, submitOptions);
    }

    /**
     * Drops the rewind point, marking the turn as one there is nothing to go back from.
     *
     * <p>
     * Called when a turn ends any way other than interrupted. A turn that <em>was</em> interrupted simply leaves the
     * point in place, which is what makes it retryable — so this is the common case and the interrupted one is the
     * exception, not the other way round.
     */
    public synchronized void endTurn() {
        rewindPoint = null;
    }

    /**
     * Returns where the current or last turn began, when that turn is one that can be rewound.
     *
     * @return the rewind point, or empty when there is nothing to go back to (never null)
     */
    public synchronized Optional<SessionRewindPoint> getRewindPoint() {
        return Optional.ofNullable(rewindPoint);
    }

    /**
     * Takes an interrupted turn back out of the buffer: its messages are dropped and the rewind point with them.
     *
     * <p>
     * A mutation of the history like any other, so it bumps the version and notifies the dirty listener — unlike
     * {@link #beginTurn(UserInput, SubmitOptions)} and {@link #endTurn()}, which only bookkeep.
     *
     * @return the point that describes the turn, for the caller to submit it again, or empty when there was nothing
     *         to rewind (never null)
     */
    public synchronized Optional<SessionRewindPoint> rewind() {
        if (rewindPoint == null) {
            return Optional.empty();
        }
        final SessionRewindPoint rewound = rewindPoint;
        final int keep = rewindPoint.getMessageCount();
        rewindPoint = null;
        while (messages.size() > keep) {
            final int last = messages.size() - 1;
            messages.remove(last);
            messageTimestamps.remove(last);
        }
        markDirty();
        return Optional.of(rewound);
    }

    /**
     * Returns the current mutation version. Bumped by every mutator method. Used by
     * readers to detect that the memory changed without snapshotting the message list. Stage 4 of the session-first
     * restructure promotes it to the record's fencing token.
     *
     * @return the version counter (monotonically non-decreasing across the memory's lifetime)
     */
    public synchronized long getVersion() {
        return version;
    }

    /**
     * Attaches (or replaces) the dirty listener invoked after every mutation. Pass {@code null} to detach.
     *
     * <p>
     * The listener is invoked while holding the memory's intrinsic lock; long-running work must be deferred to
     * another thread. This setter is itself {@code synchronized} on the same lock so the
     * attach/detach happens-before any subsequent {@link #markDirty()} call from a mutator — callers do not need to
     * provide external synchronization to avoid missed notifications.
     *
     * @param listener
     *            the listener to attach, or {@code null} to detach
     */
    public synchronized void setDirtyListener(DirtyListener listener) {
        this.dirtyListener = listener;
    }

    private void markDirty() {
        version++;
        final DirtyListener l = dirtyListener;
        if (l != null) {
            l.onMutate(this);
        }
    }

    @Override
    public synchronized String toString() {
        long userCount = messages.stream().filter(m -> m.getRole() == Role.USER).count();
        long assistantCount = messages.stream().filter(m -> m.getRole() == Role.ASSISTANT).count();
        return "TranscriptBuffer{" + "hasSystemPrompt=" + (systemPrompt != null) + ", messages=" + messages.size()
                + ", user=" + userCount + ", assistant=" + assistantCount + ", version=" + version + "}";
    }
}
