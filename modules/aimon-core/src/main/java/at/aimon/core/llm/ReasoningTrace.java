package at.aimon.core.llm;

import java.util.Objects;
import java.util.Optional;

/**
 * One provider-authored reasoning payload that a client must send back on the next turn for the model's reasoning to
 * survive a tool call.
 *
 * <p>
 * Reasoning models return an opaque item alongside their tool calls — OpenAI's {@code reasoning} item with its
 * {@code encrypted_content}, Anthropic's signature-carrying {@code thinking} block. A client that does not replay that
 * item on the following request makes the model re-derive its chain of thought on every ReAct iteration: worse answers,
 * and reasoning tokens billed again each time. This type is where that item lives between turns.
 *
 * <p>
 * <strong>{@code aimon-core} never reads inside the payload.</strong> It stores and returns it; the one operation it
 * performs is copy. That is what keeps provider semantics out of the core and what lets the payload keep a property no
 * core-side rewrite could preserve — OpenAI's {@code encrypted_content} is ciphertext and Anthropic's
 * {@code signature} is a signature, so a single rewritten byte invalidates it. The consequence is stated rather than
 * hidden: {@link Message#mapText(java.util.function.UnaryOperator)} does <em>not</em> reach a reasoning payload, so
 * redaction does not either.
 *
 * <p>
 * <strong>{@link #getProviderName()} exists because a transcript outlives a client.</strong> A fallback policy can move
 * a session onto a different model mid-run, an operator can change the configured provider and resume, and a subagent
 * snapshot can be replayed anywhere. A client replays only its own traces and drops the rest — feeding an Anthropic
 * thinking block to an OpenAI endpoint is a 400 at best.
 *
 * <p>
 * <strong>{@link #getToolUseId()} is the anchor.</strong> A turn whose output is
 * {@code [reasoning, call_1, reasoning, call_2]} must be replayed with each reasoning item in front of the call it
 * produced; {@link Message} cannot express that order on its own, because its text lives in content blocks and its
 * calls live in a separate list. The anchor means <em>this trace immediately precedes the tool use with this id in the
 * provider's own output order</em>. Empty means it precedes the assistant's text, or the end of the turn.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     ReasoningTrace trace = ReasoningTrace.builder().providerName("OpenAI").payload(reasoningItemJson)
 *             .toolUseId("call_1").build();
 * }
 * </pre>
 *
 * @see Message#getReasoningTraces()
 * @see LlmResponse#getReasoningTraces()
 */
public final class ReasoningTrace {

    private final String providerName;
    private final String payload;
    private final String toolUseId;

    private ReasoningTrace(Builder builder) {
        this.providerName = requireNonBlank(builder.providerName, "Provider name cannot be null or blank");
        this.payload = requireNonBlank(builder.payload, "Payload cannot be null or blank");
        this.toolUseId = builder.toolUseId;
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * Creates a new builder.
     *
     * @return A new ReasoningTrace.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the name of the provider that authored this payload, as reported by
     * {@link LlmClient#getProviderName()} (for example {@code "OpenAI"} or {@code "Anthropic"}).
     *
     * <p>
     * A client compares this to its own provider name and drops a trace it did not author.
     *
     * @return The provider name (never null, never blank)
     */
    public String getProviderName() {
        return providerName;
    }

    /**
     * Gets the provider-owned opaque payload.
     *
     * <p>
     * The encoding is the provider's to choose and the provider's to keep byte-exact; nothing in {@code aimon-core}
     * parses, rewrites, normalizes or truncates it.
     *
     * @return The payload (never null, never blank)
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Gets the id of the tool use this trace immediately precedes in the provider's output order.
     *
     * @return The tool use id, or empty when the trace precedes the assistant's text / the end of the turn
     */
    public Optional<String> getToolUseId() {
        return Optional.ofNullable(toolUseId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ReasoningTrace that = (ReasoningTrace) o;
        return providerName.equals(that.providerName) && payload.equals(that.payload)
                && Objects.equals(toolUseId, that.toolUseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerName, payload, toolUseId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Prints the payload's <em>length</em>, never the payload: a transcript blob in a log line is noise at best and a
     * leak at worst.
     */
    @Override
    public String toString() {
        return "ReasoningTrace{provider='" + providerName + "', payloadLength=" + payload.length() + ", toolUseId="
                + toolUseId + '}';
    }

    /** Builder for {@link ReasoningTrace}. */
    public static final class Builder {
        private String providerName;
        private String payload;
        private String toolUseId;

        private Builder() {
        }

        /**
         * Sets the authoring provider's name.
         *
         * @param providerName
         *            the provider name, as {@link LlmClient#getProviderName()} reports it (must not be null or blank)
         * @return This builder
         */
        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        /**
         * Sets the opaque provider payload.
         *
         * @param payload
         *            the provider-owned payload (must not be null or blank)
         * @return This builder
         */
        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        /**
         * Sets the anchor.
         *
         * @param toolUseId
         *            the id of the tool use this trace immediately precedes, or null when it precedes the assistant's
         *            text / the end of the turn
         * @return This builder
         */
        public Builder toolUseId(String toolUseId) {
            this.toolUseId = toolUseId;
            return this;
        }

        /**
         * @return A new {@link ReasoningTrace}
         * @throws NullPointerException
         *             if providerName or payload is null
         * @throws IllegalArgumentException
         *             if providerName or payload is blank
         */
        public ReasoningTrace build() {
            return new ReasoningTrace(this);
        }
    }
}
