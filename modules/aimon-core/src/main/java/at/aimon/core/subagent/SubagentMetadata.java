package at.aimon.core.subagent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import at.aimon.core.agent.DefinitionAttributes;
import at.aimon.core.agent.tool.permission.AllowedTool;

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

    private SubagentMetadata(String description, String whenToUse, List<AllowedTool> allowedTools, String model,
            int maxIterations, Map<String, String> attributes) {
        this.description = description;
        this.whenToUse = whenToUse;
        this.allowedTools = List.copyOf(allowedTools);
        this.model = model;
        this.maxIterations = maxIterations;
        this.attributes = DefinitionAttributes.copyOf(attributes);
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
                && Objects.equals(model, that.model) && attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, whenToUse, allowedTools, model, maxIterations, attributes);
    }

    @Override
    public String toString() {
        return "SubagentMetadata{" + "description='" + description + '\'' + ", whenToUse='" + whenToUse + '\''
                + ", allowedTools=" + allowedTools + ", model='" + model + '\'' + ", maxIterations=" + maxIterations
                + (attributes.isEmpty() ? "" : ", attributes=" + attributes) + '}';
    }

    /** Builder for SubagentMetadata. */
    public static class Builder {
        private String description;
        private String whenToUse;
        private List<AllowedTool> allowedTools = List.of();
        private String model;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private Map<String, String> attributes = Map.of();

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

        /** SubagentMetadata를 생성한다. */
        public SubagentMetadata build() {
            return new SubagentMetadata(description, whenToUse, allowedTools, model, maxIterations, attributes);
        }
    }
}
