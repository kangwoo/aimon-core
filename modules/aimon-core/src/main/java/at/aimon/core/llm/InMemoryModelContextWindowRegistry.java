package at.aimon.core.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link ModelContextWindowRegistry} backed by an in-memory map of explicit entries plus a
 * fallback default.
 *
 * <p>
 * Built via {@link Builder}. Lookups first consult the explicit entries, then a list of registered
 * <em>prefix patterns</em> in registration order, then fall back to the default. Pattern matching is
 * case-insensitive on the model name.
 */
public final class InMemoryModelContextWindowRegistry implements ModelContextWindowRegistry {

    private final Map<String, ModelContextLimits> exactEntries;
    private final Map<String, ModelContextLimits> prefixEntries;
    private final ModelContextLimits defaultLimits;

    private InMemoryModelContextWindowRegistry(Builder builder) {
        this.exactEntries = Map.copyOf(builder.exactEntries);
        this.prefixEntries = new LinkedHashMap<>(builder.prefixEntries);
        this.defaultLimits = Objects.requireNonNull(builder.defaultLimits, "defaultLimits cannot be null");
    }

    /** Returns a registry with framework-default mappings (gpt-4o, o1, claude-3.x, claude-4.x, ...). */
    public static InMemoryModelContextWindowRegistry withDefaults() {
        return builder().register("gpt-4o", ModelContextLimits.ofContextWindow(128_000))
                .register("gpt-4-turbo", ModelContextLimits.ofContextWindow(128_000))
                .register("o1", ModelContextLimits.ofContextWindow(200_000))
                .registerPrefix("gpt-4o", ModelContextLimits.ofContextWindow(128_000))
                .registerPrefix("gpt-4-turbo", ModelContextLimits.ofContextWindow(128_000))
                .registerPrefix("o1", ModelContextLimits.ofContextWindow(200_000))
                .registerPrefix("claude-3-5-sonnet", ModelContextLimits.ofContextWindow(200_000))
                .registerPrefix("claude-3-5-haiku", ModelContextLimits.ofContextWindow(200_000))
                .registerPrefix("claude-3-7", ModelContextLimits.ofContextWindow(200_000))
                .registerPrefix("claude-4", ModelContextLimits.ofContextWindow(200_000))
                .registerPrefix("claude-opus-4", ModelContextLimits.ofContextWindow(200_000))
                .registerPrefix("claude-sonnet-4", ModelContextLimits.ofContextWindow(200_000))
                .registerPrefix("claude-haiku-4", ModelContextLimits.ofContextWindow(200_000))
                .defaultLimits(ModelContextLimits.ofContextWindow(ModelContextLimits.DEFAULT_CONTEXT_WINDOW)).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ModelContextLimits resolve(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return defaultLimits;
        }
        final ModelContextLimits exact = exactEntries.get(modelName);
        if (exact != null) {
            return exact;
        }
        final String lower = modelName.toLowerCase();
        for (Map.Entry<String, ModelContextLimits> entry : prefixEntries.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return defaultLimits;
    }

    /** Builder for {@link InMemoryModelContextWindowRegistry}. */
    public static final class Builder {
        private final ConcurrentHashMap<String, ModelContextLimits> exactEntries = new ConcurrentHashMap<>();
        private final LinkedHashMap<String, ModelContextLimits> prefixEntries = new LinkedHashMap<>();
        private ModelContextLimits defaultLimits = ModelContextLimits
                .ofContextWindow(ModelContextLimits.DEFAULT_CONTEXT_WINDOW);

        private Builder() {
        }

        /** Registers an exact model-name → limits mapping. */
        public Builder register(String modelName, ModelContextLimits limits) {
            Objects.requireNonNull(modelName, "modelName cannot be null");
            Objects.requireNonNull(limits, "limits cannot be null");
            exactEntries.put(modelName, limits);
            return this;
        }

        /**
         * Registers a case-insensitive prefix match.
         *
         * <p>
         * Patterns are evaluated in registration order; the first match wins.
         */
        public Builder registerPrefix(String modelNamePrefix, ModelContextLimits limits) {
            Objects.requireNonNull(modelNamePrefix, "modelNamePrefix cannot be null");
            Objects.requireNonNull(limits, "limits cannot be null");
            prefixEntries.put(modelNamePrefix.toLowerCase(), limits);
            return this;
        }

        public Builder defaultLimits(ModelContextLimits defaultLimits) {
            this.defaultLimits = Objects.requireNonNull(defaultLimits, "defaultLimits cannot be null");
            return this;
        }

        public InMemoryModelContextWindowRegistry build() {
            return new InMemoryModelContextWindowRegistry(this);
        }
    }
}
