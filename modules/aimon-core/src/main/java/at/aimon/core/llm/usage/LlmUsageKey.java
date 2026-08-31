package at.aimon.core.llm.usage;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Aggregation key for {@link InMemoryLlmUsageRecorder}.
 *
 * <p>
 * Combines the provider/model with the attribution fields from {@link LlmCallMetadata} (component, feature,
 * principal) so that usage can be rolled up along any of those axes. Free-form tags are intentionally excluded from
 * the key to keep cardinality bounded.
 *
 * <p>
 * Immutable value object.
 */
public final class LlmUsageKey {
    private final String provider;
    private final String model;
    private final String component;
    private final String feature;
    private final Principal principal;

    private LlmUsageKey(String provider, String model, String component, String feature, Principal principal) {
        this.provider = Objects.requireNonNull(provider, "Provider cannot be null");
        this.model = model;
        this.component = component;
        this.feature = feature;
        this.principal = principal;
    }

    /**
     * Builds a key from the provider/model and a {@link LlmCallMetadata}.
     *
     * @param provider
     *            provider name (must not be null)
     * @param model
     *            model identifier (may be null)
     * @param metadata
     *            attribution metadata (must not be null)
     * @return a new aggregation key
     */
    public static LlmUsageKey of(String provider, String model, LlmCallMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return new LlmUsageKey(provider, model, metadata.getComponent().orElse(null),
                metadata.getFeature().orElse(null), metadata.getPrincipal().orElse(null));
    }

    public String getProvider() {
        return provider;
    }

    public Optional<String> getModel() {
        return Optional.ofNullable(model);
    }

    public Optional<String> getComponent() {
        return Optional.ofNullable(component);
    }

    public Optional<String> getFeature() {
        return Optional.ofNullable(feature);
    }

    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmUsageKey that = (LlmUsageKey) o;
        return provider.equals(that.provider) && Objects.equals(model, that.model)
                && Objects.equals(component, that.component) && Objects.equals(feature, that.feature)
                && Objects.equals(principal, that.principal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, model, component, feature, principal);
    }

    @Override
    public String toString() {
        return "LlmUsageKey{" + "provider='" + provider + '\'' + ", model='" + model + '\'' + ", component='"
                + component + '\'' + ", feature='" + feature + '\'' + ", principal=" + principal + '}';
    }
}
