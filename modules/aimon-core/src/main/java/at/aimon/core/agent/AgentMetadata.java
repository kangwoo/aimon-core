package at.aimon.core.agent;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

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

    private AgentMetadata(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "Agent name cannot be null");
        if (builder.maxIterations <= 0) {
            throw new IllegalArgumentException("Max iterations must be positive");
        }
        this.maxIterations = builder.maxIterations;
        this.model = Objects.requireNonNull(builder.model, "Model config cannot be null");
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(builder.tags));
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
                && tags.equals(that.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, maxIterations, model, tags);
    }

    @Override
    public String toString() {
        return "AgentMetadata{" + "name='" + name + "', maxIterations=" + maxIterations + ", model=" + model + ", tags="
                + tags + '}';
    }

    /** Builder for AgentMetadata. */
    public static final class Builder {
        private String name;
        private LlmModel model = LlmModel.builder().build();
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private Set<String> tags = new LinkedHashSet<>();

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
         * Builds the AgentMetadata.
         *
         * @return A new AgentMetadata
         */
        public AgentMetadata build() {
            return new AgentMetadata(this);
        }
    }
}
