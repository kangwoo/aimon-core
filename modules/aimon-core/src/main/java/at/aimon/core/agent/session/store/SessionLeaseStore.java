package at.aimon.core.agent.session.store;

import java.time.Duration;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionLeaseException;

/**
 * Holder election for one session — the distributed half of {@link SessionStore}.
 *
 * <p>
 * Formerly {@code at.aimon.session.base.spi.ConversationLock}. Two things changed besides the name: {@link #findHolder}
 * is new, and holder identity is now expected to be <em>node-derived and stable</em> rather than derived per thread and
 * per turn. Every shipped backend evaluates only lease <em>expiry</em> on acquire and never compares holder identity,
 * so
 * a stable holder id does not make acquisition re-entrant: a second concurrent acquire from the same node still loses,
 * exactly as a different node would.
 *
 * <p>
 * Implementations are shared across every node in a deployment and are therefore application-scoped. Do not confuse
 * this
 * with {@link SessionStore}, whose default implementation is <em>node</em>-scoped because it tracks which leases
 * the local node holds.
 *
 * <h2>Fencing-token contract</h2>
 *
 * <p>
 * {@code fencingToken} must be strictly monotonic per {@link SessionId} for the entire lifetime of that
 * session — across releases, expiries and process restarts. A token that walks backwards defeats fencing entirely.
 * A globally monotonic counter shared by all sessions satisfies this (the in-memory backend does exactly that); so
 * does a per-session counter with gaps, since only ordering matters. In practice the requirement means
 * {@code release} must expire the lease <em>in place</em> or keep the counter in a record that release does not touch;
 * the shipped backends do one or the other, and each documents which.
 *
 * <h2>Failure semantics</h2>
 * <ul>
 * <li>Held by another holder &rarr; empty {@link Optional} from {@link #tryAcquire}.
 * <li>Backend communication failure &rarr; {@link SessionLeaseException}.
 * </ul>
 */
public interface SessionLeaseStore {

    /**
     * Attempts to become the holder of {@code id} on behalf of {@code holderId}.
     *
     * @param id
     *            the session to claim (must not be null)
     * @param holderId
     *            the requesting holder identity (must not be null)
     * @param lease
     *            requested lease duration (must not be null, must be positive)
     * @return a {@link SessionLease} on success, empty when another holder owns it
     * @throws SessionLeaseException
     *             on backend communication failure
     */
    Optional<SessionLease> tryAcquire(SessionId id, String holderId, Duration lease);

    /**
     * Reports the current live holder of {@code id}, if any, without attempting to become one.
     *
     * <p>
     * Implementations must return empty unless the session is held <em>right now</em> — a lease that has lapsed,
     * or
     * that was explicitly released, is not a holder even if the backend still keeps its record around to preserve the
     * fencing counter. This is the one liveness check with uniform semantics across all backends, which is why it, and
     * not {@link #extend}, is what {@link SessionStore#records()} uses to re-prove holdership before a write.
     *
     * <p>
     * Two legitimate uses, and one illegitimate one. A <b>holder</b> may compare the returned {@code holderId} and
     * {@code fencingToken} against a lease it already won, to confirm it has not been superseded. A <b>non-holder</b>
     * may
     * read it to name the holder — to address a hand-off at it, or to reject a request with something more useful than
     * "not you". What a non-holder must never do is treat "the holder is me-shaped" or "there is no holder" as
     * permission to write: the only thing that grants write rights is a {@link SessionLease} won from
     * {@link #tryAcquire}, because only that is atomic against a competing acquirer.
     *
     * @param id
     *            the session to inspect (must not be null)
     * @return the current holder, or empty when the session is unheld, released, or its lease has lapsed
     * @throws SessionLeaseException
     *             on backend communication failure
     */
    Optional<LeaseHolder> findHolder(SessionId id);

    /**
     * Extends an already-held lease.
     *
     * <p>
     * This is an atomic compare-and-set at the shared authority on the <em>token</em>: it returns {@code false} when
     * {@code lease}'s fencing token is no longer the stored one — that is, when some other holder has taken over.
     *
     * <p>
     * <b>It is not a liveness check.</b> Implementations are not required to reject a lease that lapsed without a
     * successor, and three of the four shipped backends accept exactly that: the token still matches, so the extend
     * succeeds and effectively resurrects the lapsed lease. Only the Redis backend rejects it, and only incidentally
     * (the key is already gone). Callers that need "is my lease still live" must use {@link #findHolder}; callers that
     * need "has anyone taken over from me" — renewal, and the fenced write path's token comparison — are served by
     * either.
     *
     * @param lease
     *            a lease returned from a prior {@link #tryAcquire} (must not be null)
     * @param duration
     *            new lease duration (must not be null, must be positive)
     * @return {@code true} when the lease was extended, {@code false} when the fencing token no longer matches
     * @throws SessionLeaseException
     *             on backend communication failure
     */
    boolean extend(SessionLease lease, Duration duration);

    /**
     * Returns the lease. Token mismatches are silently ignored — release is best-effort and an unreleased lease
     * eventually expires on its own.
     *
     * @param lease
     *            the lease to return (must not be null)
     */
    void release(SessionLease lease);
}
