package at.aimon.core.agent.session.store;

import java.time.Duration;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLeaseException;

/**
 * One session store: holder election, the agent binding, and the durable record behind a single door.
 *
 * <p>
 * The session manager used to reach for {@link SessionLeaseStore} and {@link SessionRecordStore} separately; this is
 * the single entry point over both. The split was not merely inconvenient — it made three separate steps out of one
 * decision, and the ordering between them was wrong:
 *
 * <ol>
 * <li>validate the requested agent against the persisted binding,
 * <li>elect a holder,
 * <li>provision the record.
 * </ol>
 *
 * <p>
 * Step 1 ran <em>before</em> step 2, so a node validated a binding it had not yet earned the right to act on, and step
 * 3
 * might never happen at all (every side-field writer on {@link SessionRecordStore} is a documented no-op when the
 * record is absent). {@link #claim} performs all three, in the order that makes them safe.
 *
 * <h2>Atomic by ordering, not by transaction</h2>
 *
 * <p>
 * {@code claim} does not need a distributed transaction. It wins the lease first — an atomic, fenced operation at the
 * shared authority — and only then reads or writes the record. A node that loses the election never touches the record,
 * so the record work is single-threaded by construction. That is what makes the composite safe across nodes without a
 * two-phase protocol.
 *
 * <h2>Scope</h2>
 *
 * <p>
 * {@link SessionLeaseStore} and {@link SessionRecordStore} are shared by every node and are
 * application-scoped.
 * A {@code SessionStore} is <b>node-scoped</b>: one per session manager, because it tracks which leases
 * <em>this</em>
 * node holds so {@link #records()} can fence a write without every caller threading a fencing token through the ReAct
 * call chain. A deployment with two managers in one JVM (the multi-node test harnesses, notably) must build two stores
 * over the same two backends — not share one.
 *
 * <h2>Fencing, and the window it does not close</h2>
 *
 * <p>
 * {@link #records()} re-proves holdership at the lease authority immediately before delegating each mutation, via
 * {@link SessionLeaseStore#findHolder} — not via {@code extend}, which compares only the token and in three of the
 * four shipped backends will happily renew a lease that has already lapsed. An evicted holder's next write therefore
 * fails rather than corrupting history, which removes the steady-state failure mode where a node that lost its lease
 * mid-turn kept appending messages.
 *
 * <p>
 * It does <em>not</em> close the sub-millisecond interleaving between the re-proof and the delegated write: doing that
 * requires the record write itself to carry the token, i.e. a fenced compare-and-set record backend. That is
 * deliberately
 * out of scope here — do not cite this class as a complete remedy for split-brain.
 */
public interface SessionStore {

    /**
     * Becomes the holder of {@code sessionId}, provisions its record, and establishes or validates its agent
     * binding — as one call.
     *
     * <p>
     * On success the record is guaranteed to exist and to be bound to {@code agentRef}. On
     * {@link ClaimResult.AgentConflict} the lease has already been returned, so a rejected claim does not pin the
     * session.
     *
     * @param sessionId
     *            the session to claim (must not be null)
     * @param agentRef
     *            the agent the caller intends to run; validated against the persisted binding and written when the
     *            session is not yet bound (must not be null)
     * @param holderId
     *            the requesting holder identity — node-derived and stable (must not be null)
     * @param lease
     *            requested lease duration (must not be null, must be positive)
     * @return which of the three outcomes occurred; never null
     * @throws SessionLeaseException
     *             on lease-backend communication failure
     */
    ClaimResult claim(SessionId sessionId, String agentRef, String holderId, Duration lease);

    /**
     * Becomes the holder of {@code sessionId} and provisions its record, without touching the agent binding.
     *
     * <p>
     * The primitive {@link #claim} is built on: {@code claim} is this plus binding settlement. Callers that have no
     * agent
     * to assert use it directly, and there are exactly two of them. An inbox drain adopts whatever the session is
     * already bound to, so it has no requested agent to validate — it must hold the session <em>before</em> it can
     * see the queued messages that would name one, and a late {@link ClaimResult.AgentConflict} would return the lease
     * with messages already taken out of the at-most-once inbox. A delete is about to remove the record, so its binding
     * is
     * not a question worth asking. Both read the binding afterwards through {@link #load} and, when they must write
     * one,
     * through {@link #records()} — fenced by the lease this call hands back.
     *
     * <p>
     * The record is provisioned even when the caller only means to read it, so that a subsequent side-field write
     * through {@link #records()} is not silently dropped as a no-op on an absent record. For a delete that means a
     * session which never existed is briefly created and then removed; harmless, and cheaper than a contract where
     * "held" does not imply "exists".
     *
     * @param sessionId
     *            the session to acquire (must not be null)
     * @param holderId
     *            the requesting holder identity — node-derived and stable (must not be null)
     * @param lease
     *            requested lease duration (must not be null, must be positive)
     * @return the lease when this caller won the election, or empty when somebody else holds the session
     * @throws SessionLeaseException
     *             on lease-backend communication failure
     */
    Optional<SessionLease> acquire(SessionId sessionId, String holderId, Duration lease);

    /**
     * Reports the current holder without attempting to become one. Observational only — see {@link LeaseHolder}.
     *
     * @param sessionId
     *            the session to inspect (must not be null)
     * @return the current holder, or empty when unheld
     */
    Optional<LeaseHolder> findHolder(SessionId sessionId);

    /**
     * Extends a held lease. Returns {@code false} when the lease is no longer current, which the caller must treat as
     * "holdership lost" rather than as a retryable error.
     *
     * @param lease
     *            a lease this store issued (must not be null)
     * @param duration
     *            the new lease duration (must not be null, must be positive)
     * @return {@code true} when extended
     */
    boolean renew(SessionLease lease, Duration duration);

    /**
     * Returns a held lease and forgets it locally, so subsequent {@link #records()} writes for that session are no
     * longer fenced through it. Best-effort at the backend; idempotent locally.
     *
     * @param lease
     *            the lease to return (must not be null)
     */
    void release(SessionLease lease);

    /**
     * Reads a record without claiming anything. Unfenced by definition — a read cannot corrupt history, and callers
     * such
     * as a status projection legitimately need to look at sessions they do not hold.
     *
     * @param sessionId
     *            the session to read (must not be null)
     * @return the record, or empty when it does not exist
     */
    Optional<SessionRecordView> load(SessionId sessionId);

    /**
     * The record write path, fenced against the leases this node holds.
     *
     * <p>
     * Every mutator on the returned view re-proves holdership before delegating; a mutation for a session this node
     * does not hold is rejected. Reads pass straight through. Hand this to whatever assembles the durable-side
     * components for a live session ({@code TranscriptManager}, the session's own record handle) so the ReAct loop's
     * writes are fenced without knowing that fencing exists.
     *
     * <p>
     * Stage 4 of the session-first restructure collapses this into a single {@code put(lease, snapshot)}, at which
     * point
     * the remaining mutator surface disappears. Until then this is the fenced write path.
     *
     * @return a fenced repository view; never null
     */
    SessionRecordStore records();
}
