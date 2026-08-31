package at.aimon.core.agent.context;

/**
 * Classifies where an assembled {@link ContextBlock} is meant to be injected into the conversation.
 *
 * <p>
 * The kind is advisory metadata the executor uses to route a block to the right seam. It does not change the block's
 * body; it only tells the consumer whether the block belongs in the system prompt, ahead of the first user message, or
 * as a between-turn reminder.
 */
public enum ContextBlockKind {

    /**
     * Belongs in the system prompt. Consumed as a {@code SystemPromptPart}; re-emitted every turn the prompt is
     * rebuilt. Suited to stable, cache-friendly facts (environment, git branch, directory summary).
     */
    SYSTEM,

    /**
     * Belongs ahead of the first real user message as a synthetic {@code <system-reminder>} user block. Mirrors the
     * legacy {@code UserContextMessageBuilder} path (working directory, current date, user extensions).
     */
    USER_PREPEND,

    /**
     * A lightweight between-turn reminder (e.g. "a tool modified file X since the last turn"). Injected as a synthetic
     * {@code <system-reminder>} user block. Dynamic by nature, so never cache-friendly.
     */
    ATTACHMENT
}
