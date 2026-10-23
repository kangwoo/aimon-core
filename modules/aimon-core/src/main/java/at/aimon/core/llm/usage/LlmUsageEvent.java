package at.aimon.core.llm.usage;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.ServiceTier;
import at.aimon.core.llm.TokenUsage;

/**
 * One metered LLM call: what the provider reported about it, when it ran, and who it is attributed to.
 *
 * <p>
 * This is the typed form of a usage record, delivered to {@link LlmUsageRecorder#record(LlmUsageEvent)}. Beyond the
 * provider/model/usage/metadata quadruple of the original {@code record(...)} method it carries what a per-call price
 * needs: the {@link #getServiceTier() service tier the provider applied}, the prompt-cache counters inside
 * {@link #getUsage() usage}, and the {@link #getCompletedAt() completion time} at which the price applies.
 *
 * <p>
 * <strong>Every measured value comes from the response, never from the request.</strong> A field the provider did not
 * report is empty — it is not defaulted to {@code standard} or to {@code 0}, so a consumer can tell "not measured"
 * from "measured nothing" and decline to price a call it cannot price.
 *
 * <h2>Outcome</h2>
 *
 * <p>
 * {@link Outcome#COMPLETED} carries the provider's final usage. {@link Outcome#ABORTED} marks a call that may have been
 * billed but produced no final usage: a stream that failed or was cancelled after the provider had started answering,
 * or a call cut by its cancellation token. Its {@link #getUsage() usage} is empty — a partial count would be priced as
 * if it were the whole bill.
 *
 * <h2>Reserved tags</h2>
 *
 * <p>
 * The {@code TAG_*} keys below are reserved for measured values. They are removed from the {@link #getMetadata()
 * metadata} this event carries, so a tag a caller attached to the request can never pass for a measurement, and
 * {@link #toRecordingMetadata()} writes the measured values back under the same keys for recorders that only read
 * tags.
 *
 * <p>
 * Immutable value object.
 */
public final class LlmUsageEvent {

    /** Tag carrying the normalized {@link ServiceTier} the provider applied. */
    public static final String TAG_SERVICE_TIER = "service_tier";
    /** Tag carrying the prompt tokens served from the cache, as a non-negative decimal integer. */
    public static final String TAG_CACHED_INPUT_TOKENS = "cached_input_tokens";
    /** Tag carrying the prompt tokens written to the cache, as a non-negative decimal integer. */
    public static final String TAG_CACHE_WRITE_TOKENS = "cache_write_tokens";
    /**
     * Tag a consumer may derive from the two cache counters. Reserved so that a caller cannot supply it pre-derived;
     * this event never writes it.
     */
    public static final String TAG_CACHE_USAGE_KNOWN = "cache_usage_known";
    /**
     * Tag a consumer may derive from the prompt and cache counters. Reserved for the same reason as
     * {@link #TAG_CACHE_USAGE_KNOWN}; this event never writes it.
     */
    public static final String TAG_CONTEXT_INPUT_TOKENS = "context_input_tokens";

    /** Every tag key reserved for measured or derived usage values. */
    public static final Set<String> RESERVED_TAGS = Set.of(TAG_SERVICE_TIER, TAG_CACHED_INPUT_TOKENS,
            TAG_CACHE_WRITE_TOKENS, TAG_CACHE_USAGE_KNOWN, TAG_CONTEXT_INPUT_TOKENS);

    /** How the metered call ended. */
    public enum Outcome {
        /** The provider returned a response; {@link #getUsage()} holds its final usage. */
        COMPLETED,
        /** The call ended without a final usage but may have been billed; {@link #getUsage()} is empty. */
        ABORTED
    }

    private final String provider;
    private final String requestedModel;
    private final String servedModel;
    private final TokenUsage usage;
    private final ServiceTier serviceTier;
    private final Instant startedAt;
    private final Instant completedAt;
    private final LlmCallMetadata metadata;
    private final Outcome outcome;

    private LlmUsageEvent(Builder builder) {
        this.provider = Objects.requireNonNull(builder.provider, "Provider cannot be null");
        this.requestedModel = blankToNull(builder.requestedModel);
        this.servedModel = blankToNull(builder.servedModel);
        this.serviceTier = builder.serviceTier;
        this.startedAt = Objects.requireNonNull(builder.startedAt, "startedAt cannot be null");
        this.completedAt = Objects.requireNonNull(builder.completedAt, "completedAt cannot be null");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "completedAt (" + completedAt + ") is before startedAt (" + startedAt + ")");
        }
        this.metadata = Objects.requireNonNull(builder.metadata, "Metadata cannot be null").withoutTags(RESERVED_TAGS);
        this.outcome = Objects.requireNonNull(builder.outcome, "Outcome cannot be null");
        if (outcome == Outcome.COMPLETED) {
            this.usage = Objects.requireNonNull(builder.usage, "Usage cannot be null for a completed call");
        } else {
            this.usage = builder.usage;
        }
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the provider name, e.g. {@code "OpenAI"} (never null)
     */
    public String getProvider() {
        return provider;
    }

    /**
     * The model the call asked for: the request's override, else the client's default.
     *
     * <p>
     * This is the name the legacy {@link LlmUsageRecorder#record(String, String, TokenUsage, LlmCallMetadata)} method
     * receives, kept there so existing aggregation keys do not change.
     *
     * @return the requested model, or empty when neither the request nor the client named one
     */
    public Optional<String> getRequestedModel() {
        return Optional.ofNullable(requestedModel);
    }

    /**
     * The model the provider reports having actually served the call with — for example the dated snapshot an alias
     * resolved to.
     *
     * @return the served model, or empty when the provider did not report one
     */
    public Optional<String> getServedModel() {
        return Optional.ofNullable(servedModel);
    }

    /**
     * @return the served model when reported, else the requested model, else empty
     */
    public Optional<String> getModel() {
        return getServedModel().or(this::getRequestedModel);
    }

    /**
     * The provider's usage for the call. The prompt count is the whole input context, cache included; the cache
     * counters inside it are empty when not measured — see {@link TokenUsage}.
     *
     * @return the usage; always present for {@link Outcome#COMPLETED}, empty for {@link Outcome#ABORTED}
     */
    public Optional<TokenUsage> getUsage() {
        return Optional.ofNullable(usage);
    }

    /**
     * @return the service tier the provider reports having applied, or empty when it reported none
     */
    public Optional<ServiceTier> getServiceTier() {
        return Optional.ofNullable(serviceTier);
    }

    /**
     * @return when the call was sent (never null)
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * When the call finished — the response was returned, or the call aborted. This is the moment a time-dependent
     * price applies at, independent of any queueing a recorder does afterwards.
     *
     * @return the completion time (never null)
     */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * @return {@code completedAt - startedAt} (never null, never negative)
     */
    public Duration getLatency() {
        return Duration.between(startedAt, completedAt);
    }

    /**
     * The attribution metadata attached at the call site, with every {@link #RESERVED_TAGS reserved tag} removed.
     *
     * @return the metadata (never null)
     */
    public LlmCallMetadata getMetadata() {
        return metadata;
    }

    /**
     * @return how the call ended (never null)
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * Returns {@link #getMetadata()} with the measured values written under the reserved tag keys, for a recorder that
     * reads usage from tags.
     *
     * <p>
     * {@link #TAG_SERVICE_TIER} is written when the tier is known, {@link #TAG_CACHED_INPUT_TOKENS} and
     * {@link #TAG_CACHE_WRITE_TOKENS} each when that counter is measured. A value that was not measured is left out,
     * never written as a default. The derived tags ({@link #TAG_CACHE_USAGE_KNOWN}, {@link #TAG_CONTEXT_INPUT_TOKENS})
     * are never written. The instance the caller passed to the client is not modified.
     *
     * @return the recording metadata (never null)
     */
    public LlmCallMetadata toRecordingMetadata() {
        final Map<String, String> measured = new LinkedHashMap<>();
        if (serviceTier != null) {
            measured.put(TAG_SERVICE_TIER, serviceTier.getValue());
        }
        if (usage != null) {
            usage.getCacheReadTokens().ifPresent(v -> measured.put(TAG_CACHED_INPUT_TOKENS, Integer.toString(v)));
            usage.getCacheWriteTokens().ifPresent(v -> measured.put(TAG_CACHE_WRITE_TOKENS, Integer.toString(v)));
        }
        return metadata.withTags(measured);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmUsageEvent that = (LlmUsageEvent) o;
        return provider.equals(that.provider) && Objects.equals(requestedModel, that.requestedModel)
                && Objects.equals(servedModel, that.servedModel) && Objects.equals(usage, that.usage)
                && Objects.equals(serviceTier, that.serviceTier) && startedAt.equals(that.startedAt)
                && completedAt.equals(that.completedAt) && metadata.equals(that.metadata) && outcome == that.outcome;
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, requestedModel, servedModel, usage, serviceTier, startedAt, completedAt, metadata,
                outcome);
    }

    @Override
    public String toString() {
        return "LlmUsageEvent{provider='" + provider + "', requestedModel='" + requestedModel + "', servedModel='"
                + servedModel + "', outcome=" + outcome + ", usage=" + usage + ", serviceTier=" + serviceTier
                + ", completedAt=" + completedAt + ", latency=" + getLatency() + '}';
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Builder for {@link LlmUsageEvent}. */
    public static final class Builder {
        private String provider;
        private String requestedModel;
        private String servedModel;
        private TokenUsage usage;
        private ServiceTier serviceTier;
        private Instant startedAt;
        private Instant completedAt;
        private LlmCallMetadata metadata = LlmCallMetadata.empty();
        private Outcome outcome = Outcome.COMPLETED;

        private Builder() {
        }

        /**
         * Sets the provider name (required).
         *
         * @param provider
         *            the value
         * @return this builder
         */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /**
         * Sets the model the call asked for (optional).
         *
         * @param requestedModel
         *            the value
         * @return this builder
         */
        public Builder requestedModel(String requestedModel) {
            this.requestedModel = requestedModel;
            return this;
        }

        /**
         * Sets the model the provider reports having served the call with (optional).
         *
         * @param servedModel
         *            the value
         * @return this builder
         */
        public Builder servedModel(String servedModel) {
            this.servedModel = servedModel;
            return this;
        }

        /**
         * Sets the provider's usage (required for {@link Outcome#COMPLETED}).
         *
         * @param usage
         *            the value
         * @return this builder
         */
        public Builder usage(TokenUsage usage) {
            this.usage = usage;
            return this;
        }

        /**
         * Sets the service tier the provider reports having applied (optional).
         *
         * @param serviceTier
         *            the value
         * @return this builder
         */
        public Builder serviceTier(ServiceTier serviceTier) {
            this.serviceTier = serviceTier;
            return this;
        }

        /**
         * Sets when the call was sent (required).
         *
         * @param startedAt
         *            the value
         * @return this builder
         */
        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        /**
         * Sets when the call finished (required, not before {@code startedAt}).
         *
         * @param completedAt
         *            the value
         * @return this builder
         */
        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        /**
         * Sets the call-site attribution (defaults to {@link LlmCallMetadata#empty()}).
         *
         * @param metadata
         *            the value
         * @return this builder
         */
        public Builder metadata(LlmCallMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Sets how the call ended (defaults to {@link Outcome#COMPLETED}).
         *
         * @param outcome
         *            the value
         * @return this builder
         */
        public Builder outcome(Outcome outcome) {
            this.outcome = outcome;
            return this;
        }

        /**
         * @return a new {@link LlmUsageEvent}
         * @throws NullPointerException
         *             if a required field is missing
         * @throws IllegalArgumentException
         *             if {@code completedAt} is before {@code startedAt}
         */
        public LlmUsageEvent build() {
            return new LlmUsageEvent(this);
        }
    }
}
