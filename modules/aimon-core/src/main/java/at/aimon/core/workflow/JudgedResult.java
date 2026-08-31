package at.aimon.core.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of an {@link WorkflowPatterns#judgePanel judge-panel} pass — the best-scored candidate, the
 * per-candidate mean scores, and the synthesized final result (design §6.4). Immutable.
 */
public final class JudgedResult {

    private final AgentStepResult best;
    private final int bestIndex;
    private final List<Double> scores;
    private final AgentStepResult synthesis;

    private JudgedResult(AgentStepResult best, int bestIndex, List<Double> scores, AgentStepResult synthesis) {
        this.best = best;
        this.bestIndex = bestIndex;
        this.scores = List.copyOf(scores);
        this.synthesis = synthesis;
    }

    /**
     * @param best
     *            the highest-scoring candidate result, or null if no candidate produced a valid result
     * @param bestIndex
     *            the index of the best candidate, or {@code -1} if none
     * @param scores
     *            the per-candidate mean judge score in candidate order (must not be null)
     * @param synthesis
     *            the synthesizer's result (may be null if synthesis was skipped)
     * @return a new judged result
     */
    public static JudgedResult of(AgentStepResult best, int bestIndex, List<Double> scores, AgentStepResult synthesis) {
        Objects.requireNonNull(scores, "scores cannot be null");
        return new JudgedResult(best, bestIndex, scores, synthesis);
    }

    /** @return the highest-scoring candidate result, or empty if none produced a valid result */
    public Optional<AgentStepResult> best() {
        return Optional.ofNullable(best);
    }

    /** @return the index of the best candidate, or {@code -1} if none */
    public int bestIndex() {
        return bestIndex;
    }

    /** @return the per-candidate mean judge score in candidate order (never null) */
    public List<Double> scores() {
        return scores;
    }

    /** @return the synthesizer's result, or empty if synthesis was skipped */
    public Optional<AgentStepResult> synthesis() {
        return Optional.ofNullable(synthesis);
    }

    @Override
    public String toString() {
        return "JudgedResult{bestIndex=" + bestIndex + ", scores=" + scores + '}';
    }
}
