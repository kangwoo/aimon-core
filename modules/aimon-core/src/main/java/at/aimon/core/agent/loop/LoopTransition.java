package at.aimon.core.agent.loop;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, self-describing record of a single ReAct-loop re-entry.
 *
 * <p>
 * A transition answers "why is the loop about to run iteration <em>N</em>?" with a {@link LoopTransitionReason}, the
 * one-based {@code iteration} it introduces, and an optional human-readable {@code note} carrying secondary context
 * (e.g. how many queued inputs were drained). It is a pure value object: it never drives control flow. The loop
 * attaches
 * it to the iteration's tracing span so runs are reconstructable for observability and unit tests, mirroring the way
 * {@link at.aimon.core.agent.budget.CompletionReason} models the terminal side.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {@code
 * LoopTransition t = LoopTransition.of(LoopTransitionReason.QUEUED_INPUT, 3, "2 queued input(s) drained");
 * t.getReason();    // QUEUED_INPUT
 * t.getIteration(); // 3
 * t.getNote();      // Optional["2 queued input(s) drained"]
 * }
 * </pre>
 */
public final class LoopTransition {

    private final LoopTransitionReason reason;
    private final int iteration;
    private final String note;

    private LoopTransition(LoopTransitionReason reason, int iteration, String note) {
        this.reason = Objects.requireNonNull(reason, "reason cannot be null");
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be >= 1, got: " + iteration);
        }
        this.iteration = iteration;
        // A blank note is normalised to absent so getNote() has a single "no secondary context" representation.
        this.note = note != null && !note.isBlank() ? note : null;
    }

    /**
     * Creates a transition with no secondary note.
     *
     * @param reason
     *            the re-entry cause (must not be null)
     * @param iteration
     *            the one-based iteration this transition introduces (must be &gt;= 1)
     * @return a new {@link LoopTransition}
     * @throws IllegalArgumentException
     *             if {@code iteration} is less than 1
     */
    public static LoopTransition of(LoopTransitionReason reason, int iteration) {
        return new LoopTransition(reason, iteration, null);
    }

    /**
     * Creates a transition with an optional secondary note.
     *
     * @param reason
     *            the re-entry cause (must not be null)
     * @param iteration
     *            the one-based iteration this transition introduces (must be &gt;= 1)
     * @param note
     *            secondary context; {@code null} or blank is treated as absent
     * @return a new {@link LoopTransition}
     * @throws IllegalArgumentException
     *             if {@code iteration} is less than 1
     */
    public static LoopTransition of(LoopTransitionReason reason, int iteration, String note) {
        return new LoopTransition(reason, iteration, note);
    }

    /**
     * @return the re-entry cause (never null)
     */
    public LoopTransitionReason getReason() {
        return reason;
    }

    /**
     * @return the one-based iteration this transition introduces (always &gt;= 1)
     */
    public int getIteration() {
        return iteration;
    }

    /**
     * @return the secondary note, or {@link Optional#empty()} when none was supplied
     */
    public Optional<String> getNote() {
        return Optional.ofNullable(note);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LoopTransition that = (LoopTransition) o;
        return iteration == that.iteration && reason == that.reason && Objects.equals(note, that.note);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reason, iteration, note);
    }

    @Override
    public String toString() {
        return "LoopTransition{reason=" + reason + ", iteration=" + iteration + (note != null ? ", note=" + note : "")
                + "}";
    }
}
