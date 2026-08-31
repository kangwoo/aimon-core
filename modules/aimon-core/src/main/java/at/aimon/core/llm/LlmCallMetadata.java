package at.aimon.core.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.Principal;

/**
 * Observability metadata attached to an LLM call for usage attribution.
 *
 * <p>
 * Unlike {@link LlmModel} (which controls generation parameters), this value object carries contextual tags that
 * identify <em>who</em> is calling the LLM and <em>why</em>, enabling per-component, per-feature, or per-principal
 * usage aggregation.
 *
 * <p>
 * Typical tags:
 *
 * <ul>
 * <li>{@code component} — e.g. "orca-agent", "wiki-rerank", "subagent"
 * <li>{@code parentComponent} — the component that invoked this one (e.g. an orca agent that spawned a subagent)
 * <li>{@code feature} — e.g. "react-loop", "tool-search"
 * <li>{@code principal} — the invoking user/service
 * <li>{@code traceId} — correlation id across calls
 * <li>{@code tags} — free-form key/value pairs
 * </ul>
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * LlmCallMetadata metadata = LlmCallMetadata.builder()
 *         .component("orca-agent")
 *         .feature("react-loop")
 *         .traceId(runId)
 *         .tag("tenant", "acme")
 *         .build();
 *
 * LlmResponse response = client.sendMessage(systemPrompt, messages, tools, model, metadata);
 * }
 * </pre>
 */
public final class LlmCallMetadata {

    /**
     * Well-known {@code feature} tag values used by the framework to categorize LLM calls.
     *
     * <p>
     * Components may define additional values; these constants are reserved for framework-level use so that
     * interceptors and metrics can reliably distinguish framework calls (e.g., a compaction summary call) from
     * application-driven calls (e.g., the main ReAct loop).
     */
    public static final class Feature {
        /** The main ReAct loop iteration LLM call inside an agent execution. */
        public static final String REACT_LOOP = "react-loop";
        /** A compaction summary LLM call performed by the conversation compaction engine. */
        public static final String COMPACTION = "compaction";

        private Feature() {
        }
    }

    private static final LlmCallMetadata EMPTY = new LlmCallMetadata(new Builder());

    private final String component;
    private final String parentComponent;
    private final String feature;
    private final Principal principal;
    private final String traceId;
    private final Map<String, String> tags;

    private LlmCallMetadata(Builder builder) {
        this.component = builder.component;
        this.parentComponent = builder.parentComponent;
        this.feature = builder.feature;
        this.principal = builder.principal;
        this.traceId = builder.traceId;
        this.tags = builder.tags != null ? Map.copyOf(builder.tags) : Map.of();
    }

    /**
     * Returns an empty metadata instance carrying no attribution information.
     *
     * @return the shared empty instance (never null)
     */
    public static LlmCallMetadata empty() {
        return EMPTY;
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> getComponent() {
        return Optional.ofNullable(component);
    }

    /**
     * Returns the component that invoked this call, when the current {@link #getComponent() component} was dispatched
     * from another component (e.g., a subagent spawned by an orca agent).
     *
     * <p>
     * Only the immediate parent is tracked — the full ancestry chain is not preserved. When a subagent spawns another
     * subagent, the inner call's {@code parentComponent} reflects the outer subagent, not the original orca agent.
     *
     * @return the parent component name, or empty if not set
     */
    public Optional<String> getParentComponent() {
        return Optional.ofNullable(parentComponent);
    }

    public Optional<String> getFeature() {
        return Optional.ofNullable(feature);
    }

    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    public Optional<String> getTraceId() {
        return Optional.ofNullable(traceId);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    /**
     * Returns true when this instance carries no attribution information.
     *
     * @return true if all fields are unset
     */
    public boolean isEmpty() {
        return component == null && parentComponent == null && feature == null && principal == null && traceId == null
                && tags.isEmpty();
    }

    /**
     * Returns a new metadata instance with the given tags added on top of this instance's tags. Existing keys are
     * overwritten by the supplied map. Other fields (component, feature, principal, traceId) are preserved unchanged.
     *
     * <p>
     * Used by call sites that own static metadata at construction time but need to attach per-call attribution (e.g.,
     * a wiki search strategy adding {@code WikiScope} tags to each LLM call).
     *
     * @param additionalTags
     *            tags to merge in (must not be null; pass an empty map for no-op)
     * @return a new metadata instance with the merged tags (never null)
     */
    public LlmCallMetadata withTags(Map<String, String> additionalTags) {
        Objects.requireNonNull(additionalTags, "Additional tags cannot be null");
        if (additionalTags.isEmpty()) {
            return this;
        }
        final Builder copy = new Builder();
        copy.component = this.component;
        copy.parentComponent = this.parentComponent;
        copy.feature = this.feature;
        copy.principal = this.principal;
        copy.traceId = this.traceId;
        copy.tags = new LinkedHashMap<>(this.tags);
        copy.tags.putAll(additionalTags);
        return new LlmCallMetadata(copy);
    }

    /**
     * Returns a new metadata instance where this instance's fields take precedence and any unset fields fall back to
     * {@code defaults}. Tag maps are merged with {@code this} winning on key collision.
     *
     * <p>
     * Used by framework integration points to combine caller-supplied metadata with auto-derived defaults (component,
     * feature, traceId) without losing the caller's intent.
     *
     * @param defaults
     *            the fallback metadata (must not be null; pass {@link #empty()} for none)
     * @return a merged metadata instance (never null)
     */
    public LlmCallMetadata withDefaults(LlmCallMetadata defaults) {
        Objects.requireNonNull(defaults, "Defaults cannot be null");
        if (defaults.isEmpty()) {
            return this;
        }
        if (this.isEmpty()) {
            return defaults;
        }
        final Builder merged = new Builder();
        merged.component = this.component != null ? this.component : defaults.component;
        merged.parentComponent = this.parentComponent != null ? this.parentComponent : defaults.parentComponent;
        merged.feature = this.feature != null ? this.feature : defaults.feature;
        merged.principal = this.principal != null ? this.principal : defaults.principal;
        merged.traceId = this.traceId != null ? this.traceId : defaults.traceId;
        if (!defaults.tags.isEmpty() || !this.tags.isEmpty()) {
            merged.tags = new LinkedHashMap<>(defaults.tags);
            merged.tags.putAll(this.tags);
        }
        return new LlmCallMetadata(merged);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmCallMetadata that = (LlmCallMetadata) o;
        return Objects.equals(component, that.component) && Objects.equals(parentComponent, that.parentComponent)
                && Objects.equals(feature, that.feature) && Objects.equals(principal, that.principal)
                && Objects.equals(traceId, that.traceId) && tags.equals(that.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(component, parentComponent, feature, principal, traceId, tags);
    }

    @Override
    public String toString() {
        return "LlmCallMetadata{" + "component='" + component + '\'' + ", parentComponent='" + parentComponent + '\''
                + ", feature='" + feature + '\'' + ", principal=" + principal + ", traceId='" + traceId + '\''
                + ", tags=" + tags + '}';
    }

    /**
     * Builder for {@link LlmCallMetadata}.
     */
    public static final class Builder {
        private String component;
        private String parentComponent;
        private String feature;
        private Principal principal;
        private String traceId;
        private LinkedHashMap<String, String> tags;

        private Builder() {
        }

        /** Sets the component name (e.g. {@code "orca-agent"}, {@code "wiki-rerank"}). */
        public Builder component(String component) {
            this.component = component;
            return this;
        }

        /**
         * Sets the parent component that invoked this call (e.g. the orca agent that spawned a subagent). Used for
         * hierarchical usage attribution without inflating the {@link #component} cardinality.
         */
        public Builder parentComponent(String parentComponent) {
            this.parentComponent = parentComponent;
            return this;
        }

        /** Sets the feature label (e.g. {@code "react-loop"}, {@code "search"}). */
        public Builder feature(String feature) {
            this.feature = feature;
            return this;
        }

        /** Sets the invoking {@link Principal}. */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /** Sets the correlation/trace identifier shared with external logs. */
        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        /**
         * Adds a single free-form tag.
         *
         * @param key
         *            tag key (must not be null)
         * @param value
         *            tag value (must not be null)
         * @return this builder
         */
        public Builder tag(String key, String value) {
            Objects.requireNonNull(key, "Tag key cannot be null");
            Objects.requireNonNull(value, "Tag value cannot be null");
            if (tags == null) {
                tags = new LinkedHashMap<>();
            }
            tags.put(key, value);
            return this;
        }

        /**
         * Replaces all tags with the given map.
         *
         * @param tags
         *            tag map (must not be null)
         * @return this builder
         */
        public Builder tags(Map<String, String> tags) {
            Objects.requireNonNull(tags, "Tags map cannot be null");
            this.tags = new LinkedHashMap<>(tags);
            return this;
        }

        /** Builds the {@link LlmCallMetadata} instance. */
        public LlmCallMetadata build() {
            return new LlmCallMetadata(this);
        }
    }
}
