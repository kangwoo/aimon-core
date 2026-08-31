package at.aimon.session.routing;

import java.time.Duration;
import java.util.concurrent.Flow;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.exception.ConflictingAgentException;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.session.routing.builder.SessionRouterBuilder;

/**
 * Multi-instance-ready agent session manager facade.
 *
 * <p>
 * Wraps {@link at.aimon.core.agent.session.LiveSession} with the cross-node concerns required by a multi-instance /
 * distributed deployment: distributed locking per session, cross-node signal fan-out, mailbox hand-off when the
 * calling node is not the lock holder, and idempotency.
 *
 * <p>
 * The manager is application-scoped: one instance per process, lifetime &ge; the LiveSessionFactory it owns. It is
 * {@link AutoCloseable} so application shutdown can release local sessions and disconnect from any cross-node SPI
 * backends.
 *
 * <p>
 * Design reference: {@code docs/design/session/routing.md} §5.1.
 */
public interface SessionRouter extends AutoCloseable {

    /**
     * Build a new manager. See {@link SessionRouterBuilder} for the required SPIs and operational tuning
     * parameters.
     *
     * @return a fresh builder
     */
    static SessionRouterBuilder builder() {
        return new SessionRouterBuilder();
    }

    /**
     * Submit one turn of input to the session identified by {@code request}.
     *
     * <p>
     * The manager evaluates {@link SubmitRequest#getAgentRef()} against the session's current binding (design
     * §3.6) and either runs the turn locally (lock acquired) or delivers the input to the cross-node inbox (lock
     * held elsewhere).
     *
     * @param request
     *            the submit request (must not be null)
     * @return outcome describing whether the turn ran here or was queued for another node
     * @throws ConflictingAgentException
     *             when the request's {@code agentRef} differs from the session's existing binding
     */
    SubmitDisposition submit(SubmitRequest request);

    /**
     * Subscribe to streaming progress events for {@code sessionId}.
     *
     * <p>
     * The publisher fan-outs both locally-emitted events (when this node owns the turn) and remote events arriving
     * via the signal bus (when another node owns the turn). Multi-subscriber: several SSE clients may observe the
     * same session simultaneously. Subscribers receive {@code onComplete()} on
     * {@link #releaseSession(SessionId)}.
     *
     * @param sessionId
     *            the session (must not be null)
     * @return a {@link Flow.Publisher} of events
     */
    Flow.Publisher<AgentExecutionEvent> events(SessionId sessionId);

    /**
     * Trip an interrupt on the active turn, if any.
     *
     * <p>
     * The manager broadcasts an {@code INTERRUPT} signal — every node trips its own active session for that
     * session. Idempotent: when no turn is active anywhere, the call is a silent no-op.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param reason
     *            the interrupt classification (must not be null)
     */
    void interrupt(SessionId sessionId, InterruptReason reason);

    /**
     * Trip an interrupt on one specific turn.
     *
     * <p>
     * Differs from {@link #interrupt(SessionId, InterruptReason)} in what happens when the named turn is no longer
     * the running one. The unaddressed form stops whatever is running now — correct for an operator stop or an
     * eviction,
     * wrong for a user who clicked cancel: by the time the signal crosses the bus their turn may have finished and the
     * next one started, and the unaddressed form would kill that one instead. This form compares the id against the
     * active turn and does nothing on a mismatch.
     *
     * <p>
     * The id is the one from {@link SubmitDisposition#getTurnId()}. A {@code FORWARDED} turn is not running anywhere
     * yet,
     * so
     * interrupting it is a no-op here — cancelling before execution needs the inbox-level withdrawal that arrives with
     * the turn-result rail, not this call.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param turnId
     *            the turn to stop (must not be null)
     * @param reason
     *            the interrupt classification (must not be null)
     */
    void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason);

    /**
     * Release the local session cache entry and emit {@code onComplete()} on the corresponding {@code events()}
     * publisher.
     *
     * <p>
     * History stored in {@code SessionRecordStore} is preserved — submitting again with the same id resumes the
     * session. For permanent deletion (history removal) call {@link #deleteSession(SessionId)} instead;
     * direct {@code repository.delete(id)} bypasses the manager's lock and races in-flight turns (design §7.7).
     *
     * @param sessionId
     *            the session (must not be null)
     */
    void releaseSession(SessionId sessionId);

    /**
     * Permanently delete the session, including the history persisted in
     * {@link at.aimon.core.agent.session.store.SessionRecordStore}.
     *
     * <p>
     * Implementations MUST acquire the session lock first (broadcasting a {@code YIELD} on contention so the
     * current holder hands it over) and only then call {@code repository.delete(id)}. This closes design
     * §7.7: a direct {@code repository.delete(id)} from outside the manager would race the in-flight turn
     * (the holder may still be writing history) and corrupt cluster state.
     *
     * <p>
     * After delete the manager performs the same teardown as {@link #releaseSession(SessionId)} (cache
     * eviction, inbox purge, terminal {@code InterruptedAt}, {@code onComplete}, {@code EVICT} broadcast).
     *
     * <p>
     * The default implementation throws {@link UnsupportedOperationException} so existing in-process tests and minimal
     * implementations remain source-compatible.
     *
     * @param sessionId
     *            the session (must not be null)
     * @throws IllegalStateException
     *             if the lock cannot be acquired within the implementation's bounded retry budget — the caller may
     *             retry later
     */
    default void deleteSession(SessionId sessionId) {
        throw new UnsupportedOperationException("deleteSession is not implemented by " + getClass().getName());
    }

    /**
     * Best-effort, cluster-aware status snapshot for {@code sessionId}.
     *
     * <p>
     * A session's handle is node-local and lives only on the lock-holding node, so the result is one of: the live
     * local snapshot when this node is the holder; the last value the holder pushed onto the {@code STATUS} rail when
     * another node holds it; or {@code UNKNOWN} when no node has been observed running it. See
     * {@link ClusterSessionStatus}
     * for the provenance and freshness contract. This is observability only — never a control gate; do not
     * read-then-act
     * on it (turn admission must still go through {@link #submit(SubmitRequest)}).
     *
     * <p>
     * The default implementation returns {@link ClusterSessionStatus#unknown(SessionId)} so existing
     * implementations and test doubles remain source-compatible until they wire the holder-pushed projection.
     *
     * @param sessionId
     *            the session (must not be null)
     * @return a cluster-aware status result (never null)
     */
    default ClusterSessionStatus status(SessionId sessionId) {
        return ClusterSessionStatus.unknown(sessionId);
    }

    /**
     * Close the manager. Local sessions are closed; application-scoped collaborators (factory, executor, repository,
     * scheduling components) are not.
     */
    @Override
    void close();

    /**
     * Stop accepting new submits and wait up to {@code timeout} for in-flight turns to finish, then close the
     * manager. Subsequent {@link #submit(SubmitRequest)} calls during draining throw
     * {@link IllegalStateException}.
     *
     * <p>
     * If the timeout elapses before all turns drain, surviving turns are interrupted with
     * {@link InterruptReason#SYSTEM_SHUTDOWN} and the manager proceeds to a hard close. The lock release on each
     * surviving turn flips the lease so other nodes can take over.
     *
     * @param timeout
     *            maximum time to wait for in-flight turns; {@link Duration#ZERO} disables draining and matches
     *            {@link #close()} semantics (must not be null, must not be negative)
     * @return {@code true} if all in-flight turns drained cleanly within {@code timeout}, {@code false} on timeout
     */
    default boolean closeGracefully(Duration timeout) {
        close();
        return true;
    }
}
