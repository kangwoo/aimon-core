package at.aimon.core.agent;

import java.util.Map;
import java.util.Objects;

/**
 * Content for an agent.
 *
 * <p>
 * Contains the system prompt that defines agent behavior and optional variables for template rendering.
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
 *     AgentContent content = AgentContent.builder().systemPrompt("You are a helpful assistant...")
 *             .variables(Map.of("language", "Java")).build();
 * }
 * </pre>
 */
public final class AgentContent {
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful AI assistant.";

    private final String systemPrompt;
    private final Map<String, Object> variables;

    private AgentContent(Builder builder) {
        this.systemPrompt = Objects.requireNonNull(builder.systemPrompt, "System prompt cannot be null");
        this.variables = builder.variables != null ? Map.copyOf(builder.variables) : Map.of();
    }

    /**
     * Creates a new builder.
     *
     * @return A new AgentContent.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the system prompt.
     *
     * @return The system prompt (never null)
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Gets the variables.
     *
     * @return An immutable map of variables (never null, may be empty)
     */
    public Map<String, Object> getVariables() {
        return variables;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AgentContent that = (AgentContent) o;
        return systemPrompt.equals(that.systemPrompt) && variables.equals(that.variables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systemPrompt, variables);
    }

    @Override
    public String toString() {
        String promptPreview = systemPrompt.length() > 50 ? systemPrompt.substring(0, 50) + "..." : systemPrompt;
        return "AgentContent{" + "systemPrompt='" + promptPreview + "', " + "variables=" + variables + '}';
    }

    /** Builder for AgentContent. */
    public static final class Builder {
        private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        private Map<String, Object> variables;

        private Builder() {
        }

        /**
         * Sets the system prompt.
         *
         * @param systemPrompt
         *            The system prompt (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if systemPrompt is null
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
            return this;
        }

        /**
         * Sets the variables.
         *
         * @param variables
         *            The variables (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if variables is null
         */
        public Builder variables(Map<String, Object> variables) {
            this.variables = Objects.requireNonNull(variables, "Variables cannot be null");
            return this;
        }

        /**
         * Builds the AgentContent.
         *
         * @return A new AgentContent
         */
        public AgentContent build() {
            return new AgentContent(this);
        }
    }
}
