package at.aimon.core.llms.anthropic;

import at.aimon.core.llm.StopReason;

/**
 * Maps Anthropic's wire stop-reason vocabulary to the provider-neutral {@link StopReason}.
 *
 * <p>
 * Anthropic reports the terminating reason as one of {@code end_turn}, {@code max_tokens}, {@code tool_use},
 * {@code stop_sequence}, {@code refusal}, or {@code pause_turn}. This helper is the single place that vocabulary is
 * translated, so the raw SDK value never crosses the module boundary — {@code aimon-core} only ever sees the neutral
 * enum. Any unrecognised or absent value (including {@code pause_turn}, which has no neutral slot) maps to
 * {@link StopReason#UNKNOWN}, preserving the historical "no reason available" behaviour.
 */
final class AnthropicStopReasons {

    private AnthropicStopReasons() {
    }

    /**
     * Maps a raw Anthropic stop-reason wire string to the neutral enum.
     *
     * @param wire
     *            the wire value (e.g. from {@code stopReason.asString()}); may be {@code null}
     * @return the neutral {@link StopReason}; {@link StopReason#UNKNOWN} when {@code wire} is null or unrecognised
     */
    static StopReason fromWire(String wire) {
        if (wire == null) {
            return StopReason.UNKNOWN;
        }
        return switch (wire) {
            case "end_turn" -> StopReason.END_TURN;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "tool_use" -> StopReason.TOOL_USE;
            case "stop_sequence" -> StopReason.STOP_SEQUENCE;
            case "refusal" -> StopReason.REFUSAL;
            default -> StopReason.UNKNOWN;
        };
    }
}
