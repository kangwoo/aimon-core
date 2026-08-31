package at.aimon.core.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;

/**
 * Immutable per-turn options carried alongside the raw user input on each
 * {@code LiveSession.submit(String, SubmitOptions) submit},
 * {@code LiveSession.submitAsync(String, SubmitOptions, Consumer) submitAsync}, and
 * {@code LiveSession.offerAsync(String, SubmitOptions, Consumer) offerAsync} call.
 *
 * <p>
 * Where {@code LiveSessionOptions} carries session-scoped values that are fixed for the lifetime of the session
 * (default budget, locale, source agent id), {@code SubmitOptions} carries values that legitimately differ from one
 * turn to the next inside the same session:
 *
 * <ul>
 * <li>{@link #getPrincipal() principal} — caller identity for this specific turn (e.g., on-behalf-of dispatch by a
 * service).
 * <li>{@link #getSystemPromptVariables() systemPromptVariables} — turn-local variables merged into the system prompt
 * (e.g., current request context).
 * <li>{@link #getExecutionAttributes() executionAttributes} — turn-local key/value attributes propagated to
 * {@code ToolContext} and {@code HookContext} (e.g., feature flags, A/B assignments).
 * <li>{@link #getLlmCallMetadata() llmCallMetadata} — observability metadata (trace id, request id, tags) that the
 * Orca executor merges with framework-derived defaults before each LLM call.
 * <li>{@link #getUserContextInjection() userContextInjection} — tri-state override for the synthetic
 * {@code messages[0]} user-context injection. {@link Optional#empty()} means "use the executor default" (currently
 * {@code true}).
 * </ul>
 *
 * <p>
 * All fields are optional; {@link #empty()} returns a shared instance that triggers the executor's defaults for every
 * field — the legacy session behavior pre-SubmitOptions.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     SubmitOptions options = SubmitOptions.builder().principal(Principal.user("u-123", "alice"))
 *             .llmCallMetadata(LlmCallMetadata.builder().traceId(currentTraceId).tag("tenant", "acme").build())
 *             .executionAttribute("feature.flag.x", true).build();
 *
 *     AgentExecutionResult result = session.submit("Hello", options);
 * }
 * </pre>
 *
 * @see at.aimon.core.agent.session.LiveSession
 * @see at.aimon.core.agent.session.LiveSessionOptions
 */
public final class SubmitOptions {

    private static final SubmitOptions EMPTY = new SubmitOptions(new Builder());

    private final Principal principal;
    private final Map<String, Object> systemPromptVariables;
    private final Map<String, Object> executionAttributes;
    private final LlmCallMetadata llmCallMetadata;
    private final Boolean userContextInjection;

    private SubmitOptions(Builder builder) {
        this.principal = builder.principal;
        this.systemPromptVariables = builder.systemPromptVariables != null
                ? Map.copyOf(builder.systemPromptVariables)
                : Map.of();
        this.executionAttributes = builder.executionAttributes != null
                ? Map.copyOf(builder.executionAttributes)
                : Map.of();
        this.llmCallMetadata = builder.llmCallMetadata;
        this.userContextInjection = builder.userContextInjection;
    }

    /**
     * Returns the shared empty instance — every field unset, executor falls back to its defaults.
     *
     * @return the empty options (never null)
     */
    public static SubmitOptions empty() {
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
     * Returns the per-turn principal (caller identity).
     *
     * @return the principal, or empty when unset (executor receives {@code null})
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Returns the per-turn system prompt variables.
     *
     * @return an unmodifiable map (never null, may be empty)
     */
    public Map<String, Object> getSystemPromptVariables() {
        return systemPromptVariables;
    }

    /**
     * Returns the per-turn execution attributes propagated to {@code ToolContext} / {@code HookContext}.
     *
     * @return an unmodifiable map (never null, may be empty)
     */
    public Map<String, Object> getExecutionAttributes() {
        return executionAttributes;
    }

    /**
     * Returns the per-turn LLM call metadata.
     *
     * @return the metadata, or empty when unset (executor receives {@link LlmCallMetadata#empty()})
     */
    public Optional<LlmCallMetadata> getLlmCallMetadata() {
        return Optional.ofNullable(llmCallMetadata);
    }

    /**
     * Returns the per-turn user-context-injection override.
     *
     * <p>
     * Tri-state: {@code Optional.empty()} means "no override — executor uses its default"; otherwise the boxed value
     * is forwarded as-is to {@code OrcaAgentExecutionRequest.Builder#userContextInjection(boolean)}.
     *
     * @return the override, or empty when unset
     */
    public Optional<Boolean> getUserContextInjection() {
        return Optional.ofNullable(userContextInjection);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SubmitOptions that = (SubmitOptions) o;
        return Objects.equals(principal, that.principal)
                && Objects.equals(systemPromptVariables, that.systemPromptVariables)
                && Objects.equals(executionAttributes, that.executionAttributes)
                && Objects.equals(llmCallMetadata, that.llmCallMetadata)
                && Objects.equals(userContextInjection, that.userContextInjection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(principal, systemPromptVariables, executionAttributes, llmCallMetadata,
                userContextInjection);
    }

    @Override
    public String toString() {
        return "SubmitOptions{principal=" + principal + ", systemPromptVariables=" + systemPromptVariables
                + ", executionAttributes=" + executionAttributes + ", llmCallMetadata=" + llmCallMetadata
                + ", userContextInjection=" + userContextInjection + '}';
    }

    /** Builder for {@link SubmitOptions}. */
    public static final class Builder {

        private Principal principal;
        private Map<String, Object> systemPromptVariables;
        private Map<String, Object> executionAttributes;
        private LlmCallMetadata llmCallMetadata;
        private Boolean userContextInjection;

        private Builder() {
        }

        /**
         * Sets the per-turn principal (caller identity).
         *
         * @param principal
         *            the principal (may be null)
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Sets the per-turn system prompt variables. The map is defensively copied at {@link #build()}.
         *
         * @param systemPromptVariables
         *            the variables (may be null; treated as empty)
         * @return this builder
         */
        public Builder systemPromptVariables(Map<String, Object> systemPromptVariables) {
            this.systemPromptVariables = systemPromptVariables;
            return this;
        }

        /**
         * Adds a single system prompt variable. Values previously set via
         * {@link #systemPromptVariables(java.util.Map)} are preserved; subsequent calls overwrite the same key.
         *
         * @param key
         *            the variable name (must not be null)
         * @param value
         *            the variable value (must not be null)
         * @return this builder
         */
        public Builder systemPromptVariable(String key, Object value) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            if (this.systemPromptVariables == null) {
                this.systemPromptVariables = new HashMap<>();
            } else if (!(this.systemPromptVariables instanceof HashMap)) {
                this.systemPromptVariables = new HashMap<>(this.systemPromptVariables);
            }
            this.systemPromptVariables.put(key, value);
            return this;
        }

        /**
         * Sets the per-turn execution attributes. The map is defensively copied at {@link #build()}.
         *
         * @param executionAttributes
         *            the attributes (may be null; treated as empty)
         * @return this builder
         */
        public Builder executionAttributes(Map<String, Object> executionAttributes) {
            this.executionAttributes = executionAttributes;
            return this;
        }

        /**
         * Adds a single execution attribute. Values previously set via {@link #executionAttributes(java.util.Map)} are
         * preserved; subsequent calls overwrite the same key.
         *
         * @param key
         *            the attribute name (must not be null)
         * @param value
         *            the attribute value (must not be null)
         * @return this builder
         */
        public Builder executionAttribute(String key, Object value) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            if (this.executionAttributes == null) {
                this.executionAttributes = new HashMap<>();
            } else if (!(this.executionAttributes instanceof HashMap)) {
                this.executionAttributes = new HashMap<>(this.executionAttributes);
            }
            this.executionAttributes.put(key, value);
            return this;
        }

        /**
         * Sets the per-turn LLM call metadata.
         *
         * @param llmCallMetadata
         *            the metadata (may be null)
         * @return this builder
         */
        public Builder llmCallMetadata(LlmCallMetadata llmCallMetadata) {
            this.llmCallMetadata = llmCallMetadata;
            return this;
        }

        /**
         * Overrides the executor's user-context-injection default for this turn only.
         *
         * <p>
         * When this method is not called, {@link SubmitOptions#getUserContextInjection()} returns
         * {@link Optional#empty()}
         * and the session preserves the executor's default (currently {@code true}).
         *
         * @param userContextInjection
         *            {@code true} to enable injection for this turn, {@code false} to opt out
         * @return this builder
         */
        public Builder userContextInjection(boolean userContextInjection) {
            this.userContextInjection = userContextInjection;
            return this;
        }

        /**
         * Builds the {@link SubmitOptions}.
         *
         * @return a new {@code SubmitOptions} (never null)
         */
        public SubmitOptions build() {
            return new SubmitOptions(this);
        }
    }
}
