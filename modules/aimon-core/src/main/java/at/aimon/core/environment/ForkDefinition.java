package at.aimon.core.environment;

import java.util.Map;
import java.util.Objects;

import at.aimon.core.base.DefinitionAttributes;

/**
 * What an {@link ExecutionEnvironmentProvider} is told about the subagent definition a fork runs (execution-environment
 * design §5.2): its name and its free-form attributes. A provider's binding policy reads these to decide where the
 * fork runs — a workspace sandbox picks the slot from {@code sandbox.slot}, say — the way it reads
 * {@link EnvironmentRequest#agent()} for a main turn.
 *
 * <p>
 * A value of its own rather than the subagent itself: the subagent package already depends on this one, so carrying
 * the subagent type here would close a package cycle. Only what a provider can act on is copied over. Immutable.
 */
public final class ForkDefinition {

    private final String name;
    private final Map<String, String> attributes;

    private ForkDefinition(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name must not be null");
        this.attributes = builder.attributes;
    }

    /** @return the subagent's name, as the {@code Task} tool resolved it */
    public String name() {
        return name;
    }

    /**
     * @return the subagent definition's attributes, flattened to dotted keys (never null; unmodifiable; may be empty)
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
        if (!(o instanceof ForkDefinition that)) {
            return false;
        }
        return name.equals(that.name) && attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, attributes);
    }

    @Override
    public String toString() {
        return "ForkDefinition{name=" + name + ", attributes=" + attributes + '}';
    }

    /** Builder for {@link ForkDefinition}. */
    public static final class Builder {
        private String name;
        private Map<String, String> attributes = Map.of();

        private Builder() {
        }

        /**
         * @param name
         *            the subagent's name (required)
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * @param attributes
         *            the definition's attributes, validated and copied by {@link DefinitionAttributes#copyOf(Map)}
         * @return this builder
         */
        public Builder attributes(Map<String, String> attributes) {
            this.attributes = DefinitionAttributes.copyOf(attributes);
            return this;
        }

        /** @return the definition */
        public ForkDefinition build() {
            return new ForkDefinition(this);
        }
    }
}
