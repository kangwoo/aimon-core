package at.aimon.session.routing.internal;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;

/**
 * The idempotency reservations that work running on this node wants refreshed on every successful lease renewal — a
 * mutable slot per reservation, written by the turn loop and the drain pass and read by the renewal tick (design §9.2
 * stale cleanup).
 *
 * <p>
 * Before Stage 3b the renewal schedule and the reservation had the same lifetime, so the key could simply be a
 * constructor argument of the renewal tick. Now the schedule belongs to the <em>lease</em>, which outlives any one
 * turn:
 * a schedule started for turn 1 is still running when turn 5 executes. Keeping the key in the tick would mean every
 * turn
 * after the first refreshes a reservation that is already done while its own goes untouched — and the holder-loss
 * sweeper
 * then resets a live turn's key after the secondary TTL, letting the same request execute twice. So the tick reads
 * whatever is bound at the moment it fires.
 *
 * <p>
 * <b>Why more than one.</b> A submitted turn opens a drain pass that also runs whatever the inbox was holding, and both
 * kinds of message can carry a reservation this node is now responsible for: the submission's own, live for the whole
 * pass because its caller is waiting on it, and the queued message's, live only while that message runs (see
 * {@link IdempotencyStore#acquireHolder}). One slot would make those two evict each other — and a sibling turn easily
 * outlasts the secondary TTL, so whichever lost would be swept as a lost holder while its node was working normally.
 *
 * <p>
 * Each binding is one map entry, so a tick can never read turn 5's key against turn 4's reserver id — the pairing the
 * previous single-reference design had to swap atomically to guarantee, and which {@link IdempotencyStore#touch}
 * silently ignores when it is wrong.
 *
 * <p>
 * An empty slot set is the normal state, not an error: turns that arrive without an idempotency key, and drained
 * messages whose reservation this node did not manage to take over, have nothing to refresh.
 */
public final class IdempotencyTouchSlot {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTouchSlot.class);

    private final IdempotencyStore store;
    private final ConcurrentMap<String, String> bound = new ConcurrentHashMap<>();

    public IdempotencyTouchSlot(IdempotencyStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /**
     * Adds a reservation to refresh from now on, replacing any earlier binding of the same key.
     *
     * @param key
     *            the idempotency key, or {@code null} to bind nothing
     * @param reserverId
     *            the per-attempt holder recorded on that entry, or {@code null} to bind nothing. This is <em>not</em>
     *            the lease holder id — the two identities came apart in Stage 3b.
     */
    // A null half binds nothing rather than clearing, which is a change from the single-reference version: there,
    // bind(null, null) — what runTurnLoop passes for a submission with no key — emptied the slot, and that doubled as
    // a defence against a previous turn's binding surviving into this one. Clearing here instead would drop the
    // bindings of messages this same pass is running, so the defence moved to the exits: runTurnLoop and runDrainOnly
    // both clear() in their finally, drain unbinds each message in its own, and HeldLease cancels renewal when the
    // lease is lost and clears on return. Losing the lease leaves the bindings standing, which is harmless only
    // because the cancelled schedule is the sole caller of touch().
    public void bind(String key, String reserverId) {
        if (key == null || reserverId == null) {
            return;
        }
        bound.put(key, reserverId);
    }

    /**
     * Stops refreshing one reservation, once its turn has been settled with {@code markDone} or freed. Leaves every
     * other binding alone — the pass that ran this message may still owe a refresh to the submission that opened it.
     *
     * @param key
     *            the idempotency key to release, or {@code null} for nothing
     */
    public void unbind(String key) {
        if (key == null) {
            return;
        }
        bound.remove(key);
    }

    /** Clears every binding at turn end, so later renewals of the same lease touch nothing. */
    public void clear() {
        bound.clear();
    }

    /** Refreshes every bound reservation's secondary TTL. Never throws. */
    void touch() {
        for (Map.Entry<String, String> reservation : bound.entrySet()) {
            try {
                store.touch(reservation.getKey(), reservation.getValue());
            } catch (Exception e) {
                log.warn("Idempotency touch failed for key {}: {}", reservation.getKey(), e.toString());
            }
        }
    }
}
