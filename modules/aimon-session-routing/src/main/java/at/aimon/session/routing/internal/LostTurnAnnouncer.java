package at.aimon.session.routing.internal;

import at.aimon.core.agent.session.SessionId;

/**
 * How {@link HolderLossSweeper} reports a turn whose holder it declared lost.
 *
 * <p>
 * The split is detection from announcement. The sweeper owns the first half — scan for stale reservations, win the
 * cross-node reset — and knows nothing about how the news travels; the manager owns the second, because the signal rail
 * and the registry of callers waiting on forwarded turns both live there.
 *
 * <p>
 * It also keeps the sweeper honest about scope. The sweeper used to broadcast an {@code EVICT}, which told every node
 * to
 * tear the session down: drop the cached live session, purge approvals, complete the event streams. But the
 * session
 * survives its holder — a successor may already have claimed it and be running the next turn — so that broadcast could
 * evict a live session over a turn that died on a different node. What is dead is one attempt, and this interface is
 * shaped to say only that.
 */
@FunctionalInterface
interface LostTurnAnnouncer {

    /**
     * Report that the turn holding {@code idempotencyKey} will never finish, so callers waiting on it fail now instead
     * of at their forward deadline.
     *
     * <p>
     * The lost turn is addressed by its idempotency key rather than by a {@code TurnId}: no surviving node knows the
     * id.
     * That is not a gap to close by guessing one — the reservation records its holder, not its turn, and the id lived
     * in
     * the inbox envelope the dead holder consumed. Every reservation the sweeper can see has a key, since a submission
     * without one never reserves anything.
     *
     * <p>
     * Implementations must not throw; the sweeper guards the call anyway so one bad announcement cannot kill the
     * scheduled pass.
     *
     * @param sessionId
     *            the session whose holder was lost (never null)
     * @param idempotencyKey
     *            the reservation the lost holder was executing under (never null)
     * @param lostHolderId
     *            the holder id that stopped renewing (never null)
     */
    void announceHolderLost(SessionId sessionId, String idempotencyKey, String lostHolderId);
}
