package at.aimon.core.agent.compact;

import java.util.List;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmPromptTooLongException;

/**
 * Last-resort fallback consulted when the LLM provider rejects a request with {@link LlmPromptTooLongException} despite
 * all upstream compaction safeguards (the last net in the conversation compaction design doc §10.3). The executor
 * reaches it through {@link at.aimon.core.agent.context.DefaultContextEngine#recover}, which installs the shortened
 * list and hands back the view to retry with.
 *
 * <p>
 * The strategy receives the rejected prompt's message list and the exception, and returns a
 * {@link PromptSizeRecoveryDecision} describing whether the caller should retry with a shortened conversation or give
 * up. Implementations must be stateless and thread-safe.
 *
 * <p>
 * The framework default is {@link NoOpPromptSizeRecoveryStrategy} (always {@code NONE}); callers opt in to recovery by
 * wiring {@link DefaultPromptSizeRecoveryStrategy} via the agent runtime or its context engine.
 */
public interface PromptSizeRecoveryStrategy {

    /**
     * Attempts to produce a shorter, still-valid message list for retry.
     *
     * @param messages
     *            the message list that was just rejected by the provider (must not be null)
     * @param error
     *            the prompt-too-long exception raised by the provider (must not be null)
     * @return a {@link PromptSizeRecoveryDecision}; never null
     */
    PromptSizeRecoveryDecision recover(List<Message> messages, LlmPromptTooLongException error);
}
