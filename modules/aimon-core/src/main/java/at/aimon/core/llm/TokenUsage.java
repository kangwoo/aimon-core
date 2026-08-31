package at.aimon.core.llm;

import java.util.Objects;

/**
 * Represents token usage information from an LLM API call.
 *
 * <p>
 * Contains the number of tokens used for the prompt (input), completion (output), and the total token count.
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     TokenUsage usage = TokenUsage.of(150, 50, 200);
 *     System.out.println("Prompt tokens: " + usage.getPromptTokens());
 *     System.out.println("Total tokens: " + usage.getTotalTokens());
 *
 *     // Accumulate usage across multiple calls
 *     TokenUsage usage1 = TokenUsage.of(100, 50, 150);
 *     TokenUsage usage2 = TokenUsage.of(80, 40, 120);
 *     TokenUsage total = usage1.add(usage2); // 180, 90, 270
 * }
 * </pre>
 */
public final class TokenUsage {
    /**
     * Creates a new TokenUsage.
     *
     * @param promptTokens
     *            The number of prompt tokens (must be >= 0)
     * @param completionTokens
     *            The number of completion tokens (must be >= 0)
     * @param totalTokens
     *            The total number of tokens (must be >= 0 and >= promptTokens + completionTokens)
     * @return A new TokenUsage instance
     * @throws IllegalArgumentException
     *             if any token count is negative or if totalTokens is less than promptTokens + completionTokens
     */
    public static TokenUsage of(int promptTokens, int completionTokens, int totalTokens) {
        return new TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    /**
     * Creates an empty TokenUsage with all counts set to zero.
     *
     * <p>
     * Use this to represent the absence of token usage information, for example when an operation doesn't use an LLM.
     *
     * @return A TokenUsage with all token counts set to 0
     */
    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0);
    }

    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;

    /**
     * Creates a new TokenUsage.
     *
     * @param promptTokens
     *            The number of prompt tokens (must be >= 0)
     * @param completionTokens
     *            The number of completion tokens (must be >= 0)
     * @param totalTokens
     *            The total number of tokens (must be >= 0 and >= promptTokens + completionTokens)
     * @throws IllegalArgumentException
     *             if any token count is negative or if totalTokens is less than promptTokens + completionTokens
     */
    private TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        if (promptTokens < 0) {
            throw new IllegalArgumentException("Prompt tokens cannot be negative: " + promptTokens);
        }
        if (completionTokens < 0) {
            throw new IllegalArgumentException("Completion tokens cannot be negative: " + completionTokens);
        }
        if (totalTokens < 0) {
            throw new IllegalArgumentException("Total tokens cannot be negative: " + totalTokens);
        }
        if (totalTokens < promptTokens + completionTokens) {
            throw new IllegalArgumentException(
                    String.format("Total tokens (%d) must be >= prompt (%d) + completion (%d) tokens", totalTokens,
                            promptTokens, completionTokens));
        }
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    /**
     * Adds another TokenUsage to this one, returning a new TokenUsage with accumulated counts.
     *
     * <p>
     * This is useful for accumulating token usage across multiple LLM calls.
     *
     * @param other
     *            The other TokenUsage to add (must not be null)
     * @return A new TokenUsage with the sum of both token counts
     * @throws NullPointerException
     *             if other is null
     */
    public TokenUsage add(TokenUsage other) {
        Objects.requireNonNull(other, "Other TokenUsage cannot be null");
        return new TokenUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens,
                totalTokens + other.totalTokens);
    }

    /**
     * Gets the number of prompt tokens.
     *
     * @return The prompt token count (>= 0)
     */
    public int getPromptTokens() {
        return promptTokens;
    }

    /**
     * Gets the number of completion tokens.
     *
     * @return The completion token count (>= 0)
     */
    public int getCompletionTokens() {
        return completionTokens;
    }

    /**
     * Gets the total number of tokens.
     *
     * @return The total token count (>= 0)
     */
    public int getTotalTokens() {
        return totalTokens;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TokenUsage that = (TokenUsage) o;
        return promptTokens == that.promptTokens && completionTokens == that.completionTokens
                && totalTokens == that.totalTokens;
    }

    @Override
    public int hashCode() {
        return Objects.hash(promptTokens, completionTokens, totalTokens);
    }

    @Override
    public String toString() {
        return "TokenUsage{" + "prompt=" + promptTokens + ", completion=" + completionTokens + ", total=" + totalTokens
                + '}';
    }
}
