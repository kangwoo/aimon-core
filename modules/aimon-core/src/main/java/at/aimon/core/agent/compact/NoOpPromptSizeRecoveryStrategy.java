package at.aimon.core.agent.compact;

import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmPromptTooLongException;

/**
 * Default {@link PromptSizeRecoveryStrategy} that disables recovery entirely.
 *
 * <p>
 * Always returns {@link PromptSizeRecoveryDecision#none(String)} so the caller propagates the original
 * {@link LlmPromptTooLongException}. This is the framework default — recovery is opt-in.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class NoOpPromptSizeRecoveryStrategy implements PromptSizeRecoveryStrategy {

    private static final NoOpPromptSizeRecoveryStrategy INSTANCE = new NoOpPromptSizeRecoveryStrategy();

    public static NoOpPromptSizeRecoveryStrategy instance() {
        return INSTANCE;
    }

    private NoOpPromptSizeRecoveryStrategy() {
    }

    @Override
    public PromptSizeRecoveryDecision recover(List<Message> messages, LlmPromptTooLongException error) {
        Objects.requireNonNull(messages, "messages cannot be null");
        Objects.requireNonNull(error, "error cannot be null");
        return PromptSizeRecoveryDecision.none("recovery disabled");
    }
}
