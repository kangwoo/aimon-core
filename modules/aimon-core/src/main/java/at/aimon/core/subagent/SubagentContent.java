package at.aimon.core.subagent;

import java.util.Objects;

/**
 * Subagent content (system prompt).
 *
 * <p>
 * Represents the instructional content that defines how a subagent should behave and respond to tasks. This content is
 * typically loaded from the markdown body of subagent definition files (agents/*.md).
 *
 * <p>
 * Immutable value object.
 *
 * @see Subagent
 */
public final class SubagentContent {
    /**
     * Creates a new SubagentContent with the given system prompt.
     *
     * @param systemPrompt
     *            The system prompt (must not be null)
     * @return A new SubagentContent instance
     * @throws NullPointerException
     *             if systemPrompt is null
     */
    public static SubagentContent of(String systemPrompt) {
        return new SubagentContent(systemPrompt);
    }

    private final String systemPrompt;

    private SubagentContent(String systemPrompt) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
    }

    /**
     * Returns the system prompt.
     *
     * @return The system prompt (never null)
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SubagentContent that = (SubagentContent) o;
        return Objects.equals(systemPrompt, that.systemPrompt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systemPrompt);
    }

    @Override
    public String toString() {
        return "SubagentContent{" + "systemPrompt='" + systemPrompt + '\'' + '}';
    }
}
