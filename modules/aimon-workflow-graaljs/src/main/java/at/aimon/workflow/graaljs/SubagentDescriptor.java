package at.aimon.workflow.graaljs;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.DefinitionAttributes;

/**
 * The identity fields of one JS agent descriptor — everything a {@link SubagentResolver} needs to build the step's
 * {@code Subagent}, and nothing about the step itself (goal, schema, label stay on the {@code AgentTask}).
 *
 * <p>
 * One argument instead of a positional list, so a field added later (as {@code attributes} was, EE-42) does not break
 * every resolver. The builder normalizes what every resolver would otherwise re-check: a blank text is absent,
 * {@code tools} is never null, and {@code attributes} is validated by {@link DefinitionAttributes#copyOf(Map)}.
 * Immutable.
 */
public final class SubagentDescriptor {

    private final String agentType;
    private final String systemPrompt;
    private final String model;
    private final List<String> tools;
    private final Integer maxIterations;
    private final Map<String, String> attributes;

    private SubagentDescriptor(Builder builder) {
        this.agentType = builder.agentType;
        this.systemPrompt = builder.systemPrompt;
        this.model = builder.model;
        this.tools = builder.tools;
        this.maxIterations = builder.maxIterations;
        this.attributes = builder.attributes;
    }

    /** @return the logical type: basis for the synthesized name and default prompt, and the registry lookup key */
    public Optional<String> agentType() {
        return Optional.ofNullable(agentType);
    }

    /** @return the explicit system prompt, if the script gave one */
    public Optional<String> systemPrompt() {
        return Optional.ofNullable(systemPrompt);
    }

    /** @return the model override, if any */
    public Optional<String> model() {
        return Optional.ofNullable(model);
    }

    /** @return the flat tool-name allow-list (never null; unmodifiable; empty means unrestricted) */
    public List<String> tools() {
        return tools;
    }

    /** @return the iteration cap, if any (the core default applies when absent) */
    public Optional<Integer> maxIterations() {
        return Optional.ofNullable(maxIterations);
    }

    /**
     * @return the attributes the script gave, flattened to dotted keys (never null; unmodifiable; may be empty) — laid
     *         over a registered definition's by the default resolver
     */
    public Map<String, String> attributes() {
        return attributes;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SubagentDescriptor that)) {
            return false;
        }
        return Objects.equals(agentType, that.agentType) && Objects.equals(systemPrompt, that.systemPrompt)
                && Objects.equals(model, that.model) && tools.equals(that.tools)
                && Objects.equals(maxIterations, that.maxIterations) && attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentType, systemPrompt, model, tools, maxIterations, attributes);
    }

    /**
     * Omits {@code systemPrompt} on purpose: a script's prompt can be long and may carry sensitive content, and this
     * string ends up in logs and assertion messages. Use {@link #systemPrompt()} when the prompt itself is needed.
     */
    @Override
    public String toString() {
        return "SubagentDescriptor{agentType=" + agentType + ", model=" + model + ", tools=" + tools
                + ", maxIterations=" + maxIterations + ", attributes=" + attributes + '}';
    }

    /** Builder for {@link SubagentDescriptor}. */
    public static final class Builder {
        private String agentType;
        private String systemPrompt;
        private String model;
        private List<String> tools = List.of();
        private Integer maxIterations;
        private Map<String, String> attributes = Map.of();

        private Builder() {
        }

        /**
         * @param agentType
         *            the logical type (null or blank means absent)
         * @return this builder
         */
        public Builder agentType(String agentType) {
            this.agentType = blankToNull(agentType);
            return this;
        }

        /**
         * @param systemPrompt
         *            the explicit system prompt (null or blank means absent)
         * @return this builder
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = blankToNull(systemPrompt);
            return this;
        }

        /**
         * @param model
         *            the model override (null or blank means absent)
         * @return this builder
         */
        public Builder model(String model) {
            this.model = blankToNull(model);
            return this;
        }

        /**
         * @param tools
         *            the tool-name allow-list (null means unrestricted, like an empty list); copied
         * @return this builder
         */
        public Builder tools(List<String> tools) {
            this.tools = tools == null ? List.of() : List.copyOf(tools);
            return this;
        }

        /**
         * @param maxIterations
         *            the iteration cap (null means the core default)
         * @return this builder
         */
        public Builder maxIterations(Integer maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * @param attributes
         *            the attributes, already flat (must not be null); validated and copied by
         *            {@link DefinitionAttributes#copyOf(Map)}
         * @return this builder
         */
        public Builder attributes(Map<String, String> attributes) {
            this.attributes = DefinitionAttributes.copyOf(attributes);
            return this;
        }

        /** @return the descriptor */
        public SubagentDescriptor build() {
            return new SubagentDescriptor(this);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
