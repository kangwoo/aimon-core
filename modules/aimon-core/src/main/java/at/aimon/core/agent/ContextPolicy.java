package at.aimon.core.agent;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ModelContextWindowRegistry;

/**
 * An agent's say over when its context is compacted, from AGENT.md frontmatter {@code context}.
 *
 * <p>
 * The model's context window is a fact about the model and stays in the {@link ModelContextWindowRegistry}; this is
 * policy layered on top of it, applied to whichever model the agent's runtime resolves limits for:
 *
 * <ul>
 * <li>{@code maxWindowTokens} — caps the window: the agent uses {@code min(model window, maxWindowTokens)}. It can
 * lower the window, never raise it past what the model accepts.
 * <li>{@code autoCompactRatio} — auto compaction starts once the prompt passes this fraction of the effective window
 * (window minus reserved output). It replaces the model entry's {@link ModelContextLimits#getAutoCompactRatio() ratio}
 * (default {@value ModelContextLimits#DEFAULT_AUTO_COMPACT_RATIO}), for both context engines.
 * </ul>
 *
 * <p>
 * The blocking limit follows the (capped) window, never the ratio. The policy sizes the compaction gate of the agent's
 * own turns, per resolved model; subagent, skill and workflow forks run without a compaction gate.
 */
public final class ContextPolicy {

    /**
     * The smallest window cap the default reserved output, warning and blocking buffers fit in: the smallest window
     * {@link ModelContextLimits} accepts with its default values.
     */
    public static final int MIN_MAX_WINDOW_TOKENS = ModelContextLimits.DEFAULT_RESERVED_OUTPUT_TOKENS
            + Math.max(ModelContextLimits.DEFAULT_WARNING_BUFFER, ModelContextLimits.DEFAULT_BLOCKING_BUFFER) + 1;

    /**
     * The smallest ratio accepted. Below it the threshold sits under what the system prompt and tool schemas alone can
     * take, and every iteration would ask for a summary that cannot get the view under it.
     */
    public static final double MIN_AUTO_COMPACT_RATIO = 0.1;

    private static final Logger log = LoggerFactory.getLogger(ContextPolicy.class);
    private static final ContextPolicy NONE = builder().build();

    private final Integer maxWindowTokens;
    private final Double autoCompactRatio;

    private ContextPolicy(Builder builder) {
        if (builder.maxWindowTokens != null && builder.maxWindowTokens < MIN_MAX_WINDOW_TOKENS) {
            throw new IllegalArgumentException("maxWindowTokens must be at least " + MIN_MAX_WINDOW_TOKENS
                    + " (reserved output plus the compaction buffers), got: " + builder.maxWindowTokens);
        }
        if (builder.autoCompactRatio != null
                && !(builder.autoCompactRatio >= MIN_AUTO_COMPACT_RATIO && builder.autoCompactRatio <= 1.0)) {
            throw new IllegalArgumentException("autoCompactRatio must be in [" + MIN_AUTO_COMPACT_RATIO + ", 1], got: "
                    + builder.autoCompactRatio);
        }
        this.maxWindowTokens = builder.maxWindowTokens;
        this.autoCompactRatio = builder.autoCompactRatio;
    }

    /**
     * Returns the policy that changes nothing.
     *
     * @return the shared empty policy
     */
    public static ContextPolicy none() {
        return NONE;
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
     * Returns the cap on the window the agent uses.
     *
     * @return the cap, or empty for the model's own window
     */
    public Optional<Integer> getMaxWindowTokens() {
        return Optional.ofNullable(maxWindowTokens);
    }

    /**
     * Returns the fraction of the effective window past which auto compaction starts.
     *
     * @return the ratio, or empty for the model entry's ratio
     */
    public Optional<Double> getAutoCompactRatio() {
        return Optional.ofNullable(autoCompactRatio);
    }

    /**
     * Returns whether this policy changes nothing.
     *
     * @return {@code true} when neither value is set
     */
    public boolean isEmpty() {
        return maxWindowTokens == null && autoCompactRatio == null;
    }

    /**
     * Applies this policy to the limits {@code registry} resolves.
     *
     * @param registry
     *            the model registry (must not be null)
     * @return {@code registry} itself when the policy is empty, otherwise a view that applies it on every lookup
     */
    public ModelContextWindowRegistry applyTo(ModelContextWindowRegistry registry) {
        Objects.requireNonNull(registry, "registry cannot be null");
        if (isEmpty()) {
            return registry;
        }
        return new Applied(this, registry);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ContextPolicy that)) {
            return false;
        }
        return Objects.equals(maxWindowTokens, that.maxWindowTokens)
                && Objects.equals(autoCompactRatio, that.autoCompactRatio);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxWindowTokens, autoCompactRatio);
    }

    @Override
    public String toString() {
        return "ContextPolicy{maxWindowTokens=" + maxWindowTokens + ", autoCompactRatio=" + autoCompactRatio + '}';
    }

    /** The registry view {@link #applyTo} returns; one per agent runtime. */
    private static final class Applied implements ModelContextWindowRegistry {

        private final ContextPolicy policy;
        private final ModelContextWindowRegistry models;
        // Models whose buffers the cap did not fit, so the warning is logged once per model, not once per lookup.
        private final Set<String> capIgnored = ConcurrentHashMap.newKeySet();

        private Applied(ContextPolicy policy, ModelContextWindowRegistry models) {
            this.policy = policy;
            this.models = models;
        }

        @Override
        public ModelContextLimits resolve(String modelName) {
            final ModelContextLimits limits = models.resolve(modelName);
            final ModelContextLimits.Builder builder = limits.toBuilder();
            if (policy.autoCompactRatio != null) {
                builder.autoCompactRatio(policy.autoCompactRatio);
            }
            final ModelContextLimits withRatio = builder.build();
            if (policy.maxWindowTokens == null || policy.maxWindowTokens >= limits.getContextWindow()) {
                return withRatio;
            }
            try {
                return withRatio.toBuilder().contextWindow(policy.maxWindowTokens).build();
            } catch (IllegalArgumentException e) {
                // MIN_MAX_WINDOW_TOKENS is checked against the default buffers; this model's are wider.
                if (capIgnored.add(String.valueOf(modelName))) {
                    log.warn(
                            "context.max-window-tokens={} does not fit the compaction buffers of model '{}' ({});"
                                    + " using the model's window of {}",
                            policy.maxWindowTokens, modelName, e.getMessage(), limits.getContextWindow());
                }
                return withRatio;
            }
        }

        @Override
        public String toString() {
            return policy + " over " + models;
        }
    }

    /** Builder for {@link ContextPolicy}. */
    public static final class Builder {
        private Integer maxWindowTokens;
        private Double autoCompactRatio;

        private Builder() {
        }

        /**
         * Caps the context window the agent uses.
         *
         * @param maxWindowTokens
         *            the cap, at least {@link #MIN_MAX_WINDOW_TOKENS}, or {@code null} for the model's window
         * @return this builder
         */
        public Builder maxWindowTokens(Integer maxWindowTokens) {
            this.maxWindowTokens = maxWindowTokens;
            return this;
        }

        /**
         * Sets the fraction of the effective window past which auto compaction starts.
         *
         * @param autoCompactRatio
         *            the ratio in [{@link #MIN_AUTO_COMPACT_RATIO}, 1], or {@code null} for the model entry's ratio
         * @return this builder
         */
        public Builder autoCompactRatio(Double autoCompactRatio) {
            this.autoCompactRatio = autoCompactRatio;
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return the immutable policy
         * @throws IllegalArgumentException
         *             if the ratio is outside [{@link #MIN_AUTO_COMPACT_RATIO}, 1] or the cap is below
         *             {@link #MIN_MAX_WINDOW_TOKENS}
         */
        public ContextPolicy build() {
            return new ContextPolicy(this);
        }
    }
}
