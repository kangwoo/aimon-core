package at.aimon.bootstrap.spec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.AgentBundle;

/**
 * What one agent runtime is being built from — the value an {@link AimonAgentCustomizer} decides on.
 *
 * <p>
 * It exists so that the customizer interface takes <b>one</b> parameter. The obvious alternative is to pass what
 * each decision needs — the ref here, the ref and the bundle there, the ref and the bundle and the properties
 * for the third — and the shape that produces is an interface of overload pairs where every new fact is a new
 * method and a new default that forwards to the old one. Adding a fact to a descriptor breaks nobody; adding a
 * parameter breaks every implementation.
 *
 * <h2>The ref is not the bundle name</h2>
 *
 * <p>
 * {@link #getAgentRef()} is what a session submit routes on and what appears in the runtime id;
 * {@link #getBundleName()} is the directory the definition was read from. They are the same in the common case
 * and deliberately separable, because a host that has two deployments of one bundle needs two refs for it. Match
 * on the ref in {@link AimonAgentCustomizer#supports(AgentDescriptor)} unless you specifically mean "every agent
 * built from this bundle".
 *
 * <h2>The discriminator is present for tenant runtimes and absent for the startup ones</h2>
 *
 * <p>
 * A customizer that contributes a tool holding tenant-scoped credentials must read {@link #getDiscriminator()}
 * and not assume it is there: the same customizer is asked for {@code agent:ops} at startup, which has no
 * tenant. Treating absent as a tenant name would give the startup runtime one customer's credentials.
 */
public final class AgentDescriptor {

    private final String agentRef;
    private final String bundleName;
    private final AgentBundle bundle;
    private final String discriminator;
    private final Map<String, String> properties;

    private AgentDescriptor(Builder builder) {
        this.agentRef = Objects.requireNonNull(builder.agentRef, "agentRef must not be null");
        this.bundle = Objects.requireNonNull(builder.bundle, "bundle must not be null");
        this.bundleName = builder.bundleName != null ? builder.bundleName : this.agentRef;
        this.discriminator = builder.discriminator;
        this.properties = Map.copyOf(builder.properties);
    }

    /**
     * Creates a builder for the given agent ref and bundle.
     *
     * @param agentRef
     *            the name segment of the runtime id (must not be null)
     * @param bundle
     *            the loaded bundle (must not be null)
     * @return a new builder
     */
    public static Builder builder(String agentRef, AgentBundle bundle) {
        return new Builder(agentRef, bundle);
    }

    /**
     * Returns the agent ref — the name a submit routes on, and the name segment of the runtime id.
     *
     * @return the ref, never null
     */
    public String getAgentRef() {
        return agentRef;
    }

    /**
     * Returns the bundle directory the definition was read from, which equals the ref unless the host pointed
     * one ref at another bundle.
     *
     * @return the bundle name, never null
     */
    public String getBundleName() {
        return bundleName;
    }

    /**
     * Returns the loaded bundle — the agent definition, its subagents and its bundled skills.
     *
     * @return the bundle, never null
     */
    public AgentBundle getBundle() {
        return bundle;
    }

    /**
     * Returns the tenant this runtime is for.
     *
     * @return the discriminator, or empty for the startup runtime {@code agent:<ref>}
     */
    public Optional<String> getDiscriminator() {
        return Optional.ofNullable(discriminator);
    }

    /**
     * Returns the id of the runtime being described.
     *
     * @return the runtime id, never null
     */
    public AgentRuntimeId getRuntimeId() {
        return discriminator == null
                ? AgentRuntimeId.fromName(agentRef)
                : AgentRuntimeId.fromName(agentRef, discriminator);
    }

    /**
     * Returns the host-supplied properties for this agent, already merged from every source that contributes
     * them.
     *
     * @return an immutable map, possibly empty
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * Returns one property.
     *
     * @param key
     *            the property key (must not be null)
     * @return the value, or empty when the agent does not carry that key
     */
    public Optional<String> getProperty(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return Optional.ofNullable(properties.get(key));
    }

    @Override
    public String toString() {
        return "AgentDescriptor[" + getRuntimeId() + (bundleName.equals(agentRef) ? "" : " from " + bundleName) + "]";
    }

    /** Builder for {@link AgentDescriptor}. */
    public static final class Builder {

        private final String agentRef;
        private final AgentBundle bundle;
        private String bundleName;
        private String discriminator;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(String agentRef, AgentBundle bundle) {
            this.agentRef = agentRef;
            this.bundle = bundle;
        }

        /**
         * Sets the bundle directory the definition came from. Defaults to the ref.
         *
         * @param bundleName
         *            the bundle name, may be null
         * @return this builder
         */
        public Builder bundleName(String bundleName) {
            this.bundleName = bundleName;
            return this;
        }

        /**
         * Sets the tenant this runtime serves.
         *
         * @param discriminator
         *            the discriminator, or null for the startup runtime
         * @return this builder
         */
        public Builder discriminator(String discriminator) {
            this.discriminator = discriminator;
            return this;
        }

        /**
         * Adds the given properties, keeping any already added under other keys.
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
         * Builds the descriptor.
         *
         * @return the immutable descriptor
         */
        public AgentDescriptor build() {
            return new AgentDescriptor(this);
        }
    }
}
