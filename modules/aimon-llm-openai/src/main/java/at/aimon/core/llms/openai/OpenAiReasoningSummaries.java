package at.aimon.core.llms.openai;

import com.openai.models.Reasoning;

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
