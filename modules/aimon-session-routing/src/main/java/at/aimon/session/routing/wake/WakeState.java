package at.aimon.session.routing.wake;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * What a {@link WakeLedger} remembers about one session: when it was woken in the last hour, how many wakes ran in a
 * row without another turn, the session's turn count when the last wake's turn ended, and whether a wake's turn is
 * still outstanding — while it is, a turn count that moved is that turn's own, not the user's.
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class WakeState {

    private static final WakeState EMPTY = new WakeState(List.of(), 0, null, false);

    private final List<Instant> recentWakes;
    private final int consecutive;
    private final Integer turnCountAfterWake;
    private final boolean outstanding;

    private WakeState(List<Instant> recentWakes, int consecutive, Integer turnCountAfterWake, boolean outstanding) {
        this.recentWakes = List.copyOf(recentWakes);
        if (consecutive < 0) {
            throw new IllegalArgumentException("consecutive must not be negative");
        }
        this.consecutive = consecutive;
        this.turnCountAfterWake = turnCountAfterWake;
        this.outstanding = outstanding;
    }

    /**
     * @return the state of a session never woken
     */
    public static WakeState empty() {
        return EMPTY;
    }

    /**
     * Creates a state.
     *
     * @param recentWakes
     *            when the session was woken, oldest first (must not be null)
     * @param consecutive
     *            wakes in a row without another turn (must not be negative)
     * @param turnCountAfterWake
     *            the session's turn count when the last wake's turn ended, or null if unknown
     * @param outstanding
     *            whether a wake's turn has been submitted and has not ended yet
     * @return the state
     */
    public static WakeState of(List<Instant> recentWakes, int consecutive, Integer turnCountAfterWake,
            boolean outstanding) {
        return new WakeState(Objects.requireNonNull(recentWakes, "recentWakes must not be null"), consecutive,
                turnCountAfterWake, outstanding);
    }

    /**
     * @return when the session was woken, oldest first (unmodifiable)
     */
    public List<Instant> getRecentWakes() {
        return recentWakes;
    }

    /**
     * @return wakes in a row without another turn in between
     */
    public int getConsecutive() {
        return consecutive;
    }

    /**
     * @return the session's turn count when the last wake's turn ended, or empty if unknown
     */
    public OptionalInt getTurnCountAfterWake() {
        return turnCountAfterWake == null ? OptionalInt.empty() : OptionalInt.of(turnCountAfterWake);
    }

    /**
     * @return whether a wake's turn has been submitted and has not ended yet
     */
    public boolean isOutstanding() {
        return outstanding;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof WakeState that && consecutive == that.consecutive && outstanding == that.outstanding
                && recentWakes.equals(that.recentWakes) && Objects.equals(turnCountAfterWake, that.turnCountAfterWake);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recentWakes, consecutive, turnCountAfterWake, outstanding);
    }

    @Override
    public String toString() {
        return "WakeState{recent=" + recentWakes.size() + ", consecutive=" + consecutive + ", turnCountAfterWake="
                + turnCountAfterWake + ", outstanding=" + outstanding + '}';
    }
}
