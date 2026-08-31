package at.aimon.core.hook.rewake;

import java.time.Duration;
import java.util.Objects;

/**
 * One-shot delay trigger — re-fire the hook once at {@code now + delay}.
 *
 * <p>
 * The delay must be strictly positive. Zero or negative values are rejected to avoid degenerate "fire immediately"
 * envelopes that would deadlock with the originating hook execution.
 *
 * <p>
 * Immutable; safe to share across threads.
 */
public final class RewakeTriggerDelay implements RewakeTrigger {

    private final Duration delay;

    /**
     * Creates a delay trigger.
     *
     * @param delay
     *            wall-clock delay (must not be null and must be strictly positive)
     * @throws NullPointerException
     *             if {@code delay} is null
     * @throws IllegalArgumentException
     *             if {@code delay} is zero or negative
     */
    public RewakeTriggerDelay(Duration delay) {
        Objects.requireNonNull(delay, "delay cannot be null");
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be strictly positive, got: " + delay);
        }
        this.delay = delay;
    }

    /**
     * Returns the configured delay.
     *
     * @return delay (never null, always positive)
     */
    public Duration getDelay() {
        return delay;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RewakeTriggerDelay that)) {
            return false;
        }
        return delay.equals(that.delay);
    }

    @Override
    public int hashCode() {
        return delay.hashCode();
    }

    @Override
    public String toString() {
        return "RewakeTriggerDelay{delay=" + delay + '}';
    }
}
