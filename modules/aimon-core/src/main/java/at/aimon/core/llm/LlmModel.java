package at.aimon.core.llm;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration for LLM model parameters.
 *
 * <p>
 * Provides dynamic control over model selection and generation parameters at request time, allowing fine-grained
 * control over LLM behavior.
 *
 * <p>
 * Thread-safe and immutable.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     LlmModel config = LlmModel.builder().name("gpt-4").temperature(0.7).maxTokens(2000).topP(0.9).build();
 *
 *     LlmResponse response = client.sendMessage(systemPrompt, messages, tools, config);
 * }
 * </pre>
 */
public final class LlmModel {
    private final String name;
    private final Double temperature;
    private final Integer maxTokens;
    private final Double topP;
    private final Double presencePenalty;
    private final Double frequencyPenalty;
    private final Duration requestTimeout;

    private LlmModel(Builder builder) {
        name = builder.name;
        temperature = builder.temperature;
        maxTokens = builder.maxTokens;
        topP = builder.topP;
        presencePenalty = builder.presencePenalty;
        frequencyPenalty = builder.frequencyPenalty;
        requestTimeout = builder.requestTimeout;

        // Validate ranges.
        //
        // These are sanity bounds, not any one provider's contract. They are the widest range in use across the
        // providers in the tree, so a value that passes here can still be narrower-than-legal for the provider it is
        // ultimately sent to: temperature is 0.0-2.0 for OpenAI but 0.0-1.0 for Anthropic, and the penalties have no
        // Anthropic counterpart at all. That is deliberate and it is not a hole to be closed by moving the check into
        // the clients -- this type is built at agent-definition load time, so rejecting nonsense here fails a
        // deployment at startup rather than on its first LLM call. What each provider then does with a legal-but-
        // divergent value is the provider's to report, and each one must say so at a level an operator sees (see
        // AnthropicLlmClient#reportDivergence).
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("Temperature must be between 0.0 and 2.0");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }
        if (topP != null && (topP < 0.0 || topP > 1.0)) {
            throw new IllegalArgumentException("Top P must be between 0.0 and 1.0");
        }
        if (presencePenalty != null && (presencePenalty < -2.0 || presencePenalty > 2.0)) {
            throw new IllegalArgumentException("Presence penalty must be between -2.0 and 2.0");
        }
        if (frequencyPenalty != null && (frequencyPenalty < -2.0 || frequencyPenalty > 2.0)) {
            throw new IllegalArgumentException("Frequency penalty must be between -2.0 and 2.0");
        }
        if (requestTimeout != null && (requestTimeout.isNegative() || requestTimeout.isZero())) {
            throw new IllegalArgumentException("Request timeout must be positive");
        }
    }

    /**
     * Creates a new builder for LlmModel.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the model name.
     *
     * @return Optional containing the model name, or empty if not set
     */
    public Optional<String> getName() {
        return Optional.ofNullable(name);
    }

    /**
     * Gets the temperature setting.
     *
     * @return Optional containing the temperature, or empty if not set
     */
    public Optional<Double> getTemperature() {
        return Optional.ofNullable(temperature);
    }

    /**
     * Gets the maximum tokens.
     *
     * @return Optional containing the max tokens, or empty if not set
     */
    public Optional<Integer> getMaxTokens() {
        return Optional.ofNullable(maxTokens);
    }

    /**
     * Gets the top P (nucleus sampling) parameter.
     *
     * @return Optional containing the top P value, or empty if not set
     */
    public Optional<Double> getTopP() {
        return Optional.ofNullable(topP);
    }

    /**
     * Gets the presence penalty.
     *
     * @return Optional containing the presence penalty, or empty if not set
     */
    public Optional<Double> getPresencePenalty() {
        return Optional.ofNullable(presencePenalty);
    }

    /**
     * Gets the frequency penalty.
     *
     * @return Optional containing the frequency penalty, or empty if not set
     */
    public Optional<Double> getFrequencyPenalty() {
        return Optional.ofNullable(frequencyPenalty);
    }

    /**
     * Gets the per-request worst-case timeout ceiling (safety net).
     *
     * <p>
     * When present, providers apply it to the underlying SDK call (both blocking and streaming paths) as a
     * per-request override of the client-wide default timeout. When empty, the client-wide default configured on the
     * provider applies unchanged.
     *
     * @return Optional containing the request timeout, or empty if not set
     */
    public Optional<Duration> getRequestTimeout() {
        return Optional.ofNullable(requestTimeout);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmModel that = (LlmModel) o;
        return Objects.equals(name, that.name) && Objects.equals(temperature, that.temperature)
                && Objects.equals(maxTokens, that.maxTokens) && Objects.equals(topP, that.topP)
                && Objects.equals(presencePenalty, that.presencePenalty)
                && Objects.equals(frequencyPenalty, that.frequencyPenalty)
                && Objects.equals(requestTimeout, that.requestTimeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, temperature, maxTokens, topP, presencePenalty, frequencyPenalty, requestTimeout);
    }

    @Override
    public String toString() {
        return "LlmModel{" + "model='" + name + '\'' + ", temperature=" + temperature + ", maxTokens=" + maxTokens
                + ", topP=" + topP + ", presencePenalty=" + presencePenalty + ", frequencyPenalty=" + frequencyPenalty
                + ", requestTimeout=" + requestTimeout + '}';
    }

    /**
     * Builder for creating {@link LlmModel} instances with optional parameters.
     *
     * <p>
     * All parameters are optional. Unset parameters will be represented as empty {@link Optional} values in the built
     * {@link LlmModel}.
     *
     * <p>
     * Example usage:
     *
     * <pre>
     * {
     *     &#64;code
     *     LlmModel model = LlmModel.builder().name("gpt-4").temperature(0.7).maxTokens(2000).build();
     * }
     * </pre>
     */
    public static final class Builder {
        private String name;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private Double presencePenalty;
        private Double frequencyPenalty;
        private Duration requestTimeout;

        private Builder() {
        }

        /**
         * Sets the model name.
         *
         * @param name
         *            The model name (e.g., "gpt-4", "claude-3-opus-20240229")
         * @return This builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the temperature (sampling randomness).
         *
         * <p>
         * Higher values (e.g., 0.8) make output more random, lower values (e.g., 0.2) make it more focused and
         * deterministic.
         *
         * <p>
         * The accepted range is the widest any supported provider takes, not the range every provider takes. Anthropic
         * accepts only {@code 0.0}-{@code 1.0} and clamps anything above it, so a value legal here can still be
         * adjusted before it reaches the model. The provider says so when it happens; it does not fail the call.
         *
         * @param temperature
         *            The temperature (0.0 to 2.0; a provider may accept a narrower range)
         * @return This builder
         */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Sets the maximum tokens for completion.
         *
         * @param maxTokens
         *            The maximum tokens
         * @return This builder
         */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Sets the top P (nucleus sampling) parameter.
         *
         * <p>
         * An alternative to temperature sampling. The model considers the results of the tokens with top_p probability
         * mass.
         *
         * @param topP
         *            The top P value (0.0 to 1.0)
         * @return This builder
         */
        public Builder topP(double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * Sets the presence penalty.
         *
         * <p>
         * Positive values penalize new tokens based on whether they appear in the text so far, increasing the model's
         * likelihood to talk about new topics.
         *
         * <p>
         * Not every provider has this parameter. The Anthropic Messages API has no counterpart, so a value set here is
         * dropped before the call and the call succeeds without it; the provider reports the drop.
         *
         * @param presencePenalty
         *            The presence penalty (-2.0 to 2.0; not supported by every provider)
         * @return This builder
         */
        public Builder presencePenalty(double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * Sets the frequency penalty.
         *
         * <p>
         * Positive values penalize new tokens based on their existing frequency in the text so far, decreasing the
         * model's likelihood to repeat the same line verbatim.
         *
         * <p>
         * Not every provider has this parameter. The Anthropic Messages API has no counterpart, so a value set here is
         * dropped before the call and the call succeeds without it; the provider reports the drop.
         *
         * @param frequencyPenalty
         *            The frequency penalty (-2.0 to 2.0; not supported by every provider)
         * @return This builder
         */
        public Builder frequencyPenalty(double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * Sets the per-request worst-case timeout ceiling (safety net).
         *
         * <p>
         * When set, providers apply it to the underlying SDK call as a per-request override of the client-wide
         * default timeout, bounding the worst-case duration of a single LLM HTTP call (both blocking and streaming
         * paths). Leaving it unset keeps the provider's client-wide default timeout unchanged.
         *
         * @param requestTimeout
         *            The per-request timeout (must be positive when built); {@code null} leaves it unset
         * @return This builder
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * Builds the LlmModel.
         *
         * @return A new LlmModel instance
         * @throws IllegalArgumentException
         *             if any parameter is out of valid range
         */
        public LlmModel build() {
            return new LlmModel(this);
        }
    }
}
