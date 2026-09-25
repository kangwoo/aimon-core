package at.aimon.core.agent.context;

import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.Message;

/**
 * The messages a {@link ContextEngine} decided to send on one LLM call.
 *
 * <p>
 * A view is computed per call and never persisted. What survives a restart is the transcript it was computed from,
 * so a view carries nothing a later call could need to read back.
 *
 * <p>
 * Immutable value object.
 */
public final class ContextView {

    private final List<Message> messages;
    private final int estimatedTokens;

    private ContextView(List<Message> messages, int estimatedTokens) {
        Objects.requireNonNull(messages, "messages cannot be null");
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must be >= 0, got: " + estimatedTokens);
        }
        this.messages = List.copyOf(messages);
        this.estimatedTokens = estimatedTokens;
    }

    /**
     * Creates a view whose size was not estimated.
     *
     * @param messages
     *            the messages to send (must not be null nor contain null; defensively copied)
     * @return the view (never null)
     */
    public static ContextView of(List<Message> messages) {
        return new ContextView(messages, 0);
    }

    /**
     * Creates a view with a size estimate.
     *
     * @param messages
     *            the messages to send (must not be null nor contain null; defensively copied)
     * @param estimatedTokens
     *            the estimated prompt size, system prompt included (must be {@code >= 0})
     * @return the view (never null)
     */
    public static ContextView of(List<Message> messages, int estimatedTokens) {
        return new ContextView(messages, estimatedTokens);
    }

    /** The messages to send, in order. Immutable. */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * The estimated prompt size of this view, system prompt included. {@code 0} when the engine did not estimate it.
     */
    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    @Override
    public String toString() {
        return "ContextView{messages=" + messages.size()
                + (estimatedTokens > 0 ? ", estimatedTokens=" + estimatedTokens : "") + '}';
    }
}
