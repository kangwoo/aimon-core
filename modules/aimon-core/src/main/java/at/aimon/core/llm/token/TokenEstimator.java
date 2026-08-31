package at.aimon.core.llm.token;

import java.util.List;

import at.aimon.core.llm.Message;

/**
 * Estimates the number of tokens consumed by a system prompt + message list when sent to an LLM.
 *
 * <p>
 * Implementations may be heuristic (e.g. {@link HeuristicTokenEstimator}) or provider-specific (tiktoken, Anthropic
 * count_tokens API). All implementations must be thread-safe.
 *
 * <p>
 * The framework uses these estimates to drive {@link at.aimon.core.agent.compact.CompactionGuard} decisions, so
 * <em>over-estimation is preferred to under-estimation</em>.
 */
public interface TokenEstimator {

    /**
     * Estimates the total token count for the given system prompt and messages.
     *
     * @param systemPrompt
     *            the system prompt; may be {@code null} or empty
     * @param messages
     *            the message list (must not be null)
     * @return the estimated token count; never negative
     */
    int estimate(String systemPrompt, List<Message> messages);

    /**
     * Estimates tokens for a single message.
     *
     * @param message
     *            the message (must not be null)
     * @return the estimated token count; never negative
     */
    int estimateMessage(Message message);

    /**
     * Estimates tokens for a free-form text fragment.
     *
     * @param text
     *            the text; may be {@code null} or empty (returns 0)
     * @return the estimated token count; never negative
     */
    int estimateText(String text);
}
