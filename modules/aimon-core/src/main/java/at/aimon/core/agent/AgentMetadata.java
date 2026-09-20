package at.aimon.core.agent;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.LlmModel;

/**
 * Metadata for an agent.
 *
 * <p>
 * Contains configuration parameters that control agent behavior.
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
 *     AgentMetadata metadata = AgentMetadata.builder().maxIterations(10)
 *             .modelConfig(LlmModel.builder().temperature(0.7).build()).build();
 * }
 * </pre>
 */
public final class AgentMetadata {
    public static final int DEFAULT_MAX_ITERATIONS = Integer.MAX_VALUE;

    private final String name;
    private final LlmModel model;
    private final int maxIterations;
    private final Set<String> tags;
    private final List<AllowedTool> allowedTools;

    private AgentMetadata(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "Agent name cannot be null");
        if (builder.maxIterations <= 0) {
            throw new IllegalArgumentException("Max iterations must be positive");
        }
        this.maxIterations = builder.maxIterations;
        this.model = Objects.requireNonNull(builder.model, "Model config cannot be null");
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(builder.tags));
        this.allowedTools = List.copyOf(builder.allowedTools);
    }

    /**
     * Creates a new builder.
     *
     * @return A new AgentMetadata.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the agent name.
     *
     * @return The agent name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the maximum number of iterations.
     *
     * @return The maximum iterations
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Gets the model configuration.
     *
     * @return The model configuration (never null)
     */
    public LlmModel getModel() {
        return model;
    }

    /**
     * Gets the agent tags.
     *
     * @return An unmodifiable set of tags (never null, may be empty)
     */
    public Set<String> getTags() {
        return tags;
    }

    /**
     * Returns the allow-list bounding every tool call this agent makes.
     *
     * <p>
     * The same {@link AllowedTool} vocabulary the subagent, skill and command surfaces use, applied to the main agent
     * for the first time. <b>An empty list means unrestricted</b>, which is what every validator in
     * {@code at.aimon.core.agent.tool.permission} does with one, and is the default — an agent that declares nothing
     * behaves exactly as it did before this field existed.
     *
     * @return An immutable list of allowed tools (never null, may be empty)
     */
    public List<AllowedTool> getAllowedTools() {
        return allowedTools;
    }

    /**
     * Returns whether this agent declares any tool restriction at all.
     *
     * @return true when the allow-list is non-empty
     */
    public boolean hasToolRestrictions() {
        return !allowedTools.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AgentMetadata that = (AgentMetadata) o;
        return name.equals(that.name) && maxIterations == that.maxIterations && model.equals(that.model)
                && tags.equals(that.tags) && allowedTools.equals(that.allowedTools);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, maxIterations, model, tags, allowedTools);
    }

    @Override
    public String toString() {
        return "AgentMetadata{" + "name='" + name + "', maxIterations=" + maxIterations + ", model=" + model + ", tags="
                + tags + ", allowedTools=" + allowedTools + '}';
    }

    /** Builder for AgentMetadata. */
    public static final class Builder {
        private String name;
        private LlmModel model = LlmModel.builder().build();
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private Set<String> tags = new LinkedHashSet<>();
        private List<AllowedTool> allowedTools = List.of();

        private Builder() {
        }

        /**
         * Sets the agent name.
         *
         * @param name
         *            The agent name (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if name is null
         */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "Agent name cannot be null");
            return this;
        }

        /**
         * Sets the maximum number of iterations.
         *
         * @param maxIterations
         *            The maximum iterations (must be positive)
         * @return This builder
         * @throws IllegalArgumentException
         *             if maxIterations is not positive
         */
        public Builder maxIterations(int maxIterations) {
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("Max iterations must be positive");
            }
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the model configuration.
         *
         * @param model
         *            The model (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if model is null
         */
        public Builder model(LlmModel model) {
            this.model = Objects.requireNonNull(model, "Model cannot be null");
            return this;
        }

        /**
         * Adds a tag.
         *
         * @param tag
         *            The tag to add (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if tag is null
         */
        public Builder tag(String tag) {
            this.tags.add(Objects.requireNonNull(tag, "Tag cannot be null"));
            return this;
        }

        /**
         * Replaces all tags with the given collection.
         *
         * @param tags
         *            The tags (must not be null or contain null elements)
         * @return This builder
         * @throws NullPointerException
         *             if tags or any element is null
         */
        public Builder tags(Collection<String> tags) {
            Objects.requireNonNull(tags, "Tags cannot be null");
            final Set<String> replacement = new LinkedHashSet<>();
            for (String tag : tags) {
                replacement.add(Objects.requireNonNull(tag, "Tag cannot be null"));
            }
            this.tags = replacement;
            return this;
        }

        /**
         * Sets the allow-list from raw specification strings (e.g. {@code "Read"}, {@code "Bash(git:*)"}), parsed
         * through the same path as the {@code allowed-tools} frontmatter of an {@code agent.md}.
         *
         * @param tools
         *            The tool-specification strings (must not be null or contain null elements)
         * @return This builder
         * @throws NullPointerException
         *             if tools or any element is null
         */
        public Builder tools(List<String> tools) {
            Objects.requireNonNull(tools, "Tools cannot be null");
            this.allowedTools = tools.stream()
                    .map(spec -> AllowedTool.parse(Objects.requireNonNull(spec, "Tool specification cannot be null")))
                    .collect(Collectors.toUnmodifiableList());
            return this;
        }

        /**
         * Sets the allow-list directly from parsed {@link AllowedTool} entries.
         *
         * @param allowedTools
         *            The allowed tools (must not be null; an empty list means unrestricted)
         * @return This builder
         * @throws NullPointerException
         *             if allowedTools is null
         */
        public Builder allowedTools(List<AllowedTool> allowedTools) {
            this.allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "Allowed tools cannot be null"));
            return this;
        }

        /**
         * Builds the AgentMetadata.
         *
         * @return A new AgentMetadata
         */
        public AgentMetadata build() {
            return new AgentMetadata(this);
        }
    }
}
