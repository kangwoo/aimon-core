package at.aimon.core.llms.openai;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;

/**
 * Configuration for OpenAI API client.
 *
 * <p>
 * Contains API key, model selection, and client settings.
 *
 * <p>
 * The sampling parameters ({@code temperature}, {@code topP}, and the two penalties) and {@code reasoningEffort} are
 * all <em>unset</em> by default rather than defaulted to a value, and unset means the parameter is not sent at all.
 * That distinction is load-bearing twice over: several models reject a sampling parameter by its <em>presence</em>, so
 * the client has to be able to omit one; and it has to be able to tell "the operator asked for 0.0" from "nobody
 * asked" in order to decide whether omitting it is worth a warning. Nothing in this class or the client manufactures a
 * sampling value on the caller's behalf — a request carries one only when somebody put it there.
 *
 * <p>
 * {@code model} has no default and is required. Thread-safe and immutable.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OpenAIConfig config = OpenAIConfig.builder().apiKey(System.getenv("OPENAI_API_KEY")).model("gpt-4o")
 *             .temperature(0.7).timeout(Duration.ofSeconds(30)).build();
 * }
 * </pre>
 */
public final class OpenAIConfig {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Double temperature;
    private final Double topP;
    private final Double presencePenalty;
    private final Double frequencyPenalty;
    private final ReasoningEffort reasoningEffort;
    private final int maxTokens;
    private final Duration timeout;
    private final ModelCapabilityRegistry modelCapabilityRegistry;
    private final boolean responsesApiEnabled;

    private OpenAIConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = Objects.requireNonNull(builder.apiKey, "API key cannot be null");
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.presencePenalty = builder.presencePenalty;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.reasoningEffort = builder.reasoningEffort;
        this.maxTokens = builder.maxTokens;
        this.timeout = builder.timeout;
        this.modelCapabilityRegistry = builder.modelCapabilityRegistry;
        this.responsesApiEnabled = builder.responsesApiEnabled;

        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be blank");
        }
        // Checked here rather than at the field assignment above so that a config missing both fields still reports
        // the API key first -- the order callers already depend on.
        Objects.requireNonNull(model,
                "Model is required -- OpenAIConfig has no default; call OpenAIConfig.builder().model(\"gpt-4o\")");
        if (model.isBlank()) {
            throw new IllegalArgumentException(
                    "Model cannot be blank -- call OpenAIConfig.builder().model(\"gpt-4o\")");
        }
        // Range checks run only when a value is present -- "unset" is not out of range. Bounds match LlmModel's, so a
        // value legal on one is legal on the other.
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("Temperature must be between 0.0 and 2.0");
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
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }
    }

    /**
     * Creates a new builder for OpenAIConfig.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the base URL for OpenAI API.
     *
     * @return The base URL (may be null to use default)
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Gets the OpenAI API key.
     *
     * @return The API key (never null)
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Gets the model name.
     *
     * @return The model name (never null)
     */
    public String getModel() {
        return model;
    }

    /**
     * Gets the configured temperature.
     *
     * @return Optional containing the temperature, or empty when none was configured
     */
    public Optional<Double> getTemperature() {
        return Optional.ofNullable(temperature);
    }

    /**
     * Gets the configured top P (nucleus sampling) parameter.
     *
     * @return Optional containing the top P value, or empty when none was configured
     */
    public Optional<Double> getTopP() {
        return Optional.ofNullable(topP);
    }

    /**
     * Gets the configured presence penalty.
     *
     * @return Optional containing the presence penalty, or empty when none was configured
     */
    public Optional<Double> getPresencePenalty() {
        return Optional.ofNullable(presencePenalty);
    }

    /**
     * Gets the configured frequency penalty.
     *
     * @return Optional containing the frequency penalty, or empty when none was configured
     */
    public Optional<Double> getFrequencyPenalty() {
        return Optional.ofNullable(frequencyPenalty);
    }

    /**
     * Gets the configured reasoning effort.
     *
     * @return Optional containing the reasoning effort, or empty when none was configured
     */
    public Optional<ReasoningEffort> getReasoningEffort() {
        return Optional.ofNullable(reasoningEffort);
    }

    /**
     * Gets the maximum tokens.
     *
     * @return The maximum tokens
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * Gets the request timeout.
     *
     * @return The timeout duration (never null)
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Gets the registry the client consults to decide which parameters this deployment's model accepts.
     *
     * @return The capability registry (never null)
     */
    public ModelCapabilityRegistry getModelCapabilityRegistry() {
        return modelCapabilityRegistry;
    }

    /**
     * Whether this deployment's endpoint offers {@code /v1/responses}.
     *
     * <p>
     * A different question from anything in the capability registry, and the difference is load-bearing. The registry
     * answers <em>what does this model do</em> — a vendor fact. This answers <em>what does this deployment's endpoint
     * offer</em> — an operational one. Many OpenAI-compatible gateways implement {@code /v1/chat/completions} and
     * nothing else while passing the real model name straight through; such a deployment resolves {@code gpt-5.6-...}
     * to the built-in row, is sent to {@code /v1/responses}, and gets a 404 it did not get before. This switch turns
     * that off without lying about the model, so a corrected registry entry still reaches that operator.
     *
     * <p>
     * Note the asymmetry with a <em>renamed</em> gateway model: that resolves to unknown capabilities and is
     * unaffected. Only the pass-through gateway is exposed.
     *
     * @return {@code true} (the default) when a reasoning model may be routed to the Responses API
     */
    public boolean isResponsesApiEnabled() {
        return responsesApiEnabled;
    }

    /** Builder for OpenAIConfig. */
    public static final class Builder {
        private String apiKey;
        private String model;
        private Double temperature;
        private Double topP;
        private Double presencePenalty;
        private Double frequencyPenalty;
        private ReasoningEffort reasoningEffort;
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private Duration timeout = DEFAULT_TIMEOUT;
        private String baseUrl;
        private ModelCapabilityRegistry modelCapabilityRegistry = InMemoryModelCapabilityRegistry.withDefaults();
        private boolean responsesApiEnabled = true;

        private Builder() {
        }

        /**
         * Sets the OpenAI API key.
         *
         * @param apiKey
         *            The API key (must not be null)
         * @return This builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the model name. Required — this config has no default model.
         *
         * @param model
         *            The model name (e.g., "gpt-4o", "gpt-5.6-terra")
         * @return This builder
         * @throws NullPointerException
         *             if model is null
         */
        public Builder model(String model) {
            this.model = Objects.requireNonNull(model, "Model cannot be null");
            return this;
        }

        /**
         * Sets the temperature (sampling randomness).
         *
         * <p>
         * Leaving it unset means the parameter is not sent at all — this client does not substitute a value of its
         * own, so the server's default applies. Setting {@code 0.0} explicitly is therefore a different request from
         * setting nothing, and only a value set here is reported when the target model turns out not to accept it.
         *
         * @param temperature
         *            The temperature (0.0 to 2.0)
         * @return This builder
         */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Sets the top P (nucleus sampling) parameter.
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
         * @param presencePenalty
         *            The presence penalty (-2.0 to 2.0)
         * @return This builder
         */
        public Builder presencePenalty(double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * Sets the frequency penalty.
         *
         * @param frequencyPenalty
         *            The frequency penalty (-2.0 to 2.0)
         * @return This builder
         */
        public Builder frequencyPenalty(double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * Sets the reasoning effort for models that take one.
         *
         * <p>
         * A per-request {@code LlmModel} value wins over this one. Whether either reaches the model at all is decided
         * by the model's {@link ModelCapabilities}.
         *
         * @param reasoningEffort
         *            The reasoning effort; {@code null} leaves it unset
         * @return This builder
         */
        public Builder reasoningEffort(ReasoningEffort reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
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
         * Sets the request timeout.
         *
         * @param timeout
         *            The timeout duration
         * @return This builder
         * @throws NullPointerException
         *             if timeout is null
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "Timeout cannot be null");
            return this;
        }

        /**
         * Sets the base URL for OpenAI API.
         *
         * @param baseUrl
         *            The base URL (null to use default)
         * @return This builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the registry the client consults for per-model request-shape decisions.
         *
         * <p>
         * Defaults to {@link InMemoryModelCapabilityRegistry#withDefaults()}, which knows models by their real names.
         * A deployment behind {@link #baseUrl(String)} — an Azure deployment, an OpenAI-compatible gateway — is free
         * to rename models, and only its operator knows what a renamed model really is; that is what this setter is
         * for. Start from {@link InMemoryModelCapabilityRegistry#builderWithDefaults()} to add a name rather than
         * replace the table.
         *
         * @param modelCapabilityRegistry
         *            The registry (must not be null; use {@link ModelCapabilityRegistry#EMPTY} to know nothing)
         * @return This builder
         * @throws NullPointerException
         *             if modelCapabilityRegistry is null
         */
        public Builder modelCapabilityRegistry(ModelCapabilityRegistry modelCapabilityRegistry) {
            this.modelCapabilityRegistry = Objects.requireNonNull(modelCapabilityRegistry,
                    "Model capability registry cannot be null");
            return this;
        }

        /**
         * Turns the Responses API path on or off for this deployment.
         *
         * <p>
         * Defaults to {@code true}. Set it to {@code false} for an OpenAI-compatible gateway that implements only
         * {@code /v1/chat/completions} while passing real model names through — otherwise a reasoning model resolves
         * to its built-in capability row, is routed to {@code /v1/responses}, and gets a 404 on a deployment that
         * works today. Turning it off restores phase 1's behaviour for such a model: Chat Completions, with the
         * reasoning effort clamped to {@code NONE} when tools are present.
         *
         * @param responsesApiEnabled
         *            {@code false} when this endpoint has no {@code /v1/responses}
         * @return This builder
         */
        public Builder responsesApiEnabled(boolean responsesApiEnabled) {
            this.responsesApiEnabled = responsesApiEnabled;
            return this;
        }

        /**
         * Builds the OpenAIConfig.
         *
         * @return A new OpenAIConfig instance
         * @throws NullPointerException
         *             if apiKey or model is null
         * @throws IllegalArgumentException
         *             if any parameter is invalid
         */
        public OpenAIConfig build() {
            return new OpenAIConfig(this);
        }
    }
}
