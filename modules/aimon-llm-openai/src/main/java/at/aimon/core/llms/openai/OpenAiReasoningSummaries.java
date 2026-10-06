package at.aimon.core.llms.openai;

import java.util.Optional;

import com.openai.models.Reasoning;

import at.aimon.core.llm.ReasoningSummary;

/**
 * Maps {@link OpenAiReasoningSummary} to OpenAI's wire vocabulary.
 *
 * <p>
 * The sibling of {@link OpenAiReasoningEfforts}, and here for the same reason: the SDK type stays inside this class so
 * it never reaches a configuration surface or a module boundary. Unlike that one the two ladders are the same length —
 * this enum exists to keep the SDK out, not to translate between different vocabularies.
 */
final class OpenAiReasoningSummaries {

    private OpenAiReasoningSummaries() {
    }

    /**
     * Maps the provider-neutral request an agent definition states onto this vendor's level.
     *
     * <p>
     * The three levels map by name. {@link ReasoningSummary#NONE} maps to empty: this vendor has no "none" value —
     * no summary is what the request gets by carrying no {@code reasoning.summary} — which is also why the deployment
     * key has three values and the agent key four.
     *
     * @param summary
     *            the neutral request (must not be null)
     * @return the vendor level, or empty for {@link ReasoningSummary#NONE}
     */
    static Optional<OpenAiReasoningSummary> fromNeutral(ReasoningSummary summary) {
        return switch (summary) {
            case NONE -> Optional.empty();
            case AUTO -> Optional.of(OpenAiReasoningSummary.AUTO);
            case CONCISE -> Optional.of(OpenAiReasoningSummary.CONCISE);
            case DETAILED -> Optional.of(OpenAiReasoningSummary.DETAILED);
        };
    }

    /**
     * Maps a configured summary level to the SDK value.
     *
     * @param summary
     *            the configured level (must not be null)
     * @return the SDK's corresponding value
     */
    static Reasoning.Summary toWire(OpenAiReasoningSummary summary) {
        return switch (summary) {
            case AUTO -> Reasoning.Summary.AUTO;
            case CONCISE -> Reasoning.Summary.CONCISE;
            case DETAILED -> Reasoning.Summary.DETAILED;
        };
    }
}
