package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llms.openai.exception.MessageConversionException;

/**
 * What happens when a model emits a tool call whose {@code arguments} are not JSON.
 *
 * <p>
 * The Responses converter used to degrade that to an empty input map with a warning while
 * {@link OpenAIMessageConverter} threw for the same bytes — a divergence the Responses converter's own javadoc
 * (<em>"parity … case for case, including every throw"</em>) forbids. It is resolved by <strong>aligning</strong>: the
 * blocking read throws on both endpoints now, so switching endpoints does not change what identical provider output
 * means, and no tool is handed a fabricated empty input it can act on.
 *
 * <p>
 * <strong>The streaming test is the other half of the decision.</strong> The blocking/streaming split is real,
 * deliberate and older than this endpoint: on every provider AIMON has, streamed argument bytes are accumulated and
 * parsed by {@link ChunkAggregator}, which degrades. Aligning the blocking read must not drag the streaming path along
 * with it, and the narrowed {@code convertStreamedOutput} is what keeps it from doing so.
 *
 * <p>
 * <strong>The last two tests draw the other boundary: which bytes are "not JSON" at all.</strong> A JSON {@code null}
 * value is well-formed and means <em>absent</em>, which is a case both endpoints must survive rather than throw on —
 * so parity here is agreeing to drop the key, not agreeing to fail.
 */
@DisplayName("OpenAI Responses - what a tool call's arguments may contain")
@ExtendWith(MockitoExtension.class)
class OpenAIResponsesToolArgumentParityTest {

    /** Truncated after the key: syntactically a JSON string, not a JSON object. */
    private static final String MALFORMED = "{\"command\":";

    /** Well-formed, and carrying the shape a model produces for an optional parameter it has no value for. */
    private static final String NULL_VALUED = "{\"command\":\"ls\",\"timeout\":null}";

    /** The same bytes as a {@code function_call} output item. */
    private static final String NULL_VALUED_CALL_ITEM = "{\"type\":\"function_call\",\"id\":\"fc_2\","
            + "\"call_id\":\"call_2\",\"name\":\"Bash\","
            + "\"arguments\":\"{\\\"command\\\":\\\"ls\\\",\\\"timeout\\\":null}\"}";

    /** The same bytes as a {@code function_call} output item. */
    private static final String MALFORMED_CALL_ITEM = "{\"type\":\"function_call\",\"id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\"}";

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ResponseService mockResponseService;

    private static String completedResponse(String... outputItems) {
        return "{\"id\":\"r\",\"created_at\":1,\"model\":\"m\",\"object\":\"response\","
                + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],\"status\":\"completed\","
                + "\"output\":[" + String.join(",", outputItems) + "]}";
    }

    @Test
    @DisplayName("both converters reject the same bytes with the same exception and the same message")
    void bothConvertersRejectTheSameBytesTheSameWay() {
        // The parity claim, asserted against the Chat converter itself rather than against a remembered message, so a
        // change to either one fails here rather than drifting apart quietly.
        final Throwable chat = catchThrowable(() -> new OpenAIMessageConverter().parseJsonToMap(MALFORMED));
        final Throwable responses = catchThrowable(() -> new OpenAIResponsesMessageConverter()
                .convertOutput(ResponsesFixtures.response(completedResponse(MALFORMED_CALL_ITEM)).output(), "OpenAI"));

        assertThat(chat).isInstanceOf(MessageConversionException.class);
        assertThat(responses).isInstanceOf(MessageConversionException.class);
        assertThat(responses.getMessage()).isEqualTo(chat.getMessage()).isEqualTo("Failed to parse JSON: " + MALFORMED);
    }

    @Test
    @DisplayName("a blocking call fails rather than handing the tool a fabricated empty input")
    void blockingCallFailsRatherThanInventingEmptyArguments() {
        // Degrading is not a smaller answer, it is a different one: a tool whose parameters are all optional would run
        // with its defaults, having been told the model asked for nothing.
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        when(mockResponseService.create(any(ResponseCreateParams.class)))
                .thenReturn(ResponsesFixtures.response(completedResponse(MALFORMED_CALL_ITEM)));

        final OpenAILlmClient client = new OpenAILlmClient(
                OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build(), mockOpenAIClient);

        assertThatThrownBy(() -> client.sendMessage("sys", List.of(Message.user("hi")), Collections.emptyList(),
                LlmModel.builder().build())).isInstanceOf(LlmClientException.class)
                .hasCauseInstanceOf(MessageConversionException.class).hasMessageContaining("Failed to parse JSON");
    }

    @Test
    @DisplayName("the streaming path still degrades, because the aggregator owns those bytes there")
    void streamingKeepsTheAggregatorsDegradedToolCall() {
        // convertStreamedOutput must not read the arguments at all. If it did, this stream would die at stream end
        // over a tool call the aggregator had already handled -- a regression against Chat Completions and Anthropic,
        // which degrade the same bytes through the same aggregator.
        final ChunkAggregator aggregator = new ChunkAggregator();
        final List<ResponseStreamEvent> events = new ArrayList<>();
        events.add(ResponsesFixtures.event("{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"sequence_number\":1,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\","
                + "\"name\":\"Bash\",\"arguments\":\"\"}}"));
        events.add(ResponsesFixtures.event("{\"type\":\"response.function_call_arguments.delta\","
                + "\"item_id\":\"fc_1\",\"output_index\":0,\"sequence_number\":2,\"delta\":\"{\\\"command\\\":\"}"));
        events.add(ResponsesFixtures.event("{\"type\":\"response.function_call_arguments.done\","
                + "\"item_id\":\"fc_1\",\"output_index\":0,\"sequence_number\":3,\"name\":\"Bash\","
                + "\"arguments\":\"{\\\"command\\\":\"}"));
        events.add(ResponsesFixtures.event("{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"sequence_number\":4,\"item\":" + MALFORMED_CALL_ITEM + "}"));
        events.add(ResponsesFixtures.event("{\"type\":\"response.completed\",\"sequence_number\":5,\"response\":"
                + completedResponse(MALFORMED_CALL_ITEM) + "}"));

        final OpenAIResponsesStreamingMapper mapper = new OpenAIResponsesStreamingMapper(chunk -> {
        }, aggregator, new OpenAIResponsesMessageConverter(), "OpenAI", (signature, message, args) -> {
        });

        assertThatCode(() -> mapper.consume(events.stream())).doesNotThrowAnyException();

        final LlmResponse response = aggregator.toLlmResponse();
        assertThat(response.getToolUses()).singleElement().satisfies(use -> {
            assertThat(use.getName()).isEqualTo("Bash");
            assertThat(use.getInput()).isEmpty();
        });
        assertThat(response.getStopReason()).contains(StopReason.TOOL_USE);
    }

    @Test
    @DisplayName("a JSON null argument is dropped on both endpoints rather than failing the turn on one")
    void aNullArgumentValueIsDroppedOnBothEndpoints() {
        // Well-formed JSON, so this is not the malformed case above: a model routinely fills an optional parameter
        // it has no value for with null instead of omitting the key, and ToolUse's own contract says such values
        // "may be, and are dropped". Map.copyOf rejects them outright, so reading the arguments through it turned an
        // optional parameter into a failed turn -- on the endpoint gpt-5.x is routed to by default, and only there.
        final Map<String, Object> chat = new OpenAIMessageConverter().parseJsonToMap(NULL_VALUED);
        final List<ToolUse> responses = new OpenAIResponsesMessageConverter()
                .convertOutput(ResponsesFixtures.response(completedResponse(NULL_VALUED_CALL_ITEM)).output(), "OpenAI")
                .getToolUses();

        assertThat(responses).singleElement()
                .satisfies(use -> assertThat(use.getInput()).isEqualTo(ToolUse.of("call_2", "Bash", chat).getInput()));
        assertThat(responses.get(0).getInput()).containsExactly(entry("command", "ls"));
    }

    @Test
    @DisplayName("a blocking call with a null-valued argument completes and runs the tool without that key")
    void blockingCallSurvivesANullArgumentValue() {
        // The end-to-end statement of the same thing: before the fix this threw a NullPointerException out of
        // convertOutput, which escaped into the client's generic catch and was re-thrown through
        // OpenAIExceptionMapper -- so the whole turn failed, reporting an NPE rather than the argument.
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        when(mockResponseService.create(any(ResponseCreateParams.class)))
                .thenReturn(ResponsesFixtures.response(completedResponse(NULL_VALUED_CALL_ITEM)));

        final OpenAILlmClient client = new OpenAILlmClient(
                OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build(), mockOpenAIClient);

        final LlmResponse response = client.sendMessage("sys", List.of(Message.user("hi")), Collections.emptyList(),
                LlmModel.builder().build());

        assertThat(response.getToolUses()).singleElement().satisfies(use -> {
            assertThat(use.getId()).isEqualTo("call_2");
            assertThat(use.getInput()).containsExactly(entry("command", "ls"));
        });
    }
}
