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
 * Bounded twice over. A hole wider than {@link #MAX_HOLE_SPAN} is not tracked at all — that is not a transaction in
 * flight but rows that were reaped, or a dispatcher that started from zero — and no more than {@link #MAX_TRACKED}
 * ids are held at once.
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
     * @return {@code true} when the hole is now tracked, {@code false} when it was too wide or there was no room
     */
    boolean noteHole(long fromInclusive, long toExclusive, long nowNanos) {
        final long span = toExclusive - fromInclusive;
        if (span <= 0) {
            return true;
        }
        if (span > MAX_HOLE_SPAN || deadlines.size() + span > MAX_TRACKED) {
            return false;
        }
        final long deadline = nowNanos + graceNanos;
        for (long id = fromInclusive; id < toExclusive; id++) {
            deadlines.putIfAbsent(id, deadline);
        }
        return true;
    }

    /** The row for {@code id} has been delivered; stop asking for it. */
    void resolved(long id) {
        deadlines.remove(id);
    }

    /**
     * Drops the ids whose grace period has ended and returns the ones still worth asking for, ascending.
     */
    Long[] outstanding(long nowNanos) {
        for (Iterator<Map.Entry<Long, Long>> it = deadlines.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue() - nowNanos <= 0) {
                it.remove();
            }
        }
        return deadlines.keySet().toArray(new Long[0]);
    }

    int size() {
        return deadlines.size();
    }
}
