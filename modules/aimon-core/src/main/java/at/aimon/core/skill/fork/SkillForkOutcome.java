package at.aimon.core.skill.fork;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a fork-mode skill execution.
 *
 * <p>
 * Decouples {@link SkillForkExecutor} implementations from the {@code ToolResult} type so that the formatting of the
 * tool-facing payload remains the responsibility of {@code SkillTool}.
 *
 * <p>
 * Immutable value object.
 */
public final class SkillForkOutcome {

    /**
     * Creates a successful outcome carrying the subagent's final answer.
     *
     * @param finalAnswer
     *            The final answer produced by the forked subagent (must not be null)
     * @return A new successful outcome
     */
    public static SkillForkOutcome success(String finalAnswer) {
        Objects.requireNonNull(finalAnswer, "Final answer cannot be null");
        return new SkillForkOutcome(true, finalAnswer, null, false);
    }

    /**
     * Creates a successful outcome whose final answer the provider cut off at its output-token limit: the fork ended
     * {@code CompletionReason.TRUNCATED}. The answer is kept — it already ends in the truncation marker — and
     * {@link #isTruncated()} says it is not whole, so a caller does not have to find the marker in the text.
     *
     * @param finalAnswer
     *            The partial final answer, marker included (must not be null)
     * @return A new successful, truncated outcome
     */
    public static SkillForkOutcome truncated(String finalAnswer) {
        Objects.requireNonNull(finalAnswer, "Final answer cannot be null");
        return new SkillForkOutcome(true, finalAnswer, null, true);
    }

    /**
     * Creates a failed outcome carrying an error message.
     *
     * @param errorMessage
     *            The error message describing why the fork failed (must not be null)
     * @return A new failed outcome
     */
    public static SkillForkOutcome failure(String errorMessage) {
        Objects.requireNonNull(errorMessage, "Error message cannot be null");
        return new SkillForkOutcome(false, null, errorMessage, false);
    }

    private final boolean success;
    private final String finalAnswer;
    private final String errorMessage;
    private final boolean truncated;

    private SkillForkOutcome(boolean success, String finalAnswer, String errorMessage, boolean truncated) {
        this.success = success;
        this.finalAnswer = finalAnswer;
        this.errorMessage = errorMessage;
        this.truncated = truncated;
    }

    /** Returns whether the fork executed successfully. */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns whether the fork's final answer was cut off at the provider's output-token limit. Only a successful
     * outcome can be truncated; the answer it carries is partial.
     *
     * @return {@code true} if the final answer is not whole
     */
    public boolean isTruncated() {
        return truncated;
    }

    /** Returns the subagent's final answer if the fork succeeded. */
    public Optional<String> getFinalAnswer() {
        return Optional.ofNullable(finalAnswer);
    }

    /** Returns the error message if the fork failed. */
    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }
}
