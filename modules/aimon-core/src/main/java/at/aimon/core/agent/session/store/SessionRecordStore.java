package at.aimon.core.agent.session.store;

import java.util.List;
import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * Storage for session records. Implementations can use different backends (memory, database, ...).
 *
 * <h2>There is no "write the record" operation, and that is the design</h2>
 *
 * <p>
 * A record has four owners writing four different concerns, and no writer holds all of them: the checkpoint writer
 * thread owns the messages, the claim path owns {@code agentRef}, the session owns the
 * {@code sessionTotals} / {@code budgetOverride} pair, and the compaction guard owns
 * {@code compactionFailureCount}. So every write here is a <b>partial</b> write that preserves what it does not own:
 *
 * <ul>
 * <li>{@link #mergeFromSnapshot(SessionSnapshot)} — the messages
 * <li>{@link #provision(SessionId, String)} — the record's existence, and its binding when unbound
 * <li>{@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget)} — the session's pair
 * <li>{@link #incrementCompactionFailureCount(SessionId)} / {@link #resetCompactionFailureCount(SessionId)} — the
 * compaction guard's counter
 * </ul>
 *
 * <p>
 * A single full-record write — the shape this SPI used to offer as {@code save(SessionRecord)} — cannot do that. To
 * write the whole record a caller must first read the fields it does not own and hand them back unchanged, which turns
 * every write into a read-modify-write over <em>other</em> writers' fields and reopens exactly the lost-update window
 * the primitives above close. It had no production caller and it is gone.
 *
 * <p>
 * That is also why none of them has a {@code default} implementation. A default could only be written in terms of
 * load-mutate-store, i.e. non-atomically, and a backend author who implemented the obvious members and inherited the
 * rest would silently get the losing behaviour. Each is abstract so the compiler asks the question the javadoc used to
 * only shout about: implement this with whatever atomic primitive you have.
 *
 * <p>
 * Reads return {@link SessionRecordView}, never the mutable {@link SessionRecord} — see {@link #load(SessionId)}.
 * When reached through {@code SessionStore.records()} every write above is additionally fenced against the lease
 * this node holds.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SessionRecordStore repository = new InMemorySessionRecordStore();
 *     SessionId id = SessionId.of("conv-123");
 *
 *     // Establish the record, then write the messages into it
 *     repository.provision(id);
 *     repository.mergeFromSnapshot(memory.toSnapshot());
 *
 *     // Load (read-only view)
 *     Optional<SessionRecordView> loaded = repository.load(id);
 *     loaded.ifPresent(conv -> System.out.println("Messages: " + conv.getMessages().size()));
 *
 *     // List all sessions
 *     List<SessionId> ids = repository.listSessionIds();
 * }
 * </pre>
 */
public interface SessionRecordStore {

    /**
     * Atomically merges the message state from {@code snapshot} into the persisted record while preserving "side"
     * fields, none of which a {@link SessionSnapshot} represents — {@code compactionFailureCount} (written by the
     * compaction guard through {@link #incrementCompactionFailureCount(SessionId)} and
     * {@link #resetCompactionFailureCount(SessionId)}),
     * {@code agentRef} (established by {@link #provision(SessionId, String)}), and the pair
     * {@code sessionTotals} / {@code budgetOverride} (written by the live session that runs the session's turns,
     * through {@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget)}). If no record
     * exists yet, a fresh record is created from the snapshot, with every side field at its default.
     *
     * <p>
     * That the snapshot cannot carry a side field is what makes this method's preservation step trustworthy: there is
     * no second, stale value of the field arriving alongside the messages for the merge to have to choose between.
     *
     * <p>
     * This is the primary write path for the ReAct loop end-of-turn save and for mid-turn checkpoints: callers
     * persist a fresh message-history snapshot and rely on the repository to coordinate with parallel side-field
     * updates from other components.
     *
     * <p>
     * The companion atomic primitives — {@link #provision(SessionId, String)} and
     * {@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget)} — let side-field writers
     * update their fields without touching the message body, so a properly implemented backend gives write-path
     * atomicity across every concern (messages, agent binding, session totals, budget override).
     *
     * @implSpec <b>Atomicity contract.</b> Read + merge + write must be a single operation, mutually exclusive against
     *           the other writes on this interface for the same {@link SessionId} — UPSERT inside a transaction,
     *           {@link java.util.concurrent.ConcurrentMap#compute}, optimistic locking, whatever the backend has. There
     *           is deliberately no {@code default} to inherit: the only default expressible here is load-mutate-store,
     *           which is precisely the losing implementation, and a backend author who implemented the obvious members
     *           and inherited this one would get it silently.
     *
     * @param snapshot
     *            the snapshot to merge into persisted state (must not be null)
     * @throws NullPointerException
     *             if {@code snapshot} is null
     */
    void mergeFromSnapshot(SessionSnapshot snapshot);

    /**
     * Ensures a record exists for {@code sessionId} and, when that record carries no agent binding yet, binds it
     * to {@code agentRef}. Returns the record as it stands afterwards.
     *
     * <p>
     * This is the primitive a caller uses to take a session into its hands: one call establishes the row and
     * reports the binding that is <em>actually</em> there, so "create if missing", "bind if unbound" and "tell me who
     * owns this" cannot interleave with each other or with another node doing the same. An existing binding is never
     * overwritten — a caller that wanted a different agent learns so from the returned record and refuses, rather than
     * finding out after having already stolen the session.
     *
     * <p>
     * Pass {@code null} for {@code agentRef} (or use {@link #provision(SessionId)}) to establish the record
     * without binding it — the inbox drain path needs a held session before it may read the message that names
     * the agent.
     *
     * @implSpec Provisioning is the operation every other write assumes has already happened, so a backend must
     *           implement it with whatever atomic primitive it has ({@link java.util.concurrent.ConcurrentMap#compute},
     *           {@code INSERT ... ON CONFLICT DO NOTHING} followed by a read in one transaction, ...). The tempting
     *           shortcut — store a freshly constructed record unconditionally — provisions, but as a blind overwrite it
     *           can clobber a record created a microsecond earlier, binding included.
     *
     * @param sessionId
     *            the session id (must not be null)
     * @param agentRef
     *            the agent to bind if the record has no binding yet, or {@code null} to provision without binding
     * @return the record after the operation, never null
     * @throws NullPointerException
     *             if {@code sessionId} is null
     */
    SessionRecordView provision(SessionId sessionId, String agentRef);

    /**
     * Ensures a record exists for {@code sessionId} without touching its agent binding.
     *
     * @param sessionId
     *            the session id (must not be null)
     * @return the record after the operation, never null
     * @throws NullPointerException
     *             if {@code sessionId} is null
     * @see #provision(SessionId, String)
     */
    default SessionRecordView provision(SessionId sessionId) {
        return provision(sessionId, null);
    }

    /**
     * Atomically writes the two side fields whose writer is the session rather than the turn — the absolute
     * {@code sessionTotals} and the runtime {@code budgetOverride} — without touching any other persisted state
     * (messages, systemPrompt, compactionFailureCount, agentRef).
     *
     * <p>
     * The pair travels together because one writer owns both and always knows both. A session hydrates them from the
     * record at open, holds them in memory, and writes them back <em>absolutely</em> — never as a delta — at end of
     * turn and whenever a caller changes the runtime budget. Writing absolutely is what makes the call idempotent: a
     * repeat cannot double-count a turn, and there is no read-modify-write of a persisted counter to lose an update.
     * A {@code null} {@code budgetOverride} means "no override", so it both leaves an unset field unset and clears one
     * that was set (reverting to the opener default on the next open).
     *
     * <p>
     * No-op when no record exists yet: this write follows provisioning, whether by
     * {@link #provision(SessionId, String)} at claim time or by
     * {@link #mergeFromSnapshot(SessionSnapshot)} on the first turn.
     *
     * @implSpec Must write both fields, and only these two, in one operation that is mutually exclusive against a
     *           concurrent {@link #mergeFromSnapshot(SessionSnapshot)}. The race is not merely a multi-node
     *           concern, which is why there is no {@code default} to fall back on: the message merge runs on the
     *           checkpoint writer thread while this write runs on the thread that just finished the turn, so a
     *           single-node deployment races too.
     *
     * @param sessionId
     *            the session id (must not be null)
     * @param totals
     *            the absolute accumulated totals to write (must not be null)
     * @param budgetOverride
     *            the runtime budget override to bind, or {@code null} for none
     * @throws NullPointerException
     *             if {@code sessionId} or {@code totals} is null
     */
    void setTotalsAndBudgetOverride(SessionId sessionId, SessionTotals totals, ExecutionBudget budgetOverride);

    /**
     * Atomically increments the session's consecutive AUTO compaction failure count by one and returns the value the
     * record now holds. Preserves every other field.
     *
     * <p>
     * This is the compaction guard's half of the record, and the one place on this interface where a write is a
     * <em>delta</em> rather than an absolute value. The contrast with
     * {@link #setTotalsAndBudgetOverride(SessionId, SessionTotals, ExecutionBudget)} is not an inconsistency, it is the
     * consequence of who writes: the session's totals have exactly one live writer that holds the authoritative copy in
     * memory and can therefore restate it, whereas the point of persisting this counter at all is that consecutive
     * failures may be observed by <em>different</em> nodes across the turns of one session. No writer holds a copy to
     * restate, so the increment has to happen where the value lives. That is also why the new value is returned rather
     * than left to a follow-up {@link #load(SessionId)}: a read-after-write would let a second node's increment land in
     * between and hand both callers the same number, which is precisely the miscount a circuit breaker must not make.
     *
     * <p>
     * No-op returning {@code 0} when no record exists yet. A run without a session of its own — a subagent fork, a
     * skill fork, a scheduled routine — has no record to count against, and reporting "nothing has been counted" is the
     * honest answer for it; such a run simply never trips the shared breaker.
     *
     * @implSpec Must be a single atomic read-increment-write, mutually exclusive against the other writes on this
     *           interface for the same {@link SessionId} — {@code $inc}, {@code UPDATE … SET n = n + 1 RETURNING n},
     *           {@link java.util.concurrent.ConcurrentMap#compute}, whatever the backend has. There is deliberately no
     *           {@code default}: the only one expressible here is load-mutate-store, which loses exactly the
     *           concurrent increment this method exists to count.
     *
     * @param sessionId
     *            the session id (must not be null)
     * @return the incremented count, or {@code 0} if no record exists (always {@code >= 0})
     * @throws NullPointerException
     *             if {@code sessionId} is null
     */
    int incrementCompactionFailureCount(SessionId sessionId);

    /**
     * Atomically clears the session's consecutive AUTO compaction failure count back to {@code 0}, reopening the
     * compaction circuit breaker. Preserves every other field. No-op when no record exists.
     *
     * <p>
     * Absolute where its sibling is a delta, and for the same reason read backwards: "the streak is over" is knowledge
     * a single successful compaction has in full, so there is nothing to accumulate and no lost update to fear.
     *
     * @implSpec Must write only this field, in one operation that is mutually exclusive against a concurrent
     *           {@link #mergeFromSnapshot(SessionSnapshot)} or
     *           {@link #incrementCompactionFailureCount(SessionId)} for the same {@link SessionId}. No {@code default},
     *           for the reason given on {@link #mergeFromSnapshot(SessionSnapshot)}.
     *
     * @param sessionId
     *            the session id (must not be null)
     * @throws NullPointerException
     *             if {@code sessionId} is null
     */
    void resetCompactionFailureCount(SessionId sessionId);

    /**
     * Loads a session record by id as a read-only view.
     *
     * <p>
     * The return type is narrowed to {@link SessionRecordView} so the mutable {@link SessionRecord} class never leaves
     * this package (design §3.6, enforced by {@code SessionRecordSoleWriterArchitectureTest}). What the narrowing
     * prevents is not corruption — the returned object is an independent copy, so a mutation would change nothing
     * persisted — but the silent no-op of a caller believing it wrote. There is no downcast to reach for: writes are
     * the
     * named primitives on this interface, and in-package writers copy through
     * {@link SessionRecord#copyOf(SessionRecordView)}.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @return An Optional containing the read-only view if found, empty otherwise
     * @throws NullPointerException
     *             if sessionId is null
     */
    Optional<SessionRecordView> load(SessionId sessionId);

    /**
     * Deletes a session record by id.
     *
     * <p>
     * If the record does not exist, this method does nothing.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @throws NullPointerException
     *             if sessionId is null
     */
    void delete(SessionId sessionId);

    /**
     * Lists all session ids stored in this repository.
     *
     * @return A list of session ids (never null, may be empty)
     */
    List<SessionId> listSessionIds();

    /**
     * Checks if a record with the given session id exists.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @return true if the record exists, false otherwise
     * @throws NullPointerException
     *             if sessionId is null
     */
    boolean exists(SessionId sessionId);

    /**
     * Clears all session records from the repository.
     *
     * <p>
     * Use with caution as this operation cannot be undone.
     */
    void clear();
}
