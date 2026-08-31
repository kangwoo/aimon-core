package at.aimon.core.agent.session.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.SessionTranscript;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.llm.Message;

/**
 * The durable session aggregate, identified by a {@link SessionId}.
 *
 * <p>
 * It carries two unrelated halves. The first is the LLM-visible history — system prompt plus messages — held as one
 * immutable {@link SessionTranscript}. The second is bookkeeping written by different owners at different times:
 * {@code compactionFailureCount} (the compaction circuit breaker), {@code agentRef} (the agent binding, sole-written
 * by {@code SessionRouter}), {@code sessionTotals}, and {@code budgetOverride}. Only the transcript half
 * crosses the wire — see {@link SessionSnapshot}, which is the sole owner of the persisted format.
 *
 * <p>
 * <strong>This is not the live history of a running turn.</strong> A turn appends to {@link TranscriptBuffer},
 * which keeps a mutable list precisely because it is the append hot path, and flushes here through
 * {@link SessionSnapshot}. This type has no append method at all: a history is supplied whole, either as a
 * {@link SessionTranscript} or as a {@code List<Message>} the constructor copies.
 *
 * <p>
 * <strong>Note:</strong> This class is not thread-safe. If concurrent access is required, external synchronization must
 * be provided. (The transcript it holds <em>is</em> immutable, so a reference read out of
 * {@link #getTranscript()} is safe to share.)
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Empty, no system prompt
 *     SessionRecord record = new SessionRecord(SessionId.of("c-1"));
 *
 *     // With a system prompt
 *     SessionRecord prompted = new SessionRecord(SessionId.of("c-2"), "You are a helpful assistant.");
 *
 *     // With a starting history — the preferred way to build one
 *     SessionTranscript transcript = SessionTranscript.of("You are a helpful assistant.",
 *             List.of(Message.user("Hello"), Message.assistant("Hi! How can I help you?")));
 *     SessionRecord restored = new SessionRecord(SessionId.of("c-3"), transcript, 0, "agent-a",
 *             SessionTotals.empty(), null);
 *
 *     // Read the history back (immutable snapshot)
 *     List<Message> history = restored.getMessages();
 * }
 * </pre>
 */
public class SessionRecord implements SessionRecordView {

    private final SessionId id;

    /**
     * The LLM-visible history. Held as one immutable value rather than a {@code systemPrompt} field plus a mutable
     * list, so that copies can share it by reference instead of duplicating the message list (see
     * {@link SessionTranscript}). {@link #setSystemPrompt(String)} replaces this reference; nothing mutates
     * through it, so a reference handed out by {@link #getTranscript()} stays valid.
     */
    private SessionTranscript transcript;

    private int compactionFailureCount;
    private String agentRef;
    private SessionTotals sessionTotals;
    private ExecutionBudget budgetOverride;

    /**
     * Creates a new empty session record without a system prompt.
     *
     * @param id
     *            The session id (must not be null)
     */
    public SessionRecord(SessionId id) {
        this(id, null);
    }

    /**
     * Creates a new empty session record with a system prompt.
     *
     * @param id
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt (can be null)
     */
    public SessionRecord(SessionId id, String systemPrompt) {
        this(id, systemPrompt, List.of());
    }

    /**
     * Creates a new session record with a system prompt and initial messages.
     *
     * <p>
     * Creates a defensive copy of the provided messages list.
     *
     * @param id
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt (can be null)
     * @param messages
     *            The initial messages (must not be null, but can be empty)
     */
    public SessionRecord(SessionId id, String systemPrompt, List<Message> messages) {
        this(id, systemPrompt, messages, 0);
    }

    /**
     * Creates a new session record with a system prompt, initial messages, and a starting compaction failure count.
     *
     * <p>
     * Used by the persistence layer to rehydrate the per-session circuit-breaker counter consumed by
     * {@code at.aimon.core.agent.compact.CompactionFailureStore} implementations. Production callers normally pass
     * {@code 0}.
     *
     * @param id
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt (can be null)
     * @param messages
     *            The initial messages (must not be null, but can be empty)
     * @param compactionFailureCount
     *            Persisted consecutive AUTO compaction failures (must be {@code >= 0})
     * @throws NullPointerException
     *             if id or messages is null
     * @throws IllegalArgumentException
     *             if {@code compactionFailureCount} is negative
     */
    public SessionRecord(SessionId id, String systemPrompt, List<Message> messages, int compactionFailureCount) {
        this(id, systemPrompt, messages, compactionFailureCount, null);
    }

    /**
     * Creates a new session record with all fields including the agent binding.
     *
     * <p>
     * Used by the persistence layer to rehydrate an already-bound session, and by
     * {@code at.aimon.core.agent.session.store.InMemorySessionRecordStore} for deep-copy on save/load.
     *
     * @param id
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt (can be null)
     * @param messages
     *            The initial messages (must not be null, but can be empty)
     * @param compactionFailureCount
     *            Persisted consecutive AUTO compaction failures (must be {@code >= 0})
     * @param agentRef
     *            The bound agent reference (can be null when not yet bound)
     * @throws NullPointerException
     *             if id or messages is null
     * @throws IllegalArgumentException
     *             if {@code compactionFailureCount} is negative
     */
    public SessionRecord(SessionId id, String systemPrompt, List<Message> messages, int compactionFailureCount,
            String agentRef) {
        this(id, systemPrompt, messages, compactionFailureCount, agentRef, SessionTotals.empty(), null);
    }

    /**
     * Creates a new session record with all fields including the restart-durable side fields.
     *
     * <p>
     * Used by the persistence layer to rehydrate the cumulative {@code sessionTotals} and the runtime
     * {@code budgetOverride} alongside the message history, and by
     * {@code at.aimon.core.agent.session.store.InMemorySessionRecordStore} for deep-copy on save/load. The two side
     * fields are intentionally excluded from {@code SessionSnapshot}; they are preserved across message saves via
     * {@code SessionRecordStore#mergeFromSnapshot} and updated through dedicated atomic primitives.
     *
     * @param id
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt (can be null)
     * @param messages
     *            The initial messages (must not be null, but can be empty)
     * @param compactionFailureCount
     *            Persisted consecutive AUTO compaction failures (must be {@code >= 0})
     * @param agentRef
     *            The bound agent reference (can be null when not yet bound)
     * @param sessionTotals
     *            The persisted cumulative session totals (must not be null; use
     *            {@link SessionTotals#empty()})
     * @param budgetOverride
     *            The persisted runtime budget override (can be null when no override is recorded)
     * @throws NullPointerException
     *             if id, messages, or sessionTotals is null
     * @throws IllegalArgumentException
     *             if {@code compactionFailureCount} is negative
     */
    public SessionRecord(SessionId id, String systemPrompt, List<Message> messages, int compactionFailureCount,
            String agentRef, SessionTotals sessionTotals, ExecutionBudget budgetOverride) {
        Objects.requireNonNull(id, "Id cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(sessionTotals, "sessionTotals cannot be null");
        if (compactionFailureCount < 0) {
            throw new IllegalArgumentException("compactionFailureCount must be >= 0, got: " + compactionFailureCount);
        }
        this.id = id;
        this.transcript = SessionTranscript.of(systemPrompt, messages);
        this.compactionFailureCount = compactionFailureCount;
        this.agentRef = agentRef;
        this.sessionTotals = sessionTotals;
        this.budgetOverride = budgetOverride;
    }

    /**
     * Creates a new session record around an already-built {@link SessionTranscript}.
     *
     * <p>
     * The transcript is immutable and is adopted by reference — no message list is copied. This is the constructor
     * to prefer when the history is being handed between records that must not observe each other's
     * mutations (repository save/load deep copies, {@link #copyOf(SessionRecordView)}), because independence comes
     * from the transcript's immutability rather than from defensive copying.
     *
     * @param id
     *            The session id (must not be null)
     * @param transcript
     *            The system prompt and message history (must not be null; use {@link SessionTranscript#empty()})
     * @param compactionFailureCount
     *            Persisted consecutive AUTO compaction failures (must be {@code >= 0})
     * @param agentRef
     *            The bound agent reference (can be null when not yet bound)
     * @param sessionTotals
     *            The persisted cumulative session totals (must not be null; use
     *            {@link SessionTotals#empty()})
     * @param budgetOverride
     *            The persisted runtime budget override (can be null when no override is recorded)
     * @throws NullPointerException
     *             if id, transcript, or sessionTotals is null
     * @throws IllegalArgumentException
     *             if {@code compactionFailureCount} is negative
     */
    public SessionRecord(SessionId id, SessionTranscript transcript, int compactionFailureCount, String agentRef,
            SessionTotals sessionTotals, ExecutionBudget budgetOverride) {
        Objects.requireNonNull(id, "Id cannot be null");
        Objects.requireNonNull(transcript, "Transcript cannot be null");
        Objects.requireNonNull(sessionTotals, "sessionTotals cannot be null");
        if (compactionFailureCount < 0) {
            throw new IllegalArgumentException("compactionFailureCount must be >= 0, got: " + compactionFailureCount);
        }
        this.id = id;
        this.transcript = transcript;
        this.compactionFailureCount = compactionFailureCount;
        this.agentRef = agentRef;
        this.sessionTotals = sessionTotals;
        this.budgetOverride = budgetOverride;
    }

    /**
     * Creates a mutable SessionRecord copy of a {@link SessionRecordView}.
     *
     * <p>
     * Used by callers that loaded a read-only view via {@link SessionRecordStore#load(SessionId)} but need a
     * mutable instance for save-path mutations (counter updates, binding changes, message appends). Robust to any
     * {@link SessionRecordStore} implementation since it only relies on the read-only view contract.
     *
     * <p>
     * When the source is itself a {@code SessionRecord} the immutable transcript is shared rather than copied, so
     * this is O(1) in the message count on the hot repository paths. The copy is still fully independent: appends
     * on either side replace that side's transcript reference and leave the other alone.
     *
     * @param view
     *            the view to copy from (must not be null)
     * @return a new mutable SessionRecord with the same field values
     * @throws NullPointerException
     *             if view is null
     */
    public static SessionRecord copyOf(SessionRecordView view) {
        Objects.requireNonNull(view, "View cannot be null");
        final SessionTranscript sourceTranscript = view instanceof SessionRecord record
                ? record.getTranscript()
                : SessionTranscript.of(view.getSystemPrompt(), view.getMessages());
        return new SessionRecord(view.getId(), sourceTranscript, view.getCompactionFailureCount(),
                view.getAgentRef().orElse(null), view.getSessionTotals(), view.getBudgetOverride().orElse(null));
    }

    /**
     * Creates a mutable record carrying a flushed snapshot's transcript.
     *
     * <p>
     * The direction matters. This used to be {@code SessionSnapshot#toSessionRecord()}, which made the transcript
     * package depend on the mutable record and put a second package on the sole-writer allowlist. Constructing the
     * record here keeps the dependency one-way — the store knows the snapshot because {@link
     * SessionRecordStore#mergeFromSnapshot(SessionSnapshot)} already takes one, and the snapshot no longer needs to
     * know the mutable type at all.
     *
     * <p>
     * The side fields come out at their defaults, because a snapshot does not carry them. A caller writing this into
     * a store that may already hold a record must therefore merge rather than overwrite — which is what
     * {@link SessionRecordStore#mergeFromSnapshot(SessionSnapshot)} exists to do.
     *
     * @param snapshot
     *            the snapshot to materialise (must not be null)
     * @return a new mutable SessionRecord holding the snapshot's transcript
     * @throws NullPointerException
     *             if snapshot is null
     */
    public static SessionRecord fromSnapshot(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Snapshot cannot be null");
        final SessionRecord record = new SessionRecord(snapshot.getSessionId(), snapshot.getSystemPrompt(),
                snapshot.getConversationHistory());
        // The rewind point rides the transcript rather than the side fields, so it comes across here instead of being
        // restored from the existing record by the merge. That is the point of where it lives: it counts the messages
        // this snapshot brought, and a merge that kept the previous one would point into a history it did not index.
        record.transcript = record.transcript.withRewindPoint(snapshot.getRewindPoint().orElse(null));
        return record;
    }

    /**
     * Gets the session id.
     *
     * @return The session id (never null)
     */
    public SessionId getId() {
        return id;
    }

    /**
     * Gets where the last turn began, when that turn ended interrupted.
     *
     * <p>
     * Read from the transcript rather than a field of its own, which is what keeps it from outliving the messages it
     * counts.
     *
     * @return the rewind point, or empty when the last turn ended some other way (never null)
     */
    @Override
    public Optional<SessionRewindPoint> getRewindPoint() {
        return transcript.getRewindPoint();
    }

    /**
     * Takes an interrupted turn back out of this record's transcript.
     *
     * @return the point describing the turn, for a caller to submit it again, or empty when the last turn ended some
     *         other way (never null)
     */
    public Optional<SessionRewindPoint> rewind() {
        final Optional<SessionRewindPoint> point = transcript.getRewindPoint();
        if (point.isEmpty()) {
            return Optional.empty();
        }
        transcript = transcript.rewind();
        return point;
    }

    /**
     * Gets the LLM-visible history — system prompt and messages — as one immutable value.
     *
     * <p>
     * Safe to hand to another {@code SessionRecord} or hold across mutations of this one: the mutators replace this
     * record's reference rather than mutating the value.
     *
     * @return the transcript (never null)
     */
    public SessionTranscript getTranscript() {
        return transcript;
    }

    /**
     * Gets the system prompt.
     *
     * @return The system prompt (can be null)
     */
    public String getSystemPrompt() {
        return transcript.getSystemPrompt();
    }

    /**
     * Sets the system prompt.
     *
     * @param systemPrompt
     *            The system prompt (can be null)
     */
    public void setSystemPrompt(String systemPrompt) {
        this.transcript = transcript.withSystemPrompt(systemPrompt);
    }

    /**
     * Gets all messages in the transcript.
     *
     * <p>
     * Returns an immutable point-in-time snapshot: the list cannot be modified by the caller, and subsequent appends
     * to this record do not change a list handed out earlier.
     *
     * @return An immutable list of messages (never null, may be empty)
     */
    public List<Message> getMessages() {
        return transcript.getMessages();
    }

    /**
     * Gets the persisted consecutive AUTO compaction failure count.
     *
     * <p>
     * Used by {@code at.aimon.core.agent.compact.CompactionFailureStore} to share the circuit-breaker counter across
     * processes via the record store. The value is {@code 0} on a freshly created record.
     *
     * @return The compaction failure count (always {@code >= 0})
     */
    public int getCompactionFailureCount() {
        return compactionFailureCount;
    }

    /**
     * Sets the persisted consecutive AUTO compaction failure count.
     *
     * <p>
     * Intended for {@link SessionRecordStore} implementations serving
     * {@link SessionRecordStore#incrementCompactionFailureCount(at.aimon.core.agent.session.SessionId)} and
     * {@link SessionRecordStore#resetCompactionFailureCount(at.aimon.core.agent.session.SessionId)} — that is, called
     * on a copy inside whatever primitive gives the backend its atomicity, never on a record a caller is holding.
     * Application code goes through those two writers instead: the counter is a delta, and a load-mutate-store from
     * outside would drop a concurrent node's increment.
     *
     * @param compactionFailureCount
     *            The new failure count (must be {@code >= 0})
     * @throws IllegalArgumentException
     *             if {@code compactionFailureCount} is negative
     */
    public void setCompactionFailureCount(int compactionFailureCount) {
        if (compactionFailureCount < 0) {
            throw new IllegalArgumentException("compactionFailureCount must be >= 0, got: " + compactionFailureCount);
        }
        this.compactionFailureCount = compactionFailureCount;
    }

    /**
     * Gets the agent binding for this session.
     *
     * <p>
     * The {@code agentRef} identifies which agent (factory key) is bound to this session. Used by
     * {@code SessionRouter} to enforce agent-session affinity (see design §3.6).
     *
     * @return the bound agent reference, or empty if not yet bound
     */
    @Override
    public Optional<String> getAgentRef() {
        return Optional.ofNullable(agentRef);
    }

    /**
     * Sets the agent binding for this session.
     *
     * <p>
     * Sole-writer invariant (design §3.6): reachable only from inside this package, and in production only from
     * {@link SessionRecordStore#provision(SessionId, String)} — which decides "bind if unbound" atomically,
     * under the lease its caller won. No component outside the package can call this: the binding used to be written
     * one module away through a {@code MutableConversationView} downcast, and that view is gone.
     *
     * @param agentRef
     *            the agent reference to bind (may be null to clear the binding)
     */
    public void setAgentRef(String agentRef) {
        this.agentRef = agentRef;
    }

    /**
     * Gets the persisted cumulative session totals for this session.
     *
     * <p>
     * Restored on session open to seed the in-memory accumulator that backs {@code LiveSessionStatus} reporting
     * (completed turns, cumulative iterations, cumulative tokens). Never null; defaults to
     * {@link SessionTotals#empty()}
     * on a freshly created record.
     *
     * @return the cumulative session totals (never null)
     */
    @Override
    public SessionTotals getSessionTotals() {
        return sessionTotals;
    }

    /**
     * Sets the persisted cumulative session totals for this session.
     *
     * <p>
     * Intended for the persistence layer to write the absolute folded totals at end-of-turn. Application code should
     * not
     * use this directly.
     *
     * @param sessionTotals
     *            the new cumulative totals (must not be null)
     * @throws NullPointerException
     *             if {@code sessionTotals} is null
     */
    public void setSessionTotals(SessionTotals sessionTotals) {
        this.sessionTotals = Objects.requireNonNull(sessionTotals, "sessionTotals cannot be null");
    }

    /**
     * Gets the persisted runtime budget override for this session.
     *
     * <p>
     * Recorded only when a runtime {@code setOptions} explicitly changes the {@code ExecutionBudget} (e.g. a REPL
     * {@code /budget} command). When present, it takes priority over the opener-supplied default on session re-open.
     *
     * @return the runtime budget override, or empty if no override is recorded
     */
    @Override
    public Optional<ExecutionBudget> getBudgetOverride() {
        return Optional.ofNullable(budgetOverride);
    }

    /**
     * Sets the persisted runtime budget override for this session.
     *
     * <p>
     * Intended for the persistence layer to write the runtime budget change. Passing {@code null} clears the override
     * (reverting to the opener-supplied default on the next open).
     *
     * @param budgetOverride
     *            the runtime budget override to record (may be null to clear)
     */
    public void setBudgetOverride(ExecutionBudget budgetOverride) {
        this.budgetOverride = budgetOverride;
    }

    @Override
    public String toString() {
        return "SessionRecord{" + "id=" + id + ", hasSystemPrompt=" + (transcript.getSystemPrompt() != null)
                + ", messages=" + transcript.size() + ", compactionFailureCount=" + compactionFailureCount
                + ", agentRef=" + agentRef + ", sessionTotals=" + sessionTotals + ", budgetOverride=" + budgetOverride
                + "}";
    }
}
