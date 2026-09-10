package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.openai.models.responses.ResponseStreamEvent;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamChunk;

/**
 * Drives {@link OpenAIResponsesStreamingMapper} over a stream carrying both reasoning delta families, with the
 * forwarding gate closed and open.
 *
 * <p>
 * The gate is configuration, not the arrival of the events: OpenAI only sends summary deltas when asked, but an
 * OpenAI-compatible gateway behind {@code baseUrl} may send them unasked, and a deployment that set nothing must see
 * no change. The closed-gate cases are that claim.
 *
 * <p>
 * Both families are forwarded under the one gate. Forwarding only {@code reasoning_summary_text} would leave a model
 * that emits raw {@code reasoning_text} showing nothing to a deployment that asked to see reasoning — the
 * always-empty channel this feature exists to remove, at one-model granularity.
 */
@DisplayName("OpenAIResponsesStreamingMapper - reasoning deltas and the forwarding gate")
class OpenAIResponsesReasoningStreamTest {

    private static final OpenAIDivergenceReporter SILENT = (signature, message, args) -> {
    };

    private final List<LlmStreamChunk> emitted = new ArrayList<>();
    private final ChunkAggregator aggregator = new ChunkAggregator();

    private LlmResponse consume(boolean forwardReasoning, String... eventJson) {
        final List<ResponseStreamEvent> events = new ArrayList<>();
        for (String json : eventJson) {
            events.add(ResponsesFixtures.event(json));
        }
        new OpenAIResponsesStreamingMapper(emitted::add, aggregator, new OpenAIResponsesMessageConverter(), "OpenAI",
                SILENT, forwardReasoning).consume(events.stream());
        return aggregator.toLlmResponse();
    }

    private static String textDelta(String text, int seq) {
        return "{\"type\":\"response.output_text.delta\",\"content_index\":0,\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"logprobs\":[],\"delta\":\"" + text + "\",\"sequence_number\":" + seq + "}";
    }

    private static String summaryTextDelta(String text, int seq) {
        return "{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\",\"output_index\":0,"
                + "\"summary_index\":0,\"delta\":\"" + text + "\",\"sequence_number\":" + seq + "}";
    }

    private static String reasoningTextDelta(String text, int seq) {
        return "{\"type\":\"response.reasoning_text.delta\",\"item_id\":\"rs_1\",\"output_index\":0,"
                + "\"content_index\":0,\"delta\":\"" + text + "\",\"sequence_number\":" + seq + "}";
    }

    private static String completed(int seq) {
        return "{\"type\":\"response.completed\",\"sequence_number\":" + seq + ",\"response\":{\"id\":\"r\","
                + "\"created_at\":1,\"model\":\"m\",\"object\":\"response\",\"parallel_tool_calls\":true,"
                + "\"tool_choice\":\"auto\",\"tools\":[],\"status\":\"completed\",\"output\":[]}}";
    }

    private List<String> reasoningDeltas() {
        return emitted.stream().filter(chunk -> chunk.getKind() == LlmStreamChunk.Kind.REASONING_DELTA)
                .map(chunk -> chunk.getReasoningDelta().orElseThrow()).toList();
    }

    @Test
    @DisplayName("with the gate closed both delta families are ignored, exactly as before this channel existed")
    void gateClosedForwardsNothing() {
        final LlmResponse response = consume(false, textDelta("Hel", 1), summaryTextDelta("weighing ", 2),
                reasoningTextDelta("the options", 3), textDelta("lo", 4), completed(5));

        assertThat(reasoningDeltas()).isEmpty();
        assertThat(response.getTextContent()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("with the gate open both delta families reach the sink, in arrival order")
    void gateOpenForwardsBothFamilies() {
        consume(true, textDelta("Hel", 1), summaryTextDelta("weighing ", 2), reasoningTextDelta("the options", 3),
                textDelta("lo", 4), completed(5));

        assertThat(reasoningDeltas()).containsExactly("weighing ", "the options");
    }

    @Test
    @DisplayName("reasoning text never reaches the response text, only the aggregator's second buffer")
    void reasoningNeverBecomesTheAnswer() {
        final LlmResponse response = consume(true, summaryTextDelta("SECRET-DELIBERATION", 1), textDelta("Hello", 2),
                completed(3));

        assertThat(response.getTextContent()).isEqualTo("Hello");
        assertThat(aggregator.peekReasoningText()).isEqualTo("SECRET-DELIBERATION");
    }

    @Test
    @DisplayName("reasoning text is not turned into a reasoning trace — that payload is encrypted_content")
    void reasoningDeltasDoNotBecomeTraces() {
        // Merging them would change what is replayed to the model on the next iteration, and a summary is not a
        // substitute for the item it summarises.
        final LlmResponse response = consume(true, summaryTextDelta("weighing the options", 1), completed(2));

        assertThat(response.getReasoningTraces()).isEmpty();
    }

    @Test
    @DisplayName("an empty delta is filtered before emission, as an empty text delta is")
    void emptyDeltasAreFiltered() {
        consume(true, summaryTextDelta("", 1), summaryTextDelta("real", 2), completed(3));

        assertThat(reasoningDeltas()).containsExactly("real");
    }
}
