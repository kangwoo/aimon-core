package at.aimon.core.subagent;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.tool.permission.AllowedTool;

/**
 * Contract for all subagents in the system.
 *
 * <p>
 * All subagents are defined in agents/*.md files with YAML frontmatter. They execute using the same unified execution
 * model (ReAct loop).
 *
 * <p>
 * This design simplifies the architecture by using a single type for all subagents, unlike the Command system which has
 * SystemCommand and CustomCommand.
 *
 * <p>
 * Immutable value object.
 *
 * @see SubagentMetadata
 * @see SubagentContent
 */
public final class Subagent {
    /**
     * Creates a new Subagent with the given name, metadata, and content.
     *
     * @param name
     *            The subagent name (must not be null)
     * @param metadata
     *            The subagent metadata (must not be null)
     * @param content
     *            The subagent content (must not be null)
     * @return A new Subagent instance
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static Subagent of(String name, SubagentMetadata metadata, SubagentContent content) {
        return new Subagent(name, metadata, content);
    }

    /**
     * Returns a new fluent builder for defining a subagent in code.
     *
     * <p>
     * The builder produces a {@link Subagent} value object identical to one parsed from an equivalent
     * {@code agents/*.md} file: it delegates to {@link SubagentMetadata#builder()} and
     * {@link SubagentContent#of(String)}
     * and reuses the same {@code AllowedTool} parsing. Unset optional fields fall back to the exact defaults the
     * markdown parser relies on (e.g. {@code maxIterations} defaults to 1000; unset
     * {@code model}/{@code whenToUse}
     * stay {@code null}; no tool restrictions when {@code tools} is unset).
     *
     * <p>
     * {@code name} and {@code systemPrompt} are required; {@link Builder#build()} rejects null values for either.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String name;
    private final SubagentMetadata metadata;
    private final SubagentContent content;

    private Subagent(String name, SubagentMetadata metadata, SubagentContent content) {
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.metadata = Objects.requireNonNull(metadata, "Metadata cannot be null");
        this.content = Objects.requireNonNull(content, "Content cannot be null");
    }

    public String getName() {
        return name;
    }

    public SubagentMetadata getMetadata() {
        return metadata;
    }

    public SubagentContent getContent() {
        return content;
    }

    /**
     * Convenience method to get the maximum number of iterations.
     *
     * @return The maximum iterations
     */
    public int getMaxIterations() {
        return getMetadata().getMaxIterations();
    }

    /**
     * Returns the list of allowed tools for this subagent.
     *
     * <p>
     * Delegates to metadata for convenience.
     *
     * @return An immutable list of AllowedTool objects (never null, may be empty)
     */
    public List<AllowedTool> getAllowedTools() {
        return metadata.getAllowedTools();
    }

    /**
     * Checks if this subagent has tools restrictions.
     *
     * <p>
     * Delegates to metadata for convenience.
     *
     * @return true if there are tools restrictions, false otherwise
     */
    public boolean hasToolRestrictions() {
        return metadata.hasToolRestrictions();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Subagent subagent = (Subagent) o;
        return Objects.equals(name, subagent.name) && Objects.equals(metadata, subagent.metadata)
                && Objects.equals(content, subagent.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, metadata, content);
    }

    @Override
    public String toString() {
        return "Subagent{" + "name='" + name + '\'' + ", metadata=" + metadata + ", content=" + content + '}';
    }

    /**
     * Fluent builder for code-defined subagents.
     *
     * <p>
     * Forwards only the values the caller actually sets to {@link SubagentMetadata.Builder}, leaving the
     * markdown-equivalent defaults intact for everything else (so a code-defined subagent equals an equivalent markdown
     * definition). Use {@link #tools(List)} for raw tool-specification strings (e.g. {@code "Bash(psql:*)"}); they are
     * parsed via the same path as markdown {@code allowed-tools}.
     */
    public static final class Builder {
        private String name;
        private String systemPrompt;
        private String description;
        private String whenToUse;
        private List<String> tools;
        private String model;
        private Integer maxIterations;

        private Builder() {
        }

        /** Sets the subagent name (required). */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Sets the system prompt / instructional body (required). */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** Sets the description that tells the calling model when and how to use this subagent. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the optional trigger conditions describing when to select this subagent. When unset, stays {@code null}.
         */
        public Builder whenToUse(String whenToUse) {
            this.whenToUse = whenToUse;
            return this;
        }

        /**
         * Sets the allowed tools from raw specification strings (e.g. {@code "Read"}, {@code "Bash(git:*)"}), parsed
         * via
         * the same path as markdown {@code allowed-tools}. When unset, the subagent has no tool restrictions.
         *
         * @param tools
         *            the tool-specification strings
         * @return this builder
         */
        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        /** Sets the model alias (e.g. {@code "sonnet"}). When unset, the executor default applies. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the maximum ReAct loop iterations. When unset, defaults to 1000 (markdown parity). */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Builds the immutable {@link Subagent}.
         *
         * @return a new Subagent instance
         * @throws NullPointerException
         *             if {@code name} or {@code systemPrompt} was not set
         */
        public Subagent build() {
            Objects.requireNonNull(name, "name cannot be null");
            Objects.requireNonNull(systemPrompt, "systemPrompt cannot be null");

            final SubagentMetadata.Builder metadataBuilder = SubagentMetadata.builder();
            if (description != null) {
                metadataBuilder.description(description);
            }
            if (whenToUse != null) {
                metadataBuilder.whenToUse(whenToUse);
            }
            if (tools != null) {
                metadataBuilder.tools(tools);
            }
            if (model != null) {
                metadataBuilder.model(model);
            }
            if (maxIterations != null) {
                metadataBuilder.maxIterations(maxIterations);
            }
            return new Subagent(name, metadataBuilder.build(), SubagentContent.of(systemPrompt));
        }
    }
}
