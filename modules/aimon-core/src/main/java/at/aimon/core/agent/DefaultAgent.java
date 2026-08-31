package at.aimon.core.agent;

import java.util.Objects;

import at.aimon.core.llm.LlmModel;

/**
 * Default implementation of Agent interface.
 *
 * <p>
 * Immutable value object built using the builder pattern.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     Agent agent = DefaultAgent.builder().name("MyAgent").maxIterations(10)
 *             .systemPrompt("You are a helpful assistant...").modelConfig(LlmModel.builder().temperature(0.7).build())
 *             .build();
 * }
 * </pre>
 */
public final class DefaultAgent implements Agent {

    private final AgentMetadata metadata;
    private final AgentContent content;

    /**
     * Creates a new DefaultAgent.
     *
     * @param metadata
     *            The agent metadata
     * @param content
     *            The agent content
     */
    private DefaultAgent(AgentMetadata metadata, AgentContent content) {
        this.metadata = Objects.requireNonNull(metadata, "Metadata cannot be null");
        this.content = Objects.requireNonNull(content, "Content cannot be null");
    }

    /**
     * Creates a new builder.
     *
     * @return A new DefaultAgent.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public AgentMetadata getMetadata() {
        return metadata;
    }

    @Override
    public AgentContent getContent() {
        return content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultAgent that = (DefaultAgent) o;
        return metadata.equals(that.metadata) && content.equals(that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadata, content);
    }

    @Override
    public String toString() {
        return "DefaultAgent{" + "metadata=" + metadata + ", content=" + content + '}';
    }

    /** Builder for DefaultAgent. */
    public static final class Builder {
        private AgentMetadata metadata;
        private AgentContent content;
        private AgentMetadata.Builder metadataBuilder;
        private AgentContent.Builder contentBuilder;

        private Builder() {
        }

        /**
         * Sets the agent metadata.
         *
         * @param metadata
         *            The agent metadata (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if metadata is null
         */
        public Builder metadata(AgentMetadata metadata) {
            this.metadata = Objects.requireNonNull(metadata, "Metadata cannot be null");
            this.metadataBuilder = null;
            return this;
        }

        /**
         * Sets the agent content.
         *
         * @param content
         *            The agent content (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if content is null
         */
        public Builder content(AgentContent content) {
            this.content = Objects.requireNonNull(content, "Content cannot be null");
            this.contentBuilder = null;
            return this;
        }

        /**
         * 에이전트 이름을 설정한다.
         *
         * @param name
         *            에이전트 이름
         * @return This builder
         */
        public Builder name(String name) {
            ensureMetadataBuilder();
            this.metadataBuilder.name(name);
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
            ensureMetadataBuilder();
            this.metadataBuilder.maxIterations(maxIterations);
            return this;
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
            ensureContentBuilder();
            this.contentBuilder.systemPrompt(systemPrompt);
            return this;
        }

        /**
         * Sets the model configuration.
         *
         * @param modelConfig
         *            The model configuration (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if modelConfig is null
         */
        public Builder model(LlmModel modelConfig) {
            ensureMetadataBuilder();
            this.metadataBuilder.model(modelConfig);
            return this;
        }

        private void ensureMetadataBuilder() {
            if (metadataBuilder == null) {
                metadataBuilder = AgentMetadata.builder();
            }
        }

        private void ensureContentBuilder() {
            if (contentBuilder == null) {
                contentBuilder = AgentContent.builder();
            }
        }

        /**
         * Builds the DefaultAgent.
         *
         * @return A new DefaultAgent
         * @throws IllegalStateException
         *             if neither metadata nor name/model has been set, or if neither content nor systemPrompt has been
         *             set
         */
        public DefaultAgent build() {
            if (metadata == null && metadataBuilder == null) {
                throw new IllegalStateException("Agent metadata must be set (use metadata() or name()/model())");
            }
            if (content == null && contentBuilder == null) {
                throw new IllegalStateException("Agent content must be set (use content() or systemPrompt())");
            }
            AgentMetadata finalMetadata = metadata != null ? metadata : metadataBuilder.build();
            AgentContent finalContent = content != null ? content : contentBuilder.build();
            return new DefaultAgent(finalMetadata, finalContent);
        }
    }
}
