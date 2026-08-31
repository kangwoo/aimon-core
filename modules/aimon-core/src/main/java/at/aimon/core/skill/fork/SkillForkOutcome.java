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
        return new SkillForkOutcome(true, finalAnswer, null);
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
        return new SkillForkOutcome(false, null, errorMessage);
    }

    private final boolean success;
    private final String finalAnswer;
    private final String errorMessage;

    private SkillForkOutcome(boolean success, String finalAnswer, String errorMessage) {
        this.success = success;
        this.finalAnswer = finalAnswer;
        this.errorMessage = errorMessage;
    }

    /** Returns whether the fork executed successfully. */
    public boolean isSuccess() {
        return success;
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
