package at.aimon.session.routing.wake;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * What a {@link WakeLedger} remembers about one session: when it was woken in the last hour, how many wakes ran in a
 * row without another turn, and the session's turn count when the last wake's turn ended.
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class WakeState {

    private static final WakeState EMPTY = new WakeState(List.of(), 0, null);

    private final List<Instant> recentWakes;
    private final int consecutive;
    private final Integer turnCountAfterWake;

    private WakeState(List<Instant> recentWakes, int consecutive, Integer turnCountAfterWake) {
        this.recentWakes = List.copyOf(recentWakes);
        if (consecutive < 0) {
            throw new IllegalArgumentException("consecutive must not be negative");
        }
        this.consecutive = consecutive;
        this.turnCountAfterWake = turnCountAfterWake;
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
     * @return the state
     */
    public static WakeState of(List<Instant> recentWakes, int consecutive, Integer turnCountAfterWake) {
        return new WakeState(Objects.requireNonNull(recentWakes, "recentWakes must not be null"), consecutive,
                turnCountAfterWake);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof WakeState that && consecutive == that.consecutive && recentWakes.equals(that.recentWakes)
                && Objects.equals(turnCountAfterWake, that.turnCountAfterWake);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recentWakes, consecutive, turnCountAfterWake);
    }

    @Override
    public String toString() {
        return "WakeState{recent=" + recentWakes.size() + ", consecutive=" + consecutive + ", turnCountAfterWake="
                + turnCountAfterWake + '}';
    }
}
