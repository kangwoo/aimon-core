package at.aimon.core.workflow;

import java.util.Optional;

/**
 * Memoization seam for completed {@code agent()} steps, enabling resume of an workflow run (design §5.3).
 *
 * <p>
 * Modelled on {@code at.aimon.core.subagent.task.SessionSnapshotStore}: best-effort and race-safe. A {@code load}
 * that misses — an unknown key, or a routine backend error — returns {@link Optional#empty()} and must never throw, so
 * a cache problem degrades to re-execution rather than failing the run. Only steps that finished normally are ever
 * {@code save}d (the run gates on {@code COMPLETED}; §5.3d), so a hit always replays a real outcome, never a failure.
 *
 * <p>
 * The default {@link #NO_OP} always misses (no resume). {@code InMemoryStepResultCache} is the
 * bounded per-node default; a scale-out deployment supplies a shared/persistent implementation. Implementations must be
 * safe for concurrent access — a run's fan-out workers save concurrently while a resuming run loads.
 */
public interface StepResultCache {

    /** A cache that always misses and stores nothing — the no-resume default. */
    StepResultCache NO_OP = new StepResultCache() {
        @Override
        public Optional<StepOutcome> load(StepKey key) {
            return Optional.empty();
        }

        @Override
        public void save(StepKey key, StepOutcome outcome) {
            // no-op
        }

        @Override
        public void evict(StepKey key) {
            // no-op
        }
    };

    /**
     * Looks up a cached outcome for a step.
     *
     * @param key
     *            the step key (must not be null)
     * @return the cached outcome, or empty on a miss (unknown key or a routine backend error — never throws)
     */
    Optional<StepOutcome> load(StepKey key);

    /**
     * Stores a completed step's outcome. Best-effort: a backend failure must not propagate.
     *
     * @param key
     *            the step key (must not be null)
     * @param outcome
     *            the completed-step outcome to memoize (must not be null)
     */
    void save(StepKey key, StepOutcome outcome);

    /**
     * Removes a cached outcome.
     *
     * @param key
     *            the step key (must not be null)
     */
    void evict(StepKey key);
}
