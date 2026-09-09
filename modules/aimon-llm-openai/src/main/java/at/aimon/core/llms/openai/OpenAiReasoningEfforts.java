package at.aimon.core.llms.openai;

import at.aimon.core.llm.ReasoningEffort;

/**
 * Maps the provider-neutral {@link ReasoningEffort} to OpenAI's wire vocabulary.
 *
 * <p>
 * The counterpart of {@link OpenAiStopReasons}, in the other direction: this is the single place the translation
 * happens, so the SDK's own {@code ReasoningEffort} — a same-named type — never appears in an import list beside the
 * neutral one and never crosses the module boundary.
 *
 * <p>
 * The neutral enum is the common subset the SDK's ladder shares with other providers, so every constant maps; the
 * SDK's vendor-only rungs ({@code xhigh}, {@code max}) simply have no neutral name to arrive from.
 */
final class OpenAiReasoningEfforts {

    private OpenAiReasoningEfforts() {
    }

    /**
     * Maps a neutral reasoning effort to the SDK value.
     *
     * @param effort
     *            the neutral effort (must not be null)
     * @return the SDK's corresponding value
     */
    static com.openai.models.ReasoningEffort toWire(ReasoningEffort effort) {
        return switch (effort) {
            case NONE -> com.openai.models.ReasoningEffort.NONE;
            case MINIMAL -> com.openai.models.ReasoningEffort.MINIMAL;
            case LOW -> com.openai.models.ReasoningEffort.LOW;
            case MEDIUM -> com.openai.models.ReasoningEffort.MEDIUM;
            case HIGH -> com.openai.models.ReasoningEffort.HIGH;
        };
    }
}
