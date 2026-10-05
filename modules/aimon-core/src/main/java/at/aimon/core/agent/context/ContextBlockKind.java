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
     * Injected ahead of each turn's user message as a synthetic {@code <system-reminder>} user block — every
     * turn, not only the first, so each one is stored in the transcript. Suited to facts that belong beside the
     * message rather than in the system prompt (user extensions); a value that only needs to be current, such as
     * the date, costs less as a system prompt variable.
     */
    USER_PREPEND,

    /**
     * A lightweight between-turn reminder (e.g. "a tool modified file X since the last turn"). Injected as a synthetic
     * {@code <system-reminder>} user block. Dynamic by nature, so never cache-friendly.
     */
    ATTACHMENT
}
