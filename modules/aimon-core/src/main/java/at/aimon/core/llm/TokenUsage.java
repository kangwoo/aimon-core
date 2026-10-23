package at.aimon.core.llm;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Represents token usage information from an LLM API call.
 *
 * <p>
 * Contains the number of tokens used for the prompt (input), completion (output), the total token count, and the
 * subset of the completion tokens the provider spent on reasoning.
 *
 * <p>
 * <strong>What {@code promptTokens} counts.</strong> It is the <em>whole</em> input context the provider processed for
 * the call: uncached input, input read from the prompt cache, and input written to it. A provider whose wire format
 * reports only the uncached part (Anthropic's {@code input_tokens}) has the provider module add the cache counters
 * back, so every provider means the same thing by this number and it can be compared against a context window or a
 * context-length price band. The cache counters, when known, are therefore <em>subsets</em> of
 * {@code promptTokens}, never additions to it — see {@link #getCacheReadTokens()}.
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
        return new TokenUsage(promptTokens, completionTokens, totalTokens, 0, null, null);
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
        return new TokenUsage(promptTokens, completionTokens, totalTokens, reasoningTokens, null, null);
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
        return new TokenUsage(0, 0, 0, 0, null, null);
    }

    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final int reasoningTokens;
    // Boxed on purpose: null is "the provider did not report it", which is not the same claim as a measured 0.
    private final Integer cacheReadTokens;
    private final Integer cacheWriteTokens;

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
     * @param cacheReadTokens
     *            The prompt tokens read from the cache, or null when not reported (must be >= 0 when set)
     * @param cacheWriteTokens
     *            The prompt tokens written to the cache, or null when not reported (must be >= 0 when set)
     * @throws IllegalArgumentException
     *             if any token count is negative or if totalTokens is less than promptTokens + completionTokens
     */
    private TokenUsage(int promptTokens, int completionTokens, int totalTokens, int reasoningTokens,
            Integer cacheReadTokens, Integer cacheWriteTokens) {
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
        // Same reasoning for the cache counters: >= 0 only, no "read + write <= prompt" check.
        if (cacheReadTokens != null && cacheReadTokens < 0) {
            throw new IllegalArgumentException("Cache read tokens cannot be negative: " + cacheReadTokens);
        }
        if (cacheWriteTokens != null && cacheWriteTokens < 0) {
            throw new IllegalArgumentException("Cache write tokens cannot be negative: " + cacheWriteTokens);
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
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
    }

    /**
     * Returns a copy of this usage carrying the prompt-cache counters the provider reported.
     *
     * <p>
     * Both counters are <em>subsets</em> of {@link #getPromptTokens()}: the provider module is responsible for making
     * {@code promptTokens} the whole input context, cache included (see the class Javadoc). {@code null} means the
     * provider did not report that counter — or reported it in a form that cannot be priced with a single rate, such
     * as Anthropic cache writes split across TTLs — and is deliberately distinct from a measured {@code 0}.
     *
     * @param cacheReadTokens
     *            The prompt tokens served from the cache, or null when not measured (must be &gt;= 0 when set)
     * @param cacheWriteTokens
     *            The prompt tokens written to the cache, or null when not measured (must be &gt;= 0 when set)
     * @return A new TokenUsage with the same counts plus the given cache counters
     * @throws IllegalArgumentException
     *             if a cache counter is negative
     */
    public TokenUsage withCacheTokens(Integer cacheReadTokens, Integer cacheWriteTokens) {
        return new TokenUsage(promptTokens, completionTokens, totalTokens, reasoningTokens, cacheReadTokens,
                cacheWriteTokens);
    }

    /**
     * Adds another TokenUsage to this one, returning a new TokenUsage with accumulated counts.
     *
     * <p>
     * This is useful for accumulating token usage across multiple LLM calls.
     *
     * <p>
     * A cache counter of the sum is known only when it is known on both sides; an unknown side makes the sum unknown,
     * because adding a measured number to "not measured" does not yield a measured number. The one exception is a side
     * with no prompt tokens at all (such as the {@link #empty()} accumulator seed): its cache counters are trivially
     * zero, since they are subsets of the prompt.
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
                totalTokens + other.totalTokens, reasoningTokens + other.reasoningTokens,
                sumCache(cacheReadTokens, promptTokens, other.cacheReadTokens, other.promptTokens),
                sumCache(cacheWriteTokens, promptTokens, other.cacheWriteTokens, other.promptTokens));
    }

    private static Integer sumCache(Integer left, int leftPrompt, Integer right, int rightPrompt) {
        if (left == null && right == null) {
            // Nothing was measured on either side: the sum is not measured either, even when both prompts are zero,
            // so empty().add(empty()) stays equal to empty().
            return null;
        }
        final Integer l = left != null || leftPrompt != 0 ? left : Integer.valueOf(0);
        final Integer r = right != null || rightPrompt != 0 ? right : Integer.valueOf(0);
        if (l == null || r == null) {
            return null;
        }
        try {
            return Math.addExact(l, r);
        } catch (ArithmeticException e) {
            // An overflowing counter is no longer a measurement; failing add() over it would fail an accumulator.
            return null;
        }
    }

    /**
     * Reports whether no token was counted: prompt, completion, total and reasoning are all zero.
     *
     * <p>
     * Use this rather than {@code equals(TokenUsage.empty())} to ask "is there any usage": the cache counters take
     * part in {@link #equals(Object)}, so a usage with measured-zero cache counters is not equal to {@link #empty()}
     * while still counting no tokens.
     *
     * @return {@code true} if every token count is zero
     */
    public boolean isZero() {
        return promptTokens == 0 && completionTokens == 0 && totalTokens == 0 && reasoningTokens == 0;
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

    /**
     * Gets the prompt tokens the provider served from its prompt cache.
     *
     * <p>
     * A <em>subset</em> of {@link #getPromptTokens()}. Empty when the provider did not report it, which is not the
     * same as a measured zero: an empty value must not be priced as "no cache reads".
     *
     * @return The cache-read token count, or empty when not measured
     */
    public OptionalInt getCacheReadTokens() {
        return cacheReadTokens == null ? OptionalInt.empty() : OptionalInt.of(cacheReadTokens);
    }

    /**
     * Gets the prompt tokens the provider wrote to its prompt cache.
     *
     * <p>
     * A <em>subset</em> of {@link #getPromptTokens()}. Empty when the provider did not report it, or reported writes
     * that a single cache-write rate cannot price (e.g. Anthropic writes with a one-hour TTL). A provider whose API
     * has no billed cache writes (OpenAI) reports a measured {@code 0} whenever it reports cache reads.
     *
     * @return The cache-write token count, or empty when not measured
     */
    public OptionalInt getCacheWriteTokens() {
        return cacheWriteTokens == null ? OptionalInt.empty() : OptionalInt.of(cacheWriteTokens);
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
                && totalTokens == that.totalTokens && reasoningTokens == that.reasoningTokens
                && Objects.equals(cacheReadTokens, that.cacheReadTokens)
                && Objects.equals(cacheWriteTokens, that.cacheWriteTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(promptTokens, completionTokens, totalTokens, reasoningTokens, cacheReadTokens,
                cacheWriteTokens);
    }

    @Override
    public String toString() {
        return "TokenUsage{" + "prompt=" + promptTokens + ", completion=" + completionTokens + ", total=" + totalTokens
                + ", reasoning=" + reasoningTokens + ", cacheRead=" + cacheReadTokens + ", cacheWrite="
                + cacheWriteTokens + '}';
    }
}
