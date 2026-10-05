package at.aimon.core.subagent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.base.DefinitionAttributes;

/**
 * Subagent metadata including description, tools, model, permissions.
 *
 * <p>
 * Stores parsed AllowedTool objects directly, eliminating the need for lazy parsing. This simplifies the design while
 * maintaining efficiency as parsing happens once during construction.
 *
 * <p>
 * Reuses Command system's AllowedTool for consistency and proven reliability.
 *
 * <p>
 * The description field should include when and how to use this subagent.
 *
 * <p>
 * Immutable value object.
 */
public final class SubagentMetadata {
    private static final int DEFAULT_MAX_ITERATIONS = 1000;

    /**
     * Returns a new builder for SubagentMetadata.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String description;
    private final String whenToUse; // Optional trigger conditions for selecting this subagent
    private final List<AllowedTool> allowedTools; // Parsed AllowedTool objects
    private final String model; // model id, sent as written; null or empty inherits the parent's
    private final int maxIterations; // Maximum ReAct loop iterations
    private final Map<String, String> attributes; // Free-form, flattened; carried, never read by the framework
    private final boolean hidden; // Not offered to the model, and refused if the model names it

    private SubagentMetadata(Builder builder) {
        this.description = builder.description;
        this.whenToUse = builder.whenToUse;
        this.allowedTools = List.copyOf(builder.allowedTools);
        this.model = builder.model;
        this.maxIterations = builder.maxIterations;
        this.attributes = DefinitionAttributes.copyOf(builder.attributes);
        this.hidden = builder.hidden;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Returns the optional trigger conditions describing when the calling model should select this subagent.
     *
     * @return the when-to-use text, or null when unset
     */
    public String getWhenToUse() {
        return whenToUse;
    }

    /**
     * Returns the list of allowed tools for this subagent.
     *
     * @return An immutable list of AllowedTool objects (never null, may be empty)
     */
    public List<AllowedTool> getAllowedTools() {
        return allowedTools;
    }

    public String getModel() {
        return model;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Returns the free-form attributes from the definition's {@code attributes} frontmatter, flattened to dotted keys
     * ({@code sandbox.slot}). The framework carries them and never reads them: they are for a component it does not
     * know about, such as an execution environment provider picking the sandbox a fork runs in (see
     * {@link DefinitionAttributes}).
     *
     * @return an unmodifiable map (never null, may be empty)
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /**
     * Whether this definition is hidden from the model: the {@code Task} tool does not list it among the available
     * subagents and refuses a call that names it. Set with {@code hidden: true} in the definition's frontmatter or
     * {@link Builder#hidden(boolean)}.
     *
     * <p>
     * It is for a definition that exists to be <em>looked up</em> rather than launched — one registered under a
     * built-in {@code Workflow} role name ({@code workflow-judge} …) or a GraalJS {@code agentType} only to give those
     * steps their {@linkplain #getAttributes() attributes}. Hiding it does not unregister it: the registry still
     * resolves the name, so the workflow tools, a fork-mode skill's {@code agent:} and code that spawns it directly
     * work as before, and an operator's {@code /agents} listing still shows it, marked hidden.
     *
     * @return {@code true} if the model is neither shown nor allowed to launch this subagent; {@code false} by default
     */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * Checks if this metadata has tools restrictions.
     *
     * @return true if there are tools defined, false otherwise
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
        final SubagentMetadata that = (SubagentMetadata) o;
        return maxIterations == that.maxIterations && Objects.equals(description, that.description)
                && Objects.equals(whenToUse, that.whenToUse) && Objects.equals(allowedTools, that.allowedTools)
                && Objects.equals(model, that.model) && attributes.equals(that.attributes) && hidden == that.hidden;
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, whenToUse, allowedTools, model, maxIterations, attributes, hidden);
    }

    @Override
    public String toString() {
        return "SubagentMetadata{" + "description='" + description + '\'' + ", whenToUse='" + whenToUse + '\''
                + ", allowedTools=" + allowedTools + ", model='" + model + '\'' + ", maxIterations=" + maxIterations
                + (attributes.isEmpty() ? "" : ", attributes=" + attributes) + (hidden ? ", hidden=true" : "") + '}';
    }

    /** Builder for SubagentMetadata. */
    public static class Builder {
        private String description;
        private String whenToUse;
        private List<AllowedTool> allowedTools = List.of();
        private String model;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private Map<String, String> attributes = Map.of();
        private boolean hidden;

        /** description을 설정한다. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** when-to-use(선택적 트리거 조건)를 설정한다. */
        public Builder whenToUse(String whenToUse) {
            this.whenToUse = whenToUse;
            return this;
        }

        /**
         * Sets the allowed tools from a list of tools specification strings.
         *
         * @param tools
         *            List of tools specification strings (e.g., "Read", "Bash(git:*)")
         * @return This builder instance
         */
        public Builder tools(List<String> tools) {
            allowedTools = tools.stream().map(AllowedTool::parse).collect(Collectors.toUnmodifiableList());
            return this;
        }

        /**
         * Sets the allowed tools directly from AllowedTool objects.
         *
         * @param allowedTools
         *            List of AllowedTool objects
         * @return This builder instance
         */
        public Builder allowedTools(List<AllowedTool> allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        /** model을 설정한다. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** maxIterations를 설정한다. */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the free-form attributes.
         *
         * @param attributes
         *            the attributes, already flat (must not be null, nor contain null keys or values)
         * @return This builder instance
         */
        public Builder attributes(Map<String, String> attributes) {
            this.attributes = DefinitionAttributes.copyOf(attributes);
            return this;
        }

        /**
         * Sets whether the definition is hidden from the model (see {@link SubagentMetadata#isHidden()}).
         *
         * @param hidden
         *            {@code true} to keep the definition out of the {@code Task} tool's list and refuse a call that
         *            names it; {@code false} (the default) for an ordinary subagent
         * @return This builder instance
         */
        public Builder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        /** SubagentMetadata를 생성한다. */
        public SubagentMetadata build() {
            return new SubagentMetadata(this);
        }
    }
}
