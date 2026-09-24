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
 * The append-oriented, mutable session log of one session, as a running turn sees it.
 *
 * <p>
 * Holds the log entries the record carries — each a message, its seq and its {@link LogOrigin} — along with an
 * optional system prompt for persistence. Entries are addressed by seq, which is never reused (see
 * {@link SessionLogState}); {@link #getMessages()} is the messages of those entries, in seq order.
 *
 * <p>
 * <b>Origin.</b> {@link #addMessage(Message)} and the other plain appenders record
 * {@link LogOrigin#CONVERSATION}. Anything the runtime puts into the log on its own — the user-context block, an
 * assembled reminder, hook feedback, a command's reply, a file list re-attached after compaction — goes through
 * {@link #addMessage(Message, LogOrigin)} with {@link LogOrigin#SYNTHETIC}, so that readers of the log as <em>what was
 * said</em> can tell the two apart.
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
    private final List<SessionLogEntry> entries;
    private final List<Instant> messageTimestamps;
    private long nextSeq;
    private long floorSeq;
    private SessionLogFormat format = SessionLogFormat.V1;
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
     * The seq the current execution's first entry received, or {@code -1} when there is no usable mark.
     *
     * <p>
     * The counterpart of {@link #rewindPoint} for the memory ingest path, and a seq for the same reason:
     * {@code Message} carries no stable id, so "which messages are new" has to be an address in the log. It is still
     * vulnerable to one thing — {@link #replaceWith(List)} rewrites the log under it, and the entries after the mark
     * are then the rewrite, not this execution's messages. So the mark is dropped there, exactly as the rewind point
     * is, and {@link #messagesSinceIngestMark()} answers empty rather than guessing.
     *
     * <p>
     * Node-local and not persisted. It is set and read within one execution, in one process, so there is nothing for a
     * restart to restore: a resumed session marks again on its next execution. Persisting it would mean widening the
     * session record's wire format for a value whose whole life is shorter than one save.
     *
     * <p>
     * Like the rewind point, it neither bumps {@link #getVersion()} nor notifies the dirty listener — it says nothing
     * about the LLM-visible history.
     */
    private long ingestMark = -1;

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
     * Creates a defensive copy of the provided messages list. The messages become a version-1 log — seqs
     * {@code 0..n-1}, every entry {@link LogOrigin#CONVERSATION}. Each initial message receives the current clock
     * instant as
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
        this.entries = new ArrayList<>(messages.size());
        this.messageTimestamps = new ArrayList<>(messages.size());
        final Instant now = clock.instant();
        for (Message message : messages) {
            this.entries.add(SessionLogEntry.of(nextSeq++, message, LogOrigin.CONVERSATION));
            this.messageTimestamps.add(now);
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
        append(Message.user(content), LogOrigin.CONVERSATION);
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
        append(Message.assistant(content), LogOrigin.CONVERSATION);
    }

    /**
     * Adds a message to the conversation, as {@link LogOrigin#CONVERSATION}.
     *
     * @param message
     *            The message to add (must not be null)
     * @throws NullPointerException
     *             if message is null
     */
    public synchronized void addMessage(Message message) {
        addMessage(message, LogOrigin.CONVERSATION);
    }

    /**
     * Appends a message to the log with an explicit origin.
     *
     * <p>
     * Runtime injections — anything the conversation did not say — pass {@link LogOrigin#SYNTHETIC}. The model sees
     * them like any other message; readers of the log as a record of the conversation skip them.
     *
     * @param message
     *            The message to add (must not be null)
     * @param origin
     *            where the message came from (must not be null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public synchronized void addMessage(Message message, LogOrigin origin) {
        Objects.requireNonNull(message, "Message cannot be null");
        Objects.requireNonNull(origin, "Origin cannot be null");
        append(message, origin);
    }

    private void append(Message message, LogOrigin origin) {
        entries.add(SessionLogEntry.of(nextSeq++, message, origin));
        messageTimestamps.add(clock.instant());
        markDirty();
    }

    /**
     * Gets the messages of the log entries this buffer holds, in seq order.
     *
     * <p>
     * That is the part of the session log held in the record — until sealing moves part of the log out of the record,
     * every message of the session. It is not the LLM view: what the model is sent is decided by
     * {@link at.aimon.core.agent.context.ContextEngine}. Returns an immutable copy to prevent external modification.
     *
     * @return An immutable list of messages (never null, may be empty)
     */
    public synchronized List<Message> getMessages() {
        final List<Message> messages = new ArrayList<>(entries.size());
        for (SessionLogEntry entry : entries) {
            messages.add(entry.getMessage());
        }
        return Collections.unmodifiableList(messages);
    }

    /**
     * Gets the number of messages this buffer holds.
     *
     * @return The message count
     */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * Checks if this buffer holds no messages.
     *
     * @return true if no messages exist, false otherwise
     */
    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Returns whether the session has a live conversation user message — a {@link LogOrigin#CONVERSATION} entry with
     * the user role.
     *
     * <p>
     * This is the "is this session being resumed" question. Synthetic user messages do not answer it, and neither
     * does the number of seqs handed out: a new session whose first turn was interrupted and rewound has used seqs
     * but holds no conversation.
     *
     * @return true if a live conversation user message exists
     * @see SessionLogState#hasConversation()
     */
    public synchronized boolean hasConversation() {
        for (SessionLogEntry entry : entries) {
            if (entry.getOrigin() == LogOrigin.CONVERSATION && entry.getMessage() != null
                    && entry.getMessage().getRole() == Role.USER) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns how many entries of the log are still alive — neither cut by a rewind nor removed by {@link #clear()}.
     *
     * @return the live entry count (never negative)
     * @see SessionLogState#liveEntryCount()
     */
    public synchronized int liveEntryCount() {
        return entries.size();
    }

    /**
     * Returns the seq the next appended entry will receive.
     *
     * @return the next seq (never negative)
     */
    public synchronized long getNextSeq() {
        return nextSeq;
    }

    /**
     * Returns the format this buffer's log must at least be written in.
     *
     * @return the format (never null)
     */
    public synchronized SessionLogFormat getFormat() {
        return format;
    }

    /**
     * Requires this buffer's log to be written in at least {@code minimum} from now on. Never lowers the format.
     *
     * <p>
     * Bookkeeping only — no version bump, no dirty notification — like {@link #beginTurn}: nothing the model sees
     * changes, and the next save writes the format anyway.
     *
     * @param minimum
     *            the format to require (must not be null)
     */
    public synchronized void requireFormat(SessionLogFormat minimum) {
        this.format = format.atLeast(Objects.requireNonNull(minimum, "minimum cannot be null"));
    }

    /**
     * Returns the log this buffer holds as one immutable value — entries, seqs, rewind point, format.
     *
     * @return the log state (never null)
     */
    public synchronized SessionLogState getLogState() {
        return SessionLogState.builder().entries(entries).nextSeq(nextSeq).floorSeq(floorSeq).rewindPoint(rewindPoint)
                .format(format).build();
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
     * <p>
     * <b>This rewrites the log.</b> The replacement entries receive fresh seqs from {@code nextSeq} — seqs are never
     * reused — and {@code floorSeq} rises to the first of them, since nothing before survives. A replacement message
     * that is the very instance of an entry being replaced keeps that entry's origin, matched in order; every other
     * one is {@link LogOrigin#CONVERSATION}. This is the in-place behaviour of the version-1 write mode.
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
        final List<SessionLogEntry> replaced = new ArrayList<>(entries);
        entries.clear();
        messageTimestamps.clear();
        floorSeq = nextSeq;
        final Instant now = clock.instant();
        int cursor = 0;
        for (Message message : newMessages) {
            LogOrigin origin = LogOrigin.CONVERSATION;
            for (int i = cursor; i < replaced.size(); i++) {
                if (replaced.get(i).getMessage() == message) {
                    origin = replaced.get(i).getOrigin();
                    cursor = i + 1;
                    break;
                }
            }
            entries.add(SessionLogEntry.of(nextSeq++, message, origin));
            messageTimestamps.add(now);
        }
        // The entries the point addressed are gone, so the point cannot survive them. It now lies below floorSeq, which
        // is not a position in this log: it is validated where the transcript is rebuilt, so the end-of-turn persist
        // would throw into saveSilently, which swallows it, and the whole turn's history would be dropped in silence.
        // Losing the ability to retry this one turn is the honest price.
        rewindPoint = null;
        // Same reasoning, different consequence: every rewritten entry now has a seq above the ingest mark, so a delta
        // taken against it would re-send the compaction summary as if it were new conversation. The execution that
        // was rewritten forgoes its ingest and the next one marks afresh.
        ingestMark = -1;
        markDirty();
    }

    /**
     * Replaces the message at the given index in place, preserving its existing timestamp.
     *
     * <p>
     * Used by {@link at.aimon.core.agent.compact.TimeBasedMicrocompact} to rewrite a single message (typically a tool
     * result whose content is being scrubbed) without disturbing the timestamp side-channel. The entry keeps its seq
     * and origin. The original timestamp is
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
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + entries.size());
        }
        entries.set(index, entries.get(index).withMessage(newMessage));
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
     *
     * <p>
     * The log's seqs are not reset: {@code floorSeq} rises to {@code nextSeq} and the next entry continues from there
     * (see {@link SessionLogState#clear()}).
     */
    public synchronized void clear() {
        systemPrompt = null;
        rewindPoint = null;
        ingestMark = -1;
        entries.clear();
        messageTimestamps.clear();
        floorSeq = nextSeq;
        markDirty();
    }

    /**
     * Gets the last message in the conversation.
     *
     * @return The last message, or null if conversation is empty
     */
    public synchronized Message getLastMessage() {
        if (entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1).getMessage();
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

        if (count == 0 || entries.isEmpty()) {
            return List.of();
        }

        final List<Message> messages = getMessages();
        int fromIndex = Math.max(0, messages.size() - count);
        return Collections.unmodifiableList(new ArrayList<>(messages.subList(fromIndex, messages.size())));
    }

    /**
     * Gets the number of user messages in the conversation.
     *
     * @return The count of user messages
     */
    public synchronized int countUserMessages() {
        return (int) entries.stream().map(SessionLogEntry::getMessage).filter(m -> m.getRole() == Role.USER).count();
    }

    /**
     * Gets the number of assistant messages in the conversation.
     *
     * @return The count of assistant messages
     */
    public synchronized int countAssistantMessages() {
        return (int) entries.stream().map(SessionLogEntry::getMessage).filter(m -> m.getRole() == Role.ASSISTANT)
                .count();
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
     * Creates a snapshot capturing the current state of the transcript: the system prompt and the whole log, as one
     * {@link SessionLogState}. The snapshot is immutable and will not reflect any future changes to this context.
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
        return SessionSnapshot.fromLog(sessionId, systemPrompt, getLogState());
    }

    /**
     * Creates a new mutable transcript buffer from an immutable snapshot.
     *
     * <p>
     * The new context is independent of the snapshot and can be modified without affecting the original snapshot. The
     * snapshot's log is adopted whole: entries with their seqs and origins, {@code nextSeq}, {@code floorSeq}, the
     * rewind point and the format.
     *
     * @param snapshot
     *            The snapshot to convert (must not be null)
     * @return A new mutable TranscriptBuffer
     * @throws NullPointerException
     *             if snapshot is null
     */
    public static TranscriptBuffer fromSnapshot(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Snapshot cannot be null");
        final SessionLogState state = snapshot.getLogState();
        final TranscriptBuffer buffer = new TranscriptBuffer(snapshot.getSessionId(), snapshot.getSystemPrompt(),
                List.of(), Clock.systemUTC());
        final Instant now = buffer.clock.instant();
        for (SessionLogEntry entry : state.getEntries()) {
            buffer.entries.add(entry);
            buffer.messageTimestamps.add(now);
        }
        buffer.nextSeq = state.getNextSeq();
        buffer.floorSeq = state.getFloorSeq();
        buffer.format = state.getFormat();
        buffer.rewindPoint = state.getRewindPoint().orElse(null);
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
        rewindPoint = SessionRewindPoint.of(nextSeq, userInput, submitOptions);
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
     * Marks the current end of the history as the point an execution's own messages start after.
     *
     * <p>
     * Called at the top of an execution, before it adds anything. Bookkeeping only: no version bump, no dirty
     * notification.
     */
    public synchronized void markIngestPoint() {
        ingestMark = nextSeq;
    }

    /**
     * Returns the messages added since {@link #markIngestPoint()}, or empty when there is no usable mark.
     *
     * <p>
     * Empty means one of three things, and the caller treats them the same way — send nothing:
     *
     * <ul>
     * <li>no mark was set (this buffer is not being driven by an execution that marks);
     * <li>the history was rewritten by {@link #replaceWith(List)} — compaction, or a prompt-size recovery — so the
     * mark no longer points anywhere;
     * <li>the execution added nothing.
     * </ul>
     *
     * <p>
     * Skipping the middle case is deliberate and costs one execution's messages. Sending everything instead would
     * re-send what was already ingested, and a memory backend that de-duplicates on write has still paid for the
     * extraction by then; sending the compaction summary would feed a paraphrase in as if it were conversation. The
     * next execution marks again and the stream resumes.
     *
     * @return the new messages in order, or an empty list (never null)
     */
    public synchronized List<Message> messagesSinceIngestMark() {
        if (ingestMark < 0) {
            return List.of();
        }
        final List<Message> since = new ArrayList<>();
        for (SessionLogEntry entry : entries) {
            if (entry.getSeq() >= ingestMark) {
                since.add(entry.getMessage());
            }
        }
        return List.copyOf(since);
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
        final long from = rewindPoint.getSeq();
        rewindPoint = null;
        while (!entries.isEmpty() && entries.get(entries.size() - 1).getSeq() >= from) {
            final int last = entries.size() - 1;
            entries.remove(last);
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
        return "TranscriptBuffer{" + "hasSystemPrompt=" + (systemPrompt != null) + ", messages=" + entries.size()
                + ", user=" + countUserMessages() + ", assistant=" + countAssistantMessages() + ", nextSeq=" + nextSeq
                + ", version=" + version + "}";
    }
}
