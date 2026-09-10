package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.openai.models.responses.ResponseStreamEvent;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * Tests the second streaming mapper.
 *
 * <p>
 * <strong>The load-bearing test is {@link #tracesComeFromOutputItemDoneNotTheTerminalResponse()}.</strong> Its stream
 * carries {@code encrypted_content} on the {@code output_item.done} event and <em>not</em> on the terminal
 * {@code response.completed} — which is what the SDK warns about for exactly this field under {@code store: false},
 * the mode this client uses. An implementation reading traces from the terminal response passes every other test in
 * this class and is inert in production on the ReAct loop's primary path.
 */
@DisplayName("OpenAIResponsesStreamingMapper")
class OpenAIResponsesStreamingMapperTest {

    private static final OpenAIDivergenceReporter SILENT = (signature, message, args) -> {
    };

    private final List<LlmStreamChunk> emitted = new ArrayList<>();
    private final LlmStreamSink sink = emitted::add;
    private final ChunkAggregator aggregator = new ChunkAggregator();

    private LlmResponse consume(OpenAIDivergenceReporter reporter, String... eventJson) {
        return consume(reporter, false, eventJson);
    }

    /**
     * @param forwardReasoning
     *            whether the deployment asked for a reasoning summary — off in every case but one.
     */
    private LlmResponse consume(OpenAIDivergenceReporter reporter, boolean forwardReasoning, String... eventJson) {
        final List<ResponseStreamEvent> events = new ArrayList<>();
        for (String json : eventJson) {
            events.add(ResponsesFixtures.event(json));
        }
        new OpenAIResponsesStreamingMapper(sink, aggregator, new OpenAIResponsesMessageConverter(), "OpenAI", reporter,
                forwardReasoning).consume(events.stream());
        return aggregator.toLlmResponse();
    }

    private LlmResponse consume(String... eventJson) {
        return consume(SILENT, eventJson);
    }

    private static String textDelta(String text, int seq) {
        return "{\"type\":\"response.output_text.delta\",\"content_index\":0,\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"logprobs\":[],\"delta\":\"" + text + "\",\"sequence_number\":" + seq + "}";
    }

    private static String completed(String status, String usage, int seq, String... outputItems) {
        final String usagePart = usage == null ? "" : ",\"usage\":" + usage;
        return "{\"type\":\"response." + status + "\",\"sequence_number\":" + seq + ",\"response\":{\"id\":\"r\","
                + "\"created_at\":1,\"model\":\"m\",\"object\":\"response\",\"parallel_tool_calls\":true,"
                + "\"tool_choice\":\"auto\",\"tools\":[],\"status\":\""
                + ("completed".equals(status) ? "completed" : "incomplete") + "\""
                + ("incomplete".equals(status) ? ",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}" : "")
                + ",\"output\":[" + String.join(",", outputItems) + "]" + usagePart + "}}";
    }

    @Test
    @DisplayName("text deltas reach the sink and the aggregated response")
    void textDeltasReachTheSink() {
        final LlmResponse response = consume(textDelta("Hel", 1), textDelta("lo", 2), completed("completed", null, 3));

        assertThat(emitted).filteredOn(c -> c.getKind() == LlmStreamChunk.Kind.TEXT_DELTA)
                .extracting(c -> c.getTextDelta().orElseThrow()).containsExactly("Hel", "lo");
        assertThat(response.getTextContent()).isEqualTo("Hello");
        assertThat(response.getStopReason()).contains(StopReason.END_TURN);
    }

    @Test
    @DisplayName("tool call arguments accumulate by output_index and emit exactly one TOOL_USE_READY")
    void toolCallArgumentsAccumulate() {
        // Keyed by output_index rather than by the item's own id: ResponseFunctionToolCall.id() is Optional in the
        // SDK while call_id is required, so a gateway that omits the item id would lose the slot. output_index is a
        // required Long on BOTH event families.
        final LlmResponse response = consume(
                "{\"type\":\"response.output_item.added\",\"output_index\":0,\"sequence_number\":1,"
                        + "\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"Bash\","
                        + "\"arguments\":\"\"}}",
                "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"output_index\":0,"
                        + "\"sequence_number\":2,\"delta\":\"{\\\"command\\\":\"}",
                "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"output_index\":0,"
                        + "\"sequence_number\":3,\"delta\":\"\\\"ls\\\"}\"}",
                "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\",\"output_index\":0,"
                        + "\"sequence_number\":4,\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}",
                "{\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":5,"
                        + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\","
                        + "\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}",
                completed("completed", null, 6));

        assertThat(emitted).filteredOn(c -> c.getKind() == LlmStreamChunk.Kind.TOOL_USE_READY).hasSize(1);
        assertThat(response.getToolUses()).singleElement().satisfies(use -> {
            assertThat(use.getId()).isEqualTo("call_1");
            assertThat(use.getName()).isEqualTo("Bash");
            assertThat(use.getInput()).containsEntry("command", "ls");
        });
        assertThat(response.getStopReason()).contains(StopReason.TOOL_USE);
    }

    @Test
    @DisplayName("reasoning traces come from output_item.done, not from the terminal response")
    void tracesComeFromOutputItemDoneNotTheTerminalResponse() {
        // The terminal response's output array is deliberately EMPTY here. Under store:false the SDK warns that the
        // completed item and its encrypted_content must be taken from output_item.done -- so an implementation
        // reading response.output() yields no traces at all and the whole feature is inert on this path, while every
        // other test in this class stays green.
        final LlmResponse response = consume(
                "{\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,"
                        + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],"
                        + "\"encrypted_content\":\"ZZZ\"}}",
                "{\"type\":\"response.output_item.done\",\"output_index\":1,\"sequence_number\":2,"
                        + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\","
                        + "\"name\":\"Bash\",\"arguments\":\"{}\"}}",
                completed("completed", null, 3));

        assertThat(response.getReasoningTraces()).singleElement().satisfies(trace -> {
            assertThat(trace.getPayload()).contains("ZZZ");
            assertThat(trace.getToolUseId()).contains("call_1");
            assertThat(trace.getProviderName()).isEqualTo("OpenAI");
        });
    }

    @Test
    @DisplayName("response.completed supplies the usage, reasoning tokens included")
    void completedSuppliesUsage() {
        final LlmResponse response = consume(
                completed("completed", "{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15,"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":3}}", 1));

        assertThat(response.getTokenUsage().getReasoningTokens()).isEqualTo(3);
        assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(15);
    }

    @Test
    @DisplayName("response.incomplete with max_output_tokens is a stop reason, not an error")
    void incompleteIsAStopReason() {
        final LlmResponse response = consume(textDelta("part", 1), completed("incomplete", null, 2));

        assertThat(response.getStopReason()).contains(StopReason.MAX_TOKENS);
        assertThat(response.getTextContent()).isEqualTo("part");
    }

    @Test
    @DisplayName("reasoning items with no encrypted_content produce exactly one warning")
    void silentNoOpIsAnnounced() {
        // A feature that quietly does nothing is the failure this round exists to fix; it should at least say so.
        final List<String> reported = new ArrayList<>();
        consume((signature, message, args) -> reported.add(signature),
                "{\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,"
                        + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]}}",
                completed("completed", null, 2));

        assertThat(reported).containsExactly("reasoningWithoutEncryptedContent@OpenAI");
    }

    @Test
    @DisplayName("a stream that carries encrypted_content warns about nothing")
    void aHealthyStreamIsSilent() {
        final List<String> reported = new ArrayList<>();
        consume((signature, message, args) -> reported.add(signature),
                "{\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,"
                        + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],"
                        + "\"encrypted_content\":\"ZZZ\"}}",
                completed("completed", null, 2));

        assertThat(reported).isEmpty();
    }

    @Test
    @DisplayName("exactly one STREAM_END is emitted, after the stream is drained")
    void oneStreamEndAfterTheDrain() {
        // Closing on drain is what keeps an unclosed aggregator from escaping as IllegalStateException from OUTSIDE
        // the client's try -- unmapped, unclassified, and reporting AIMON's internal state instead of the provider's.
        consume(textDelta("hi", 1), completed("completed", null, 2));

        assertThat(emitted).filteredOn(c -> c.getKind() == LlmStreamChunk.Kind.STREAM_END).hasSize(1);
        assertThat(aggregator.isClosed()).isTrue();
    }
}
