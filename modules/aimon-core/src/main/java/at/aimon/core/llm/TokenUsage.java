package at.aimon.core.llm;

import java.util.Objects;

/**
 * Represents token usage information from an LLM API call.
 *
 * <p>
 * Contains the number of tokens used for the prompt (input), completion (output), the total token count, and the
 * subset of the completion tokens the provider spent on reasoning.
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
        return new TokenUsage(promptTokens, completionTokens, totalTokens, 0);
    }

    /**
     * Creates a new TokenUsage that also records the reasoning tokens the provider reported.
     *
     * <p>
     * {@code reasoningTokens} is a <em>subset</em> of {@code completionTokens}, not an addition to it: OpenAI reports
     * it inside {@code output_tokens_details}, and {@code total = input + output} still holds. It is therefore
     * reported alongside cost rather than added to it — see {@link at.aimon.core.llm.cost.ModelPrice#costOf}.
     *
     * @param promptTokens
     *            The number of prompt tokens (must be &gt;= 0)
     * @param completionTokens
     *            The number of completion tokens (must be &gt;= 0)
     * @param totalTokens
     *            The total number of tokens (must be &gt;= 0 and &gt;= promptTokens + completionTokens)
     * @param reasoningTokens
     *            The reasoning tokens included in {@code completionTokens} (must be &gt;= 0)
     * @return A new TokenUsage instance
     * @throws IllegalArgumentException
     *             if any token count is negative or if totalTokens is less than promptTokens + completionTokens
     */
    public static TokenUsage of(int promptTokens, int completionTokens, int totalTokens, int reasoningTokens) {
        return new TokenUsage(promptTokens, completionTokens, totalTokens, reasoningTokens);
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
        return new TokenUsage(0, 0, 0, 0);
    }

    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final int reasoningTokens;

    /**
     * Creates a new TokenUsage.
     *
     * @param promptTokens
     *            The number of prompt tokens (must be >= 0)
     * @param completionTokens
     *            The number of completion tokens (must be >= 0)
     * @param totalTokens
     *            The total number of tokens (must be >= 0 and >= promptTokens + completionTokens)
     * @param reasoningTokens
     *            The reasoning tokens included in completionTokens (must be >= 0)
     * @throws IllegalArgumentException
     *             if any token count is negative or if totalTokens is less than promptTokens + completionTokens
     */
    private TokenUsage(int promptTokens, int completionTokens, int totalTokens, int reasoningTokens) {
        if (promptTokens < 0) {
            throw new IllegalArgumentException("Prompt tokens cannot be negative: " + promptTokens);
        }
        if (completionTokens < 0) {
            throw new IllegalArgumentException("Completion tokens cannot be negative: " + completionTokens);
        }
        if (totalTokens < 0) {
            throw new IllegalArgumentException("Total tokens cannot be negative: " + totalTokens);
        }
        // Validated as >= 0 only. There is deliberately no reasoningTokens <= completionTokens invariant, even though
        // every provider integrated so far reports containment: the value is filled by the *server*, and a new
        // throwing cross-field check on it would turn an accounting surprise into a failed LLM call.
        if (reasoningTokens < 0) {
            throw new IllegalArgumentException("Reasoning tokens cannot be negative: " + reasoningTokens);
        }
        if (totalTokens < promptTokens + completionTokens) {
            throw new IllegalArgumentException(
                    String.format("Total tokens (%d) must be >= prompt (%d) + completion (%d) tokens", totalTokens,
                            promptTokens, completionTokens));
        }
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.reasoningTokens = reasoningTokens;
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
                totalTokens + other.totalTokens, reasoningTokens + other.reasoningTokens);
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

    /**
     * Gets the number of reasoning tokens the provider reported for this call.
     *
     * <p>
     * These are a <em>subset</em> of {@link #getCompletionTokens()}, not an addition to it, so they are already
     * priced by {@link at.aimon.core.llm.cost.ModelPrice#costOf}. Zero when the provider does not report them — every
     * usage built through the three-argument {@link #of(int, int, int)} factory, and every provider that has no such
     * counter.
     *
     * @return The reasoning token count (&gt;= 0)
     */
    public int getReasoningTokens() {
        return reasoningTokens;
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
                && totalTokens == that.totalTokens && reasoningTokens == that.reasoningTokens;
    }

    @Override
    public int hashCode() {
        return Objects.hash(promptTokens, completionTokens, totalTokens, reasoningTokens);
    }

    @Override
    public String toString() {
        return "TokenUsage{" + "prompt=" + promptTokens + ", completion=" + completionTokens + ", total=" + totalTokens
                + ", reasoning=" + reasoningTokens + '}';
    }
}
