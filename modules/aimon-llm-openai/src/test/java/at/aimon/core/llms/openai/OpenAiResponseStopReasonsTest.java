package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import at.aimon.core.llm.StopReason;

/**
 * Every row of the Responses status mapping, as a pure function.
 *
 * <p>
 * The {@code hasToolCalls} argument is the one real difference from {@code OpenAiStopReasons.fromWire}: Chat
 * Completions says {@code tool_calls} in the finish reason, whereas Responses says only {@code completed} and leaves
 * the distinction to the output array. Without it every tool-calling turn on this endpoint would report
 * {@code END_TURN}, and the executor branches on that.
 */
@DisplayName("OpenAiResponseStopReasons")
class OpenAiResponseStopReasonsTest {

    @ParameterizedTest(name = "status={0}, reason={1}, toolCalls={2} -> {3}")
    @CsvSource(nullValues = "null", value = {"completed,      null,               true,  TOOL_USE",
            "completed,      null,               false, END_TURN",
            "incomplete,     max_output_tokens,  false, MAX_TOKENS",
            "incomplete,     max_output_tokens,  true,  MAX_TOKENS",
            "incomplete,     content_filter,     false, REFUSAL", "incomplete,     null,               false, UNKNOWN",
            "incomplete,     something_new,      false, UNKNOWN",
            // A gateway that omits status is read leniently, as though the turn completed, rather than as a failure.
            "null,           null,               true,  TOOL_USE",
            "null,           null,               false, END_TURN",
            // These four are intercepted by OpenAiResponseErrors before a response is converted, so they never reach
            // this method in practice. Mapped defensively rather than left to a switch that throws on a status the
            // SDK adds later.
            "failed,         null,               false, UNKNOWN", "cancelled,      null,               false, UNKNOWN",
            "in_progress,    null,               false, UNKNOWN", "queued,         null,               false, UNKNOWN"})
    void mapsEveryRow(String status, String incompleteReason, boolean hasToolCalls, StopReason expected) {
        assertThat(OpenAiResponseStopReasons.fromStatus(status, incompleteReason, hasToolCalls)).isEqualTo(expected);
    }
}
