package at.aimon.core.llms.anthropic;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;

/**
 * Configuration for Anthropic API client.
 *
 * <p>
 * Contains API key, model selection, and client settings.
 *
 * <p>
 * {@code temperature} is <em>unset</em> by default rather than defaulted to a value, and unset means the parameter is
 * not sent at all. Six of the current Claude models reject any non-default {@code temperature}, so the client has to be
 * able to omit one; and it has to be able to tell "the operator asked for 0.0" from "nobody asked" to decide whether
 * the omission is worth a warning. Nothing in this class or the client manufactures a sampling value on the caller's
 * behalf — a request carries one only when somebody put it there.
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
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final AnthropicThinkingMode DEFAULT_THINKING_MODE = AnthropicThinkingMode.OFF;
    private static final boolean DEFAULT_REPLAY_THINKING_BLOCKS = true;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final ReasoningEffort reasoningEffort;
    private final AnthropicThinkingMode thinkingMode;
    private final Integer thinkingBudgetTokens;
    private final boolean replayThinkingBlocks;
    private final ModelCapabilityRegistry modelCapabilityRegistry;

    private AnthropicConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = Objects.requireNonNull(builder.apiKey, "API key cannot be null");
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.timeout = builder.timeout;
        this.reasoningEffort = builder.reasoningEffort;
        this.thinkingMode = Objects.requireNonNull(builder.thinkingMode, "Thinking mode cannot be null");
        this.thinkingBudgetTokens = builder.thinkingBudgetTokens;
        this.replayThinkingBlocks = builder.replayThinkingBlocks;
        this.modelCapabilityRegistry = builder.modelCapabilityRegistry;

        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be blank");
        }
        // Range check only when a value is present -- "unset" is not out of range.
        if (temperature != null && (temperature < 0.0 || temperature > 1.0)) {
            throw new IllegalArgumentException("Temperature must be between 0.0 and 1.0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }
        if (thinkingBudgetTokens != null) {
            // The API rejects a budget below 1024 on every request, so failing here beats failing on all of them.
            if (thinkingBudgetTokens < AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS) {
                throw new IllegalArgumentException("Thinking budget must be at least "
                        + AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS + " tokens, got: " + thinkingBudgetTokens);
            }
            // Adaptive mode has no budget field and OFF sends no thinking parameter at all, so a budget set alongside
            // either would be silently ignored. Refusing at construction is strictly better than a warning per call.
            if (thinkingMode != AnthropicThinkingMode.EXTENDED) {
                throw new IllegalArgumentException("Thinking budget applies only to " + AnthropicThinkingMode.EXTENDED
                        + " thinking mode, but " + "the configured mode is " + thinkingMode);
            }
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
     * Gets the configured temperature.
     *
     * @return Optional containing the temperature (0.0 to 1.0), or empty when none was configured
     */
    public Optional<Double> getTemperature() {
        return Optional.ofNullable(temperature);
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
     * Gets the deployment-wide reasoning effort.
     *
     * <p>
     * The Anthropic half of the shared {@code reasoningEffort} configuration key. A per-request {@link
     * at.aimon.core.llm.LlmModel} value wins over this one, which is the precedence
     * {@code OpenAIConfig.getReasoningEffort()} has on the other provider — the key means the same thing on both, so
     * it resolves the same way on both.
     *
     * <p>
     * There is no validation, and none of the five values is refusable here: this provider expresses effort as a
     * token budget rather than as a rung, so what a rung is worth is
     * {@link AnthropicThinkingBudgets}' translation to make and the client's to report. It also does nothing at all
     * under the shipped default {@link AnthropicThinkingMode#OFF} — the client warns once about that rather than
     * refusing here, because the remedy is a second key.
     *
     * @return Optional containing the reasoning effort, or empty when none was configured
     */
    public Optional<ReasoningEffort> getReasoningEffort() {
        return Optional.ofNullable(reasoningEffort);
    }

    /**
     * Gets the thinking dialect this deployment's model speaks.
     *
     * @return The thinking mode (never null; {@link AnthropicThinkingMode#OFF} by default)
     */
    public AnthropicThinkingMode getThinkingMode() {
        return thinkingMode;
    }

    /**
     * Gets the explicit {@code budget_tokens} override for {@link AnthropicThinkingMode#EXTENDED}.
     *
     * <p>
     * When absent the budget comes from the call's {@link at.aimon.core.llm.ReasoningEffort} instead.
     *
     * @return The configured budget, or null to derive it from the call's reasoning effort
     */
    public Integer getThinkingBudgetTokens() {
        return thinkingBudgetTokens;
    }

    /**
     * Whether stored thinking blocks are replayed on the next request.
     *
     * @return {@code true} (the default) to replay them
     */
    public boolean isReplayThinkingBlocks() {
        return replayThinkingBlocks;
    }

    /**
     * Gets the registry the client consults to decide which parameters this deployment's model accepts.
     *
     * @return The capability registry (never null)
     */
    public ModelCapabilityRegistry getModelCapabilityRegistry() {
        return modelCapabilityRegistry;
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
        // modelCapabilityRegistry is deliberately absent from all three of equality, the hash and toString. It is a
        // caller-supplied collaborator with identity semantics -- no implementation defines equals, and the default
        // is a fresh InMemoryModelCapabilityRegistry per build() -- so including it here would make two configs that
        // agree on every value an operator can write unequal to each other.
        //
        // toString is left out for a weaker reason and it costs something: no registry defines toString either, so
        // the line would carry an identity hash and no information. The cost is that an operator asking "why is my
        // capability declaration not taking effect" gets no hint from a logged config -- the answer is in the WARN
        // that names the model whose value was dropped, or in its absence.
        return Objects.equals(temperature, that.temperature) && maxTokens == that.maxTokens
                && Objects.equals(baseUrl, that.baseUrl) && apiKey.equals(that.apiKey) && model.equals(that.model)
                && timeout.equals(that.timeout) && reasoningEffort == that.reasoningEffort
                && thinkingMode == that.thinkingMode && Objects.equals(thinkingBudgetTokens, that.thinkingBudgetTokens)
                && replayThinkingBlocks == that.replayThinkingBlocks;
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUrl, apiKey, model, temperature, maxTokens, timeout, reasoningEffort, thinkingMode,
                thinkingBudgetTokens, replayThinkingBlocks);
    }

    @Override
    public String toString() {
        return "AnthropicConfig{" + "baseUrl='" + baseUrl + '\'' + ", model='" + model + '\'' + ", temperature="
                + temperature + ", maxTokens=" + maxTokens + ", timeout=" + timeout + ", reasoningEffort="
                + reasoningEffort + ", thinkingMode=" + thinkingMode + ", thinkingBudgetTokens=" + thinkingBudgetTokens
                + ", replayThinkingBlocks=" + replayThinkingBlocks + '}';
    }

    /** Builder for AnthropicConfig. */
    public static final class Builder {

        private String apiKey;
        private String model = DEFAULT_MODEL;
        private Double temperature;
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private Duration timeout = DEFAULT_TIMEOUT;
        private String baseUrl;
        private ReasoningEffort reasoningEffort;
        private AnthropicThinkingMode thinkingMode = DEFAULT_THINKING_MODE;
        private Integer thinkingBudgetTokens;
        private boolean replayThinkingBlocks = DEFAULT_REPLAY_THINKING_BLOCKS;
        private ModelCapabilityRegistry modelCapabilityRegistry = InMemoryModelCapabilityRegistry.withDefaults();

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
         * Sets the deployment-wide reasoning effort.
         *
         * <p>
         * A per-request {@link at.aimon.core.llm.LlmModel} value wins over this one. Whether either reaches the model
         * at all depends on {@link #thinkingMode(AnthropicThinkingMode)}: under the default
         * {@link AnthropicThinkingMode#OFF} the request carries no thinking parameter, so the effort reaches nothing
         * and the client says so once.
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
         * Sets which thinking dialect this deployment's model speaks.
         *
         * <p>
         * The two dialects are mutually exclusive per model and picking the wrong one is an HTTP 400 — see
         * {@link AnthropicThinkingMode}, whose javadoc carries the per-model split and both server messages. The
         * default is {@link AnthropicThinkingMode#OFF}, which sends no {@code thinking} parameter and leaves the
         * request body exactly as it was before thinking support existed.
         *
         * <p>
         * This setting gates the <em>request</em> only. Thinking blocks that arrive anyway — and on the newest models
         * they do, because thinking is on there by default — are captured and replayed regardless.
         *
         * @param thinkingMode
         *            The thinking mode (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if thinkingMode is null
         */
        public Builder thinkingMode(AnthropicThinkingMode thinkingMode) {
            this.thinkingMode = Objects.requireNonNull(thinkingMode, "Thinking mode cannot be null");
            return this;
        }

        /**
         * Sets an explicit {@code budget_tokens} for {@link AnthropicThinkingMode#EXTENDED}, overriding the call's
         * {@link at.aimon.core.llm.ReasoningEffort}.
         *
         * <p>
         * Must be at least 1024 — the API rejects less — and applies only in {@code EXTENDED} mode; {@code build()}
         * refuses both mistakes rather than letting a silently ignored value reach the wire. The value is still
         * clamped below the request's {@code max_tokens}, since thinking tokens count against it.
         *
         * @param thinkingBudgetTokens
         *            The budget, or null to derive it from the call's reasoning effort
         * @return This builder
         */
        public Builder thinkingBudgetTokens(Integer thinkingBudgetTokens) {
            this.thinkingBudgetTokens = thinkingBudgetTokens;
            return this;
        }

        /**
         * Sets whether stored thinking blocks are replayed on the next request. Defaults to {@code true}.
         *
         * <p>
         * The escape hatch, and it exists for one named failure. From Claude Fable 5.1 a thinking block stays valid
         * only while the system prompt, the tools and the messages before it are unchanged; AIMON re-renders its
         * system prompt every iteration and compacts client-side, both of which are prefix edits. The resulting error
         * is <em>"Invalid {@code signature} in {@code thinking} block. The block is bound to a different
         * conversation."</em>, and the vendor's own remedy is to strip every thinking block from the history — which
         * is what {@code false} does. The cost is the feature: the model re-derives its reasoning each turn.
         *
         * @param replayThinkingBlocks
         *            {@code false} to strip stored thinking blocks instead of replaying them
         * @return This builder
         */
        public Builder replayThinkingBlocks(boolean replayThinkingBlocks) {
            this.replayThinkingBlocks = replayThinkingBlocks;
            return this;
        }

        /**
         * Sets the registry the client consults for per-model request-shape decisions.
         *
         * <p>
         * Defaults to {@link InMemoryModelCapabilityRegistry#withDefaults()}, which knows models by their real names.
         * A deployment behind {@link #baseUrl(String)} — a proxy, an Anthropic-compatible gateway — is free to rename
         * models, and only its operator knows what a renamed model really is; that is what this setter is for. Start
         * from {@link InMemoryModelCapabilityRegistry#builderWithDefaults()} to add a name rather than replace the
         * table.
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
