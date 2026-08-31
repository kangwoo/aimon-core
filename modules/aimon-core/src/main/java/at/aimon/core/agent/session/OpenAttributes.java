package at.aimon.core.agent.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Caller-provided string attributes carried alongside a session-open request.
 *
 * <p>
 * {@code OpenAttributes} is the per-call attribute channel the {@code SessionRouter} forwards to a
 * caller-supplied {@code LiveSessionOpener} on cache miss, so application-level callers can thread caller-domain
 * identifiers (tenant, organization unit, agent id, ...) through to their custom opener without polluting the
 * {@code Agent} domain model or the {@code SessionId}.
 *
 * <h2>Re-open semantics — important</h2>
 *
 * <p>
 * Attributes are consumed <b>only on cache miss</b>, i.e., when the manager actually invokes the opener for a fresh
 * session. While a session for the same {@code SessionId} is cached on the holder node, subsequent submits with
 * different attributes have no effect on the already-open session — the manager keeps using the session built with
 * the attributes from the original open. Callers that need per-turn metadata should use
 * {@link at.aimon.core.agent.SubmitOptions} instead.
 *
 * <h2>Wire format</h2>
 *
 * <p>
 * {@code OpenAttributes} are <b>not</b> serialized into the {@code SessionInbox} or the idempotency cache.
 * Cross-node forwarding never carries attributes — every {@code submit()} that triggers an opener call is dispatched
 * with the local caller's own {@code OpenAttributes}, so each node's opener always sees attributes provided by a
 * caller of the same node.
 *
 * <h2>Key namespacing</h2>
 *
 * <p>
 * The {@code aimon.*} key prefix is reserved for framework use. Application callers should namespace their keys
 * (e.g., {@code "ops.agentId"}, {@code "ops.ouId"}) and define them as constants on their opener implementation to
 * avoid raw-string drift.
 *
 * <p>
 * <strong>Note:</strong> the {@code AgentRuntime} discriminator is <em>not</em> carried via this attribute
 * channel — it is a first-class field on {@code SubmitRequest.contextDiscriminator}, derived by the manager into
 * the {@code AgentRuntimeId} threaded through the {@code LiveSessionOpener} signature. Use
 * {@code OpenAttributes} for additional caller-domain metadata only.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     OpenAttributes attrs = OpenAttributes.builder().put("ops.agentId", agentId.value())
 *             .put("ops.ouId", ouId.value()).build();
 *
 *     SubmitRequest request = SubmitRequest.builder().sessionId(sessionId).agentRef(agentRef)
 *             .userInput(input).initiator(principal).openAttributes(attrs).build();
 * }
 * </pre>
 */
public final class OpenAttributes {

    private static final OpenAttributes EMPTY = new OpenAttributes(Collections.emptyMap());

    private final Map<String, String> values;

    private OpenAttributes(Builder builder) {
        this(Map.copyOf(builder.values));
    }

    private OpenAttributes(Map<String, String> values) {
        this.values = values;
    }

    /**
     * Returns the shared empty instance.
     *
     * @return the empty attributes (never null)
     */
    public static OpenAttributes empty() {
        return EMPTY;
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the value bound to {@code key}.
     *
     * @param key
     *            the attribute key (must not be null)
     * @return the value, or {@link Optional#empty()} when unset
     */
    public Optional<String> getString(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return Optional.ofNullable(values.get(key));
    }

    /**
     * Returns whether {@code key} is present.
     *
     * @param key
     *            the attribute key (must not be null)
     * @return {@code true} when present
     */
    public boolean has(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return values.containsKey(key);
    }

    /**
     * Returns whether this instance carries no attributes.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Returns an unmodifiable view of all attributes.
     *
     * @return an unmodifiable map (never null, may be empty)
     */
    public Map<String, String> asMap() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final OpenAttributes that = (OpenAttributes) o;
        return values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "OpenAttributes{" + values + "}";
    }

    /** Builder for {@link OpenAttributes}. */
    public static final class Builder {

        private final LinkedHashMap<String, String> values = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Adds an attribute.
         *
         * @param key
         *            the attribute key (must not be null)
         * @param value
         *            the attribute value (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             when {@code key} or {@code value} is null
         */
        public Builder put(String key, String value) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            this.values.put(key, value);
            return this;
        }

        /**
         * Adds all entries from {@code attributes}. Existing keys are overwritten.
         *
         * @param attributes
         *            the source map (must not be null; entries must not contain null keys/values)
         * @return this builder
         * @throws NullPointerException
         *             when {@code attributes} or any of its keys/values is null
         */
        public Builder putAll(Map<String, String> attributes) {
            Objects.requireNonNull(attributes, "attributes must not be null");
            attributes.forEach(this::put);
            return this;
        }

        /**
         * Builds the {@link OpenAttributes}.
         *
         * @return a new instance (never null)
         */
        public OpenAttributes build() {
            return new OpenAttributes(this);
        }
    }
}
