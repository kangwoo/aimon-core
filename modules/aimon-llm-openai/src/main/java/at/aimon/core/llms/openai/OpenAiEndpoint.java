package at.aimon.core.llms.openai;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Which OpenAI endpoint an {@link OpenAILlmClient} sends its requests to.
 *
 * <p>
 * The endpoint is chosen per request, because the model is — a request's {@link at.aimon.core.llm.LlmModel} overrides
 * the client's configured one. This enum says how much of that choice the deployment leaves to the model:
 * {@link #AUTO} leaves all of it, the other two none.
 *
 * <p>
 * <strong>Forcing an endpoint changes where a request goes, never what the model is said to do.</strong> The
 * capability registry still answers for the model on either endpoint — sampling suppression, the effort ladder — and
 * the reasoning trace round trip in particular stays with
 * {@link at.aimon.core.llm.capability.ModelCapabilities#supportsReasoningTraceRoundTrip()}: a model sent to
 * {@code /v1/responses} by {@link #RESPONSES} whose row does not say it replays reasoning items is neither asked for
 * {@code reasoning.encrypted_content} nor sent stored traces.
 *
 * <p>
 * A framework enum rather than anything of the SDK's, for the reason {@link OpenAiReasoningSummary} gives.
 *
 * @see OpenAIConfig.Builder#endpoint(OpenAiEndpoint)
 */
public enum OpenAiEndpoint {

    /**
     * {@code /v1/responses} for a model whose capability row does the reasoning trace round trip, Chat Completions for
     * every other model. The default, and what this client did before the endpoint could be named.
     */
    AUTO,

    /**
     * Every request to {@code /v1/chat/completions}. For an OpenAI-compatible gateway that implements nothing else,
     * and the value the deprecated {@code responsesApiEnabled(false)} stands for. Reasoning items are not replayed and
     * {@code reasoning.summary} reaches nothing — Chat Completions has neither.
     */
    CHAT_COMPLETIONS,

    /**
     * Every request to {@code /v1/responses}, whatever the model's capability row says. For a deployment that wants
     * one endpoint for all its models, or a gateway name the built-in table does not know.
     */
    RESPONSES;

    /**
     * Folds a configuration value onto a constant: case-insensitive, with {@code -} and {@code _} interchangeable, so
     * {@code chat-completions}, {@code chat_completions} and {@code CHAT_COMPLETIONS} are one value. Both configuration
     * surfaces fold through this method, so they cannot come to accept different spellings.
     *
     * @param value
     *            the written value (must not be null)
     * @return the constant, or empty when the value names none
     */
    public static Optional<OpenAiEndpoint> fromConfigValue(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        final String folded = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (OpenAiEndpoint candidate : values()) {
            if (candidate.name().equals(folded)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The spelling the configuration surfaces document: lower case, words joined by {@code -}.
     *
     * @return e.g. {@code chat-completions}
     */
    public String configValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
