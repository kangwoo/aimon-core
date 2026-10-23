package at.aimon.core.llms.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.models.completions.CompletionUsage;

import at.aimon.core.llm.TokenUsage;

/**
 * Reads a Chat Completions usage document into {@link TokenUsage}, prompt-cache counter included.
 *
 * <p>
 * Chat Completions' {@code prompt_tokens} already counts the cached part, so it is the whole input context as
 * {@link TokenUsage} defines it; {@code prompt_tokens_details.cached_tokens} is the cache-read subset. OpenAI bills no
 * cache writes, so whenever the cache-read counter is reported the cache-write counter is a measured {@code 0}. When
 * the details are missing both stay unmeasured. The endpoint reports no reasoning-token counter, so
 * {@link TokenUsage#getReasoningTokens()} stays at zero.
 */
final class OpenAiChatUsages {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatUsages.class);

    private OpenAiChatUsages() {
    }

    /**
     * @param usage
     *            the usage document (must not be null)
     * @return the token usage, {@link TokenUsage#empty()} when a counter is out of {@code int} range
     */
    static TokenUsage toTokenUsage(CompletionUsage usage) {
        try {
            final TokenUsage counts = TokenUsage.of(Math.toIntExact(usage.promptTokens()),
                    Math.toIntExact(usage.completionTokens()), Math.toIntExact(usage.totalTokens()));
            final Long cached = cachedTokens(usage);
            if (cached == null || cached < 0 || cached > Integer.MAX_VALUE) {
                return counts;
            }
            return counts.withCacheTokens(cached.intValue(), 0);
        } catch (ArithmeticException e) {
            log.warn("Token count exceeded Integer range; falling back to empty usage: {}", e.getMessage());
            return TokenUsage.empty();
        }
    }

    /** Never throws: a missing or malformed breakdown reads as "not measured". */
    private static Long cachedTokens(CompletionUsage usage) {
        try {
            return usage._promptTokensDetails().asKnown().flatMap(details -> details._cachedTokens().asKnown())
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
