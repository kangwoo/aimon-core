package at.aimon.core.llms.openai;

import at.aimon.core.llm.StopReason;

/**
 * Maps OpenAI's wire finish-reason vocabulary to the provider-neutral {@link StopReason}.
 *
 * <p>
 * OpenAI reports the terminating reason as one of {@code stop}, {@code length}, {@code tool_calls},
 * {@code function_call}, or {@code content_filter}. This helper is the single place that vocabulary is translated, so
 * the raw SDK value never crosses the module boundary — {@code aimon-core} only ever sees the neutral enum. Any
 * unrecognised or absent value maps to {@link StopReason#UNKNOWN}, preserving the historical "no reason available"
 * behaviour.
 */
final class OpenAiStopReasons {

    private OpenAiStopReasons() {
    }

    /**
     * Maps a raw OpenAI finish-reason wire string to the neutral enum.
     *
     * @param wire
     *            the wire value (e.g. from {@code finishReason.asString()}); may be {@code null}
     * @return the neutral {@link StopReason}; {@link StopReason#UNKNOWN} when {@code wire} is null or unrecognised
     */
    static StopReason fromWire(String wire) {
        if (wire == null) {
            return StopReason.UNKNOWN;
        }
        return switch (wire) {
            case "stop" -> StopReason.END_TURN;
            case "length" -> StopReason.MAX_TOKENS;
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "content_filter" -> StopReason.REFUSAL;
            default -> StopReason.UNKNOWN;
        };
    }
}
