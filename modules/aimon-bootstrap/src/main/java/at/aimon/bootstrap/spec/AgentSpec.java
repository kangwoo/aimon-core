package at.aimon.bootstrap.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.impl.AgentBundle;

/**
 * Declares one agent the stack should stand a runtime up for.
 *
 * <p>
 * An agent is identified either by <b>name</b> — resolved through the stack's bundle loader against
 * {@code agents/<name>/} on the class path or the workspace — or by a <b>pre-built bundle</b>, for callers that
 * assembled the definition themselves (tests, code-defined agents, a Spring slice that read the definition
 * from properties).
 *
 * <h2>The name is the ref, and the bundle it is read from can be another</h2>
 *
 * <p>
 * {@link #getName()} is the <b>ref</b>: the name segment of the runtime id, and therefore what a session submit
 * routes on. {@link Builder#bundleName(String)} says where the definition is read from and defaults to the ref,
 * so the two coincide unless a host deliberately points two refs at one bundle — one deployment of a shared
 * definition per business unit, say. The ref wins over the name declared inside the bundle: an id derived from
 * the definition file would mean a host cannot know what to route on without opening it, and a frontmatter edit
 * would silently move an agent out from under every caller.
 *
 * <p>
 * The optional {@code discriminator} splits one agent definition into several runtimes (per tenant, per user,
 * per environment). It becomes the suffix of the runtime id — {@code agent:<name>:<discriminator>} — and must
 * therefore be non-blank and free of {@code ':'}. Leaving it empty yields the plain {@code agent:<name>} id,
 * which is what a session submit without a context discriminator resolves to.
 *
 * <p>
 * {@link Builder#addCustomizer(AgentRuntimeCustomizer) Customizers} are the seam for anything the embedding
 * application registers on the runtime itself — front-end tools, display hooks. They run after the runtime is
 * built and before it is published. {@link Builder#properties(Map) Properties} are the seam for anything an
 * {@link AimonAgentCustomizer} needs in order to decide what this particular agent gets.
 */
public final class AgentSpec {

    private final String name;
    private final String bundleName;
    private final AgentBundle bundle;
    private final String discriminator;
    private final List<AgentRuntimeCustomizer> customizers;
    private final Map<String, String> properties;

    private AgentSpec(Builder builder) {
        this.bundle = builder.bundle;
        // An explicit name is the ref and wins; a bundle supplied without one names itself.
        this.name = (builder.name != null)
                ? builder.name
                : (builder.bundle != null) ? builder.bundle.getAgent().getName() : null;
        this.bundleName = (builder.bundleName != null) ? builder.bundleName : this.name;
        this.discriminator = builder.discriminator;
        this.customizers = List.copyOf(builder.customizers);
        this.properties = Map.copyOf(builder.properties);

        Objects.requireNonNull(this.name, "Agent spec needs either a name or a bundle");
        if (this.name.isBlank()) {
            throw new IllegalArgumentException("Agent name must not be blank");
        }
        if (this.bundleName.isBlank()) {
            throw new IllegalArgumentException("Agent bundle name must not be blank when present");
        }
        if (this.discriminator != null) {
            if (this.discriminator.isBlank()) {
                throw new IllegalArgumentException("Agent discriminator must not be blank when present");
            }
            if (this.discriminator.indexOf(':') >= 0) {
                throw new IllegalArgumentException(
                        "Agent discriminator must not contain ':' (it separates the id segments): "
                                + this.discriminator);
            }
        }
    }

    /**
     * Creates a spec for an agent resolved by name through the stack's bundle loader.
     *
     * @param name
     *            the agent name (must not be null or blank)
     * @return the spec
     */
    public static AgentSpec named(String name) {
        return builder().name(name).build();
    }

    /**
     * Creates a spec for an already-loaded bundle.
     *
     * @param bundle
     *            the agent bundle (must not be null)
     * @return the spec
     */
    public static AgentSpec of(AgentBundle bundle) {
        return builder().bundle(bundle).build();
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the agent ref — the name segment of the runtime id, taken from the bundle when the caller supplied
     * one without naming it.
     *
     * @return the agent name, never null
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the bundle directory the definition is read from, which is the ref unless the caller pointed this
     * agent at another bundle.
     *
     * @return the bundle name, never null
     */
    public String getBundleName() {
        return bundleName;
    }

    /**
     * Returns the pre-built bundle, when the caller supplied one instead of a name.
     *
     * @return the bundle, or empty when the stack must load it by name
     */
    public Optional<AgentBundle> getBundle() {
        return Optional.ofNullable(bundle);
    }

    /**
     * Returns the runtime-id discriminator.
     *
     * @return the discriminator, or empty for the plain {@code agent:<name>} id
     */
    public Optional<String> getDiscriminator() {
        return Optional.ofNullable(discriminator);
    }

    /**
     * Returns the customizers to run against the new runtime, in registration order.
     *
     * @return an immutable list, possibly empty
     */
    public List<AgentRuntimeCustomizer> getCustomizers() {
        return customizers;
    }

    /**
     * Returns the host-supplied properties describing this agent, which reach every
     * {@link AimonAgentCustomizer} through {@link AgentDescriptor#getProperties()}.
     *
     * @return an immutable map, possibly empty
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public String toString() {
        return "AgentSpec[" + name + (discriminator != null ? ":" + discriminator : "") + "]";
    }

    /** Builder for {@link AgentSpec}. */
    public static final class Builder {

        private String name;
        private String bundleName;
        private AgentBundle bundle;
        private String discriminator;
        private final List<AgentRuntimeCustomizer> customizers = new ArrayList<>();
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the agent ref — the name segment of the runtime id, and the bundle to load unless
         * {@link #bundleName(String)} says otherwise.
         *
         * @param name
         *            the agent name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Points this agent at a bundle directory other than its ref.
         *
         * <p>
         * Two agents may name the same bundle. They get separate runtimes, separate file systems and separate
         * ids; what they share is the definition they were read from.
         *
         * @param bundleName
         *            the bundle directory, or null to use the ref
         * @return this builder
         */
        public Builder bundleName(String bundleName) {
            this.bundleName = bundleName;
            return this;
        }

        /**
         * Supplies an already-loaded bundle, bypassing the loader. Its agent name becomes the ref unless
         * {@link #name} sets one.
         *
         * @param bundle
         *            the agent bundle
         * @return this builder
         */
        public Builder bundle(AgentBundle bundle) {
            this.bundle = bundle;
            return this;
        }

        /**
         * Adds properties describing this agent, for {@link AimonAgentCustomizer} implementations to decide on.
         *
         * <p>
         * Additive, so several sources can contribute; later values replace earlier ones under the same key.
         *
         * @param properties
         *            the properties (must not be null)
         * @return this builder
         */
        public Builder properties(Map<String, String> properties) {
            this.properties.putAll(Objects.requireNonNull(properties, "properties must not be null"));
            return this;
        }

        /**
         * Adds one property.
         *
         * @param key
         *            the key (must not be null)
         * @param value
         *            the value (must not be null)
         * @return this builder
         */
        public Builder property(String key, String value) {
            this.properties.put(Objects.requireNonNull(key, "key must not be null"),
                    Objects.requireNonNull(value, "value must not be null"));
            return this;
        }

        /**
         * Sets the runtime-id discriminator.
         *
         * @param discriminator
         *            a non-blank string without {@code ':'}
         * @return this builder
         */
        public Builder discriminator(String discriminator) {
            this.discriminator = discriminator;
            return this;
        }

        /**
         * Adds a customizer to run against the runtime before it is registered.
         *
         * <p>
         * Customizers run in the order added. Adding is additive — this never replaces earlier entries — because
         * the common case is several independent contributions (tools here, hooks there) that must not have to
         * know about each other.
         *
         * @param customizer
         *            the customizer (must not be null)
         * @return this builder
         */
        public Builder addCustomizer(AgentRuntimeCustomizer customizer) {
            this.customizers.add(Objects.requireNonNull(customizer, "customizer must not be null"));
            return this;
        }

        /**
         * Builds the spec.
         *
         * @return the immutable spec
         */
        public AgentSpec build() {
            return new AgentSpec(this);
        }
    }
}
