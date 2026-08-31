package at.aimon.core.llms.anthropic;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for Anthropic API client.
 *
 * <p>
 * Contains API key, model selection, and client settings.
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
 *     AnthropicConfig config = AnthropicConfig.builder().apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *             .model("claude-sonnet-4-20250514").temperature(0.7).timeout(Duration.ofSeconds(30)).build();
 * }
 * </pre>
 */
public final class AnthropicConfig {

    private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";
    private static final double DEFAULT_TEMPERATURE = 0.0;
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;

    private AnthropicConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = Objects.requireNonNull(builder.apiKey, "API key cannot be null");
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.timeout = builder.timeout;

        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be blank");
        }
        if (temperature < 0.0 || temperature > 1.0) {
            throw new IllegalArgumentException("Temperature must be between 0.0 and 1.0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }
    }

    /**
     * Creates a new builder for AnthropicConfig.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the base URL for Anthropic API.
     *
     * @return The base URL (may be null to use default)
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Gets the Anthropic API key.
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
     * Gets the temperature setting.
     *
     * @return The temperature (0.0 to 1.0)
     */
    public double getTemperature() {
        return temperature;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AnthropicConfig that = (AnthropicConfig) o;
        return Double.compare(that.temperature, temperature) == 0 && maxTokens == that.maxTokens
                && Objects.equals(baseUrl, that.baseUrl) && apiKey.equals(that.apiKey) && model.equals(that.model)
                && timeout.equals(that.timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUrl, apiKey, model, temperature, maxTokens, timeout);
    }

    @Override
    public String toString() {
        return "AnthropicConfig{" + "baseUrl='" + baseUrl + '\'' + ", model='" + model + '\'' + ", temperature="
                + temperature + ", maxTokens=" + maxTokens + ", timeout=" + timeout + '}';
    }

    /** Builder for AnthropicConfig. */
    public static final class Builder {

        private String apiKey;
        private String model = DEFAULT_MODEL;
        private double temperature = DEFAULT_TEMPERATURE;
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private Duration timeout = DEFAULT_TIMEOUT;
        private String baseUrl;

        private Builder() {
        }

        /**
         * Sets the Anthropic API key.
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
         * Sets the model name.
         *
         * @param model
         *            The model name (e.g., "claude-sonnet-4-20250514", "claude-opus-4-20250514")
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
         * @param temperature
         *            The temperature (0.0 to 1.0)
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
         * Sets the base URL for Anthropic API.
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
         * Builds the AnthropicConfig.
         *
         * @return A new AnthropicConfig instance
         * @throws NullPointerException
         *             if apiKey is null
         * @throws IllegalArgumentException
         *             if any parameter is invalid
         */
        public AnthropicConfig build() {
            return new AnthropicConfig(this);
        }
    }
}
