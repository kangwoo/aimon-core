package at.aimon.core.memory.dreamer;

import java.util.List;

import at.aimon.core.memory.Observation;

/**
 * Scores how "surprising" an {@link Observation} is relative to a list of
 * neighbors — i.e., how much new information it carries given what is already
 * known.
 *
 * <p>
 * Per design doc §6.3.2, surprisal is the signal the dreamer uses to pick
 * consolidation candidates: low-surprisal observations duplicate their
 * neighbors and can be merged; high-surprisal observations contradict them
 * and need reconciliation.
 *
 * <p>
 * Implementations must return a value in {@code [0.0, 1.0]} where {@code 0.0}
 * means "fully redundant" and {@code 1.0} means "completely novel". An empty
 * neighbor list must yield {@code 1.0}: by definition, a fact with no peers
 * carries maximum new information.
 *
 * <p>
 * Implementations must be thread-safe; the dreamer batches calls
 * concurrently across subjects.
 */
public interface SurprisalScorer {

    /**
     * Returns the surprisal score of {@code observation} given {@code neighbors}.
     *
     * @param observation
     *            the observation under evaluation (must not be null)
     * @param neighbors
     *            existing observations the score is computed against (must not
     *            be null; may be empty)
     * @return surprisal in {@code [0.0, 1.0]}
     */
    double score(Observation observation, List<Observation> neighbors);
}
