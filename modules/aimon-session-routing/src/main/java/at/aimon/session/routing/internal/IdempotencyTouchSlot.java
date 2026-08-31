package at.aimon.session.routing.internal;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;

/**
 * The idempotency reservation that the current turn wants refreshed on every successful lease renewal — a mutable slot
 * the turn loop writes and the renewal tick reads (design §9.2 stale cleanup).
 *
 * <p>
 * Before Stage 3b the renewal schedule and the reservation had the same lifetime, so the key could simply be a
 * constructor argument of the renewal tick. Now the schedule belongs to the <em>lease</em>, which outlives any one
 * turn:
 * a schedule started for turn 1 is still running when turn 5 executes. Keeping the key in the tick would mean every
 * turn
 * after the first refreshes a reservation that is already done while its own goes untouched — and the holder-loss
 * sweeper
 * then resets a live turn's key after the secondary TTL, letting the same request execute twice. So the tick holds this
 * slot instead and reads whatever is bound at the moment it fires.
 *
 * <p>
 * The bound pair is swapped atomically rather than stored as two fields because a tick that interleaved with a
 * {@link #bind} could otherwise read turn 5's key against turn 4's reserver id, and {@link IdempotencyStore#touch}
 * silently ignores a mismatched holder — the failure mode this class exists to prevent.
 *
 * <p>
 * An unbound slot is the normal state, not an error: turns that arrive without an idempotency key, and drained inbox
 * messages whose reservation lives on the submitting node, have nothing to refresh.
 */
public final class IdempotencyTouchSlot {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTouchSlot.class);

    private final IdempotencyStore store;
    private final AtomicReference<Reservation> bound = new AtomicReference<>();

    public IdempotencyTouchSlot(IdempotencyStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /**
     * Binds the reservation to refresh from now on, replacing any previous one.
     *
     * @param key
     *            the idempotency key, or {@code null} to leave the slot empty
     * @param reserverId
     *            the per-attempt holder recorded on that entry, or {@code null} to leave the slot empty. This is
     *            <em>not</em> the lease holder id — the two identities came apart in Stage 3b.
     */
    public void bind(String key, String reserverId) {
        bound.set(key == null || reserverId == null ? null : new Reservation(key, reserverId));
    }

    /** Clears the slot at turn end, so later renewals of the same lease touch nothing. */
    public void clear() {
        bound.set(null);
    }

    /** Refreshes the bound reservation's secondary TTL, if any. Never throws. */
    void touch() {
        final Reservation reservation = bound.get();
        if (reservation == null) {
            return;
        }
        try {
            store.touch(reservation.key, reservation.reserverId);
        } catch (Exception e) {
            log.warn("Idempotency touch failed for key {}: {}", reservation.key, e.toString());
        }
    }

    private static final class Reservation {

        private final String key;
        private final String reserverId;

        Reservation(String key, String reserverId) {
            this.key = key;
            this.reserverId = reserverId;
        }
    }
}
