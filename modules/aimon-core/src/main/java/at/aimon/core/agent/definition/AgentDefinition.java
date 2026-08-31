package at.aimon.core.agent.definition;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import at.aimon.core.agent.Version;
import at.aimon.core.llm.LlmModel;

/**
 * Represents a complete agent definition including metadata, configuration, and system prompt.
 *
 * <p>
 * An agent definition encapsulates all the information needed to configure and execute an agent:
 * <ul>
 * <li>Name and version for identification</li>
 * <li>LLM model configuration (model name, temperature, etc.)</li>
 * <li>Maximum iteration limit for the ReAct loop</li>
 * <li>System prompt that defines agent behavior</li>
 * <li>Variables for template interpolation in the system prompt</li>
 * </ul>
 *
 * <p>
 * This class is immutable and thread-safe. Use the {@link Builder} to create instances.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentDefinition definition = AgentDefinition.builder().name("coding-agent").version(new Version(1, 0, 0))
 *             .model(LlmModel.builder().name("gpt-4").temperature(0.7).build()).maxIterations(50)
 *             .systemPrompt("You are a Java expert...").variables(Map.of("language", "Java")).build();
 * }
 * </pre>
 *
 * @see at.aimon.core.agent.definition.parser.AgentDefinitionParser
 */
public final class AgentDefinition {

    /**
     * Creates a new builder for constructing agent definitions.
     *
     * @return A new builder instance (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String name;
    private final Version version;
    private final LlmModel model;
    private final int maxIterations;
    private final String systemPrompt;
    private final Set<String> tags;
    private final Map<String, Object> variables;

    /**
     * AgentDefinition을 생성한다.
     *
     * @param builder
     *            빌더 (null 불가)
     */
    AgentDefinition(Builder builder) {
        name = Objects.requireNonNull(builder.name, "AgentDefinition: Agent name cannot be null");
        version = Objects.requireNonNull(builder.version, "AgentDefinition: Version cannot be null");
        model = Objects.requireNonNull(builder.model, "AgentDefinition: Model cannot be null");
        maxIterations = builder.maxIterations != null ? builder.maxIterations : Integer.MAX_VALUE;
        systemPrompt = Objects.requireNonNull(builder.systemPrompt, "AgentDefinition: System prompt cannot be null");
        tags = builder.tags != null ? Collections.unmodifiableSet(new LinkedHashSet<>(builder.tags)) : Set.of();
        variables = builder.variables != null ? Map.copyOf(builder.variables) : Map.of();
    }

    /**
     * Returns the agent name.
     *
     * @return The agent name (never null)
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the agent version.
     *
     * @return The version (never null)
     */
    public Version getVersion() {
        return version;
    }

    /**
     * Returns the LLM model configuration.
     *
     * @return The LLM model configuration (never null)
     */
    public LlmModel getModel() {
        return model;
    }

    /**
     * Returns the maximum number of ReAct loop iterations allowed.
     *
     * <p>
     * If not explicitly set, defaults to {@link Integer#MAX_VALUE}.
     *
     * @return The maximum iterations (always positive)
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Returns the system prompt that defines agent behavior.
     *
     * @return The system prompt (never null)
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Returns the agent tags.
     *
     * <p>
     * Tags are used for classification, filtering, and routing in agent registries.
     *
     * @return Immutable set of tags (never null, may be empty)
     */
    public Set<String> getTags() {
        return tags;
    }

    /**
     * Returns variables for template interpolation.
     *
     * <p>
     * These variables can be used for Mustache-style template substitution in the system prompt.
     *
     * @return Immutable map of variables (never null, may be empty)
     */
    public Map<String, Object> getVariables() {
        return variables;
    }

    /**
     * Builder for constructing {@link AgentDefinition} instances.
     *
     * <p>
     * All fields except {@code maxIterations} and {@code variables} are required.
     */
    public static final class Builder {
        private String name;
        private Version version;
        private LlmModel model;
        private Integer maxIterations;
        private String systemPrompt;
        private Set<String> tags;
        private Map<String, Object> variables;

        /**
         * Sets the agent name.
         *
         * @param name
         *            The agent name (required, must not be null)
         * @return This builder for method chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the agent version.
         *
         * @param version
         *            The version (required, must not be null)
         * @return This builder for method chaining
         */
        public Builder version(Version version) {
            this.version = version;
            return this;
        }

        /**
         * Sets the LLM model configuration.
         *
         * @param model
         *            The model configuration (required, must not be null)
         * @return This builder for method chaining
         */
        public Builder model(LlmModel model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the maximum number of ReAct loop iterations.
         *
         * @param maxIterations
         *            The maximum iterations (optional, defaults to {@link Integer#MAX_VALUE})
         * @return This builder for method chaining
         */
        public Builder maxIterations(Integer maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the system prompt.
         *
         * @param systemPrompt
         *            The system prompt (required, must not be null)
         * @return This builder for method chaining
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * Sets the agent tags.
         *
         * @param tags
         *            The tags (optional, defaults to empty set; null elements not allowed)
         * @return This builder for method chaining
         */
        public Builder tags(Collection<String> tags) {
            if (tags == null) {
                this.tags = null;
                return this;
            }
            final Set<String> copy = new LinkedHashSet<>();
            for (String tag : tags) {
                copy.add(Objects.requireNonNull(tag, "Tag cannot be null"));
            }
            this.tags = copy;
            return this;
        }

        /**
         * Sets variables for template interpolation.
         *
         * @param variables
         *            The variables map (optional, defaults to empty map)
         * @return This builder for method chaining
         */
        public Builder variables(Map<String, Object> variables) {
            this.variables = variables;
            return this;
        }

        /**
         * Builds the agent definition.
         *
         * @return A new {@link AgentDefinition} instance
         * @throws NullPointerException
         *             if any required field is null
         */
        public AgentDefinition build() {
            return new AgentDefinition(this);
        }
    }
}
