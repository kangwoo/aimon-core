package at.aimon.session.routing.internal;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import at.aimon.core.agent.session.store.SessionLease;

/**
 * A session lease this node holds for as long as its session lives (design §7.4), bundled with the renewal
 * schedule and the idempotency slot whose lifetimes are now the lease's rather than a turn's.
 *
 * <p>
 * Until Stage 3b the three were per-turn locals: {@code submit} won a lease, the turn loop started renewal, and its
 * {@code finally} released both. A session-scoped lease has no such scope to live in — it is won by one turn, used by
 * the next five, and returned by whatever finally ends the session (an idle sweep, a release, a shutdown, a lost
 * renewal). This class is that scope, and the two flags are what keep the several possible enders from tripping over
 * each other:
 *
 * <ul>
 * <li>{@code returned} latches so the lease is released exactly once no matter how many paths try. Releasing twice is
 * not merely untidy: the second release would be a no-op only by luck of the fencing token, and the map bookkeeping
 * would already have moved on to a <em>newer</em> lease for the same session.
 * <li>{@code lost} marks a lease whose renewal was refused — the backend has already handed the session to
 * somebody else. Such a lease must never be reused by the next turn, which is why holdership is tested through
 * {@link #isUsable()} rather than by the mere presence of a record.
 * </ul>
 */
final class HeldLease {

    private final SessionLease lease;
    private final IdempotencyTouchSlot touchSlot;
    private final AtomicBoolean returned = new AtomicBoolean();
    private volatile boolean lost;
    private volatile ScheduledFuture<?> renewalTask;

    HeldLease(SessionLease lease, IdempotencyTouchSlot touchSlot) {
        this.lease = Objects.requireNonNull(lease, "lease must not be null");
        this.touchSlot = Objects.requireNonNull(touchSlot, "touchSlot must not be null");
    }

    SessionLease getLease() {
        return lease;
    }

    IdempotencyTouchSlot getTouchSlot() {
        return touchSlot;
    }

    /**
     * Attaches the renewal schedule, which can only be started once this object exists — the failure callback needs to
     * name the lease it is failing for.
     *
     * <p>
     * Cancels immediately if the lease was already returned or lost in the meantime, which is otherwise the one way a
     * schedule outlives its lease and keeps extending a lease nobody owns. Both flags have to be tested, not just
     * {@code returned}: the callback that sets {@code lost} is invoked from inside the very schedule being attached
     * here, so a first tick that fires before this assignment lands would leave a dead lease renewing forever.
     */
    void attachRenewal(ScheduledFuture<?> task) {
        this.renewalTask = Objects.requireNonNull(task, "task must not be null");
        if (returned.get() || lost) {
            task.cancel(false);
        }
    }

    /** Whether the next turn may run under this lease, rather than having to elect afresh. */
    boolean isUsable() {
        return !lost && !returned.get();
    }

    /**
     * Records that renewal was refused: the session now belongs to whoever the backend gave it to.
     *
     * <p>
     * Stops the renewal schedule as well as marking the flag. {@code LeaseRenewer}'s task latches internally, so its
     * later ticks are already cheap no-ops — but nothing <em>cancels</em> the schedule until the lease is returned, and
     * a lost lease is returned by whichever path next notices it, which can be a whole turn away. Leaving it to run is
     * worse than untidy: renewal is given a scheduler of its own precisely so a tick is never late, and ticks for a
     * lease this node has already been told it does not own are the one kind of work with no business on that pool.
     */
    void markLost() {
        lost = true;
        cancelRenewal();
    }

    /**
     * Claims the right to release this lease, stopping renewal and the idempotency touch on the way.
     *
     * @return {@code true} for the single caller that must go on to call {@code SessionStore#release};
     *         {@code false} when some other path already did
     */
    boolean beginReturn() {
        if (!returned.compareAndSet(false, true)) {
            return false;
        }
        cancelRenewal();
        touchSlot.clear();
        return true;
    }

    /**
     * Stops the renewal schedule, if one was ever attached.
     *
     * <p>
     * Never interrupts: {@link #markLost()} is called from inside the renewal tick itself, and {@code cancel(false)} on
     * a fixed-rate task suppresses every later run while letting the one in flight finish. Idempotent, which both
     * callers rely on — a lease can be lost and then returned.
     */
    private void cancelRenewal() {
        final ScheduledFuture<?> task = renewalTask;
        if (task != null) {
            task.cancel(false);
        }
    }
}
