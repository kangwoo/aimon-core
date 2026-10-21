package at.aimon.core.agent.budget;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A point in time an execution must be over by, read on a monotonic clock.
 *
 * <p>
 * Unlike {@link ExecutionBudget#getMaxWallClockDuration()}, which {@link BudgetTracker} measures with a wall
 * {@link java.time.Clock} from the moment the tracker is built, a deadline is <b>anchored once</b>, where the work is
 * launched, and every later reader asks the same instance how much is left. That is what lets a deadline cover time an
 * execution spends before it starts (waiting for a background worker) and what lets a child be held to whatever its
 * parent has left ({@link #earlierOf(ExecutionDeadline)}).
 *
 * <p>
 * The clock is {@link System#nanoTime()} unless a test hands one in, so a wall-clock step (NTP, a VM resumed) neither
 * shortens nor lengthens a deadline. Instants of two deadlines are compared only when both read the same clock;
 * {@link #earlierOf(ExecutionDeadline)} refuses two that do not.
 *
 * <p>
 * Immutable value object — thread-safe and freely shareable.
 */
public final class ExecutionDeadline {

    private static final LongSupplier SYSTEM_NANO_TIME = System::nanoTime;

    private final LongSupplier nanoClock;
    private final long deadlineNanos;
    private final Duration budget;

    private ExecutionDeadline(LongSupplier nanoClock, long deadlineNanos, Duration budget) {
        this.nanoClock = nanoClock;
        this.deadlineNanos = deadlineNanos;
        this.budget = budget;
    }

    /**
     * A deadline {@code budget} from now on the system's monotonic clock.
     *
     * @param budget
     *            how long from now (must be positive)
     * @return the deadline
     * @throws IllegalArgumentException
     *             if {@code budget} is zero or negative
     */
    public static ExecutionDeadline after(Duration budget) {
        return after(budget, SYSTEM_NANO_TIME);
    }

    /**
     * A deadline {@code budget} from now on the given monotonic clock. For tests that move time by hand.
     *
     * @param budget
     *            how long from now (must be positive)
     * @param nanoClock
     *            a monotonic nanosecond source, read like {@link System#nanoTime()} (must not be null)
     * @return the deadline
     * @throws IllegalArgumentException
     *             if {@code budget} is zero or negative
     */
    public static ExecutionDeadline after(Duration budget, LongSupplier nanoClock) {
        Objects.requireNonNull(budget, "budget cannot be null");
        Objects.requireNonNull(nanoClock, "nanoClock cannot be null");
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("budget must be positive, got: " + budget);
        }
        return new ExecutionDeadline(nanoClock, nanoClock.getAsLong() + budget.toNanos(), budget);
    }

    /**
     * @return how much time is left, {@link Duration#ZERO} once the deadline has passed (never negative)
     */
    public Duration remaining() {
        final long left = deadlineNanos - nanoClock.getAsLong();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    /**
     * @return whether the deadline has passed
     */
    public boolean isExpired() {
        return deadlineNanos - nanoClock.getAsLong() <= 0;
    }

    /**
     * @return the duration this deadline was anchored with, before any clamping by {@link #earlierOf}
     */
    public Duration getBudget() {
        return budget;
    }

    /**
     * Returns whichever of this deadline and {@code other} comes first, so a child is never given more time than its
     * parent has left.
     *
     * @param other
     *            the other deadline (nullable: then this one is returned)
     * @return the earlier of the two
     * @throws IllegalArgumentException
     *             if the two read different clocks and cannot be compared
     */
    public ExecutionDeadline earlierOf(ExecutionDeadline other) {
        if (other == null || other == this) {
            return this;
        }
        if (other.nanoClock != nanoClock) {
            throw new IllegalArgumentException("deadlines on different clocks cannot be compared");
        }
        return other.deadlineNanos - deadlineNanos < 0 ? other : this;
    }

    @Override
    public String toString() {
        return "ExecutionDeadline{budget=" + budget + ", remaining=" + remaining() + '}';
    }
}
