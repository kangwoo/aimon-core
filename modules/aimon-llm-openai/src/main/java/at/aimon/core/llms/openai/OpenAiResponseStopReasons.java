package at.aimon.core.llms.openai;

import at.aimon.core.llm.StopReason;

/**
 * Maps the Responses API's terminating vocabulary to the provider-neutral {@link StopReason}.
 *
 * <p>
 * Separate from {@link OpenAiStopReasons} because the two endpoints do not share a vocabulary. Chat Completions
 * reports one {@code finish_reason} string that already distinguishes a tool-calling turn ({@code tool_calls}) from a
 * plain one ({@code stop}). Responses reports {@code status} plus {@code incomplete_details.reason} and says only
 * {@code completed} either way, leaving the distinction to the output array — which is why this method takes a third
 * argument the Chat one does not need. Without it every tool-calling turn on the new path would report
 * {@link StopReason#END_TURN}, and {@code OrcaAgentExecutor} branches on that.
 *
 * <p>
 * Takes wire strings rather than SDK enums so the mapping is a pure function over the values that actually arrive,
 * including ones this SDK version does not model.
 */
final class OpenAiResponseStopReasons {

    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_INCOMPLETE = "incomplete";
    private static final String REASON_MAX_OUTPUT_TOKENS = "max_output_tokens";
    private static final String REASON_CONTENT_FILTER = "content_filter";

    private OpenAiResponseStopReasons() {
    }

    /**
     * Maps a response status to the neutral enum.
     *
     * @param status
     *            the raw {@code status} value, or {@code null} when the response carried none — a gateway that omits
     *            it is read leniently, as though the turn completed, rather than as a failure
     * @param incompleteReason
     *            the raw {@code incomplete_details.reason} value, or {@code null}
     * @param hasToolCalls
     *            whether the output array carried at least one {@code function_call}
     * @return the neutral stop reason; {@link StopReason#UNKNOWN} for anything unrecognised
     */
    static StopReason fromStatus(String status, String incompleteReason, boolean hasToolCalls) {
        if (status == null || STATUS_COMPLETED.equals(status)) {
            return hasToolCalls ? StopReason.TOOL_USE : StopReason.END_TURN;
        }
        if (STATUS_INCOMPLETE.equals(status)) {
            if (REASON_MAX_OUTPUT_TOKENS.equals(incompleteReason)) {
                return StopReason.MAX_TOKENS;
            }
            if (REASON_CONTENT_FILTER.equals(incompleteReason)) {
                return StopReason.REFUSAL;
            }
            return StopReason.UNKNOWN;
        }
        // failed / cancelled / in_progress / queued are intercepted by OpenAiResponseErrors before a response is
        // converted, so they do not reach this method in practice. Mapped defensively rather than left to a switch
        // that would throw on a status the SDK adds later.
        return StopReason.UNKNOWN;
    }
}
