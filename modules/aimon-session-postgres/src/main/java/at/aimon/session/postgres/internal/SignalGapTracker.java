package at.aimon.session.postgres.internal;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Remembers the ids a fetch stepped over, for as long as one of them might still turn into a row.
 *
 * <p>
 * {@code conversation_signal} ids are taken when a row is inserted and the row becomes visible when its transaction
 * commits. A fetch of {@code id > lastSeen} that returns 6 without 5 has therefore learned nothing about 5: its
 * transaction may have rolled back, in which case 5 never exists, or may simply not have committed yet, in which case
 * a high-water mark that has moved to 6 never looks at it again. This class is how {@link ListenDispatcher} looks
 * again: every id stepped over is kept for a grace period and asked for by id until it appears or the period ends.
 *
 * <p>
 * Bounded twice over. Of a hole wider than {@link #MAX_HOLE_SPAN} only the highest {@link #MAX_HOLE_SPAN} ids are
 * tracked — a hole that wide is a dispatcher that started from zero, not that many transactions in flight, and the
 * ones that could still be in flight took their ids last. And no more than {@link #MAX_TRACKED} ids are held at once;
 * when there is not room for a hole, again its highest ids are the ones kept.
 *
 * <p>
 * Not thread-safe; the dispatcher calls it from one thread at a time.
 */
final class SignalGapTracker {

    /** Widest run of missing ids taken to be transactions still in flight. */
    static final int MAX_HOLE_SPAN = 4_096;

    /** Most ids held at once. */
    static final int MAX_TRACKED = 16_384;

    private final long graceNanos;

    /** Missing id → the {@code System.nanoTime()} after which it is given up on. */
    private final TreeMap<Long, Long> deadlines = new TreeMap<>();

    SignalGapTracker(long graceMillis) {
        if (graceMillis < 0) {
            throw new IllegalArgumentException("graceMillis must not be negative: " + graceMillis);
        }
        this.graceNanos = graceMillis * 1_000_000L;
    }

    /**
     * Records that a fetch returned {@code toExclusive} without having returned any id from {@code fromInclusive} up
     * to it.
     *
     * @return how many ids of the hole were left untracked for want of room — zero unless {@link #MAX_TRACKED} was
     *         reached. Ids cut off because the hole was wider than {@link #MAX_HOLE_SPAN} are not counted.
     */
    long noteHole(long fromInclusive, long toExclusive, long nowNanos) {
        final long from = Math.max(fromInclusive, toExclusive - MAX_HOLE_SPAN);
        if (toExclusive <= from) {
            return 0;
        }
        final long deadline = nowNanos + graceNanos;
        long untracked = 0;
        // Highest first, so that what is left out when room runs short is the oldest of the hole.
        for (long id = toExclusive - 1; id >= from; id--) {
            if (deadlines.containsKey(id)) {
                continue;
            }
            if (deadlines.size() >= MAX_TRACKED) {
                untracked++;
            } else {
                deadlines.put(id, deadline);
            }
        }
        return untracked;
    }

    /** The row for {@code id} has been delivered; stop asking for it. */
    void resolved(long id) {
        deadlines.remove(id);
    }

    /** The ids still being asked for, ascending. Does not expire any: see {@link #expire}. */
    Long[] outstanding() {
        return deadlines.keySet().toArray(new Long[0]);
    }

    /**
     * Drops the ids whose grace period has ended. Called after a fetch has asked for them, never before: a dispatcher
     * that could not fetch for longer than the grace period must still ask once for what it was waiting on.
     */
    void expire(long nowNanos) {
        for (Iterator<Map.Entry<Long, Long>> it = deadlines.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue() - nowNanos <= 0) {
                it.remove();
            }
        }
    }

    int size() {
        return deadlines.size();
    }
}
