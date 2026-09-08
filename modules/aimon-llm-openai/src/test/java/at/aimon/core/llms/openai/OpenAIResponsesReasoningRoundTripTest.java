package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.ToolUseResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Acceptance criterion 2, at the seam: a reasoning model with tools completes a multi-turn tool loop with its
 * reasoning items carried across the turn.
 *
 * <p>
 * <strong>The payload assertion is exact, not {@code contains}.</strong> A plain {@code ObjectMapper}
 * <em>preserves</em>
 * {@code encrypted_content} and unknown fields, so a {@code contains("ZZZ")} assertion is green under the wrong mapper.
 * The corruption is an <em>addition</em> — {@code "valid":true} from the model's {@code isValid()}, and
 * {@code "content":null} from an {@code Optional} accessor without the SDK's {@code NON_ABSENT} inclusion — so only a
 * comparison that notices extra keys can fail on it.
 *
 * <p>
 * This test binds the provider. It cannot bind a caller: step 3 reproduces the executor's attachment by hand, which is
 * green for every possible executor. The caller side is bound separately, in {@code aimon-core}, by the four
 * reasoning-trace tests on the four ReAct loops.
 */
@DisplayName("OpenAI Responses - reasoning items round-trip across a tool call")
@ExtendWith(MockitoExtension.class)
class OpenAIResponsesReasoningRoundTripTest {

    private static final String ENCRYPTED = "gAAAAABmZ3JhdGlz";

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ResponseService mockResponseService;

    private OpenAILlmClient client() {
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        return new OpenAILlmClient(OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build(),
                mockOpenAIClient);
    }

    private static String reasoningItem(String id, String encrypted) {
        return "{\"type\":\"reasoning\",\"id\":\"" + id + "\",\"summary\":[],\"encrypted_content\":\"" + encrypted
                + "\"}";
    }

    private static String functionCall(String callId, String name) {
        return "{\"type\":\"function_call\",\"id\":\"fc_x\",\"call_id\":\"" + callId + "\",\"name\":\"" + name
                + "\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}";
    }

    private static Response responseWithOutput(String... items) {
        return ResponsesFixtures.response("{\"id\":\"resp_1\",\"created_at\":1,\"model\":\"gpt-5.6-terra\","
                + "\"object\":\"response\",\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
                + "\"status\":\"completed\",\"output\":[" + String.join(",", items) + "]}");
    }

    @Test
    @DisplayName("a reasoning item is captured, stored, and replayed verbatim in front of its own call")
    void reasoningItemSurvivesTheTurn() {
        final OpenAILlmClient client = client();
        when(mockResponseService.create(any(ResponseCreateParams.class)))
                .thenReturn(responseWithOutput(reasoningItem("rs_1", ENCRYPTED), functionCall("call_1", "Bash")));

        // ---- turn 1: capture ----
        final LlmResponse first = client.sendMessage("You are helpful", List.of(Message.user("list the files")),
                List.of(), LlmModel.builder().build());

        assertThat(first.getReasoningTraces()).hasSize(1);
        final ReasoningTrace trace = first.getReasoningTraces().get(0);
        assertThat(trace.getProviderName()).isEqualTo("OpenAI");
        assertThat(trace.getToolUseId()).contains("call_1");
        assertThat(first.getToolUses()).singleElement().satisfies(use -> assertThat(use.getId()).isEqualTo("call_1"));
        assertThat(first.getStopReason()).contains(StopReason.TOOL_USE);

        // ---- turn 2: replay, built the way an executor builds it ----
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenReturn(responseWithOutput());
        client.sendMessage("You are helpful",
                List.of(Message.user("list the files"),
                        Message.assistant(first.getTextContent(), first.getToolUses())
                                .withReasoningTraces(first.getReasoningTraces()),
                        Message.toolUseResults(List.of(ToolUseResult.success("call_1", "a.txt")))),
                List.of(), LlmModel.builder().build());

        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService, org.mockito.Mockito.times(2)).create(captor.capture());
        final JsonNode input = ResponsesFixtures.bodyTreeOf(captor.getAllValues().get(1)).get("input");

        // The order is the whole point: reasoning, then the call it produced, then that call's output.
        assertThat(itemTypes(input)).containsExactly("role:user", "reasoning", "function_call", "function_call_output");
        assertThat(input.get(2).get("call_id").asText()).isEqualTo("call_1");
        assertThat(input.get(3).get("call_id").asText()).isEqualTo("call_1");

        // Byte-level: the replayed item is the item that came back, with nothing added. A plain ObjectMapper would
        // pass a contains("ZZZ") assertion here and fail this one, because its corruption is additive.
        final JsonNode replayed = input.get(1);
        assertThat(replayed.get("encrypted_content").asText()).isEqualTo(ENCRYPTED);
        assertThat(replayed.has("valid")).as("a plain ObjectMapper invents isValid() as a field").isFalse();
        assertThat(replayed.has("content")).as("a plain ObjectMapper writes an absent Optional as null").isFalse();
        assertThat(fieldNames(replayed)).containsExactlyInAnyOrder("type", "id", "summary", "encrypted_content");
    }

    @Test
    @DisplayName("two reasoning items stay next to their own calls rather than being emitted together")
    void reasoningItemsStayNextToTheirOwnCall() {
        // The case the naive "all traces first" implementation gets wrong. [r1, call_1, r2, call_2] must come back
        // interleaved; a flat list would put r1 and r2 in front of both calls and OpenAI rejects that shape.
        final OpenAILlmClient client = client();
        when(mockResponseService.create(any(ResponseCreateParams.class)))
                .thenReturn(responseWithOutput(reasoningItem("rs_1", "AAA"), functionCall("call_1", "Bash"),
                        reasoningItem("rs_2", "BBB"), functionCall("call_2", "Read")));

        final LlmResponse first = client.sendMessage("sys", List.of(Message.user("go")), List.of(),
                LlmModel.builder().build());

        assertThat(first.getReasoningTraces()).extracting(t -> t.getToolUseId().orElse(null)).containsExactly("call_1",
                "call_2");

        when(mockResponseService.create(any(ResponseCreateParams.class))).thenReturn(responseWithOutput());
        client.sendMessage("sys",
                List.of(Message.user("go"),
                        Message.assistant("", first.getToolUses()).withReasoningTraces(first.getReasoningTraces())),
                List.of(), LlmModel.builder().build());

        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService, org.mockito.Mockito.times(2)).create(captor.capture());
        final JsonNode input = ResponsesFixtures.bodyTreeOf(captor.getAllValues().get(1)).get("input");

        assertThat(itemTypes(input)).containsExactly("role:user", "reasoning", "function_call", "reasoning",
                "function_call");
        assertThat(input.get(1).get("encrypted_content").asText()).isEqualTo("AAA");
        assertThat(input.get(2).get("call_id").asText()).isEqualTo("call_1");
        assertThat(input.get(3).get("encrypted_content").asText()).isEqualTo("BBB");
        assertThat(input.get(4).get("call_id").asText()).isEqualTo("call_2");
    }

    @Test
    @DisplayName("an unanchored trace leads the turn, in front of the assistant's own text")
    void unanchoredTracesLeadTheAssistantTurn() {
        final OpenAILlmClient client = client();
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenReturn(responseWithOutput());
        final ReasoningTrace unanchored = ReasoningTrace.builder().providerName("OpenAI")
                .payload(reasoningItem("rs_1", "CCC")).build();

        client.sendMessage("sys",
                List.of(Message.assistant("here is the answer").withReasoningTraces(List.of(unanchored))), List.of(),
                LlmModel.builder().build());

        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService).create(captor.capture());
        final JsonNode input = ResponsesFixtures.bodyTreeOf(captor.getValue()).get("input");

        assertThat(itemTypes(input)).containsExactly("reasoning", "role:assistant");
        // The assistant text item is an EasyInputMessage, which carries no "type" -- Message.Role has no ASSISTANT
        // constant, and ResponseOutputMessage would require inventing a server-assigned id.
        assertThat(input.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(input.get(1).get("content").asText()).isEqualTo("here is the answer");
    }

    @Test
    @DisplayName("a message carrying an Anthropic trace sends no reasoning item and warns once")
    void aForeignTraceIsDroppedRatherThanSent() {
        // A transcript outlives a client: a fallback policy can move a session onto another model mid-run, and a
        // subagent snapshot can be replayed anywhere. Feeding an Anthropic thinking block to /v1/responses is a 400.
        final OpenAILlmClient client = client();
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenReturn(responseWithOutput());
        final ReasoningTrace foreign = ReasoningTrace.builder().providerName("Anthropic")
                .payload("{\"type\":\"thinking\",\"thinking\":\"...\",\"signature\":\"abc\"}").build();

        final Logger clientLogger = (Logger) LoggerFactory.getLogger(OpenAILlmClient.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        clientLogger.addAppender(appender);
        try {
            client.sendMessage("sys", List.of(Message.assistant("hi").withReasoningTraces(List.of(foreign))), List.of(),
                    LlmModel.builder().build());
            // Twice: the divergence is reported once per distinct signature, not once per ReAct iteration.
            client.sendMessage("sys", List.of(Message.assistant("hi").withReasoningTraces(List.of(foreign))), List.of(),
                    LlmModel.builder().build());

            final List<String> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).contains("Anthropic").contains("OpenAI");
        } finally {
            clientLogger.detachAppender(appender);
        }

        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService, org.mockito.Mockito.times(2)).create(captor.capture());
        // Asserted on the input array, not on the whole body: "reasoning.encrypted_content" appears in `include` on
        // every request, so a doesNotContain("reasoning") over the body would be red for the wrong reason.
        assertThat(itemTypes(ResponsesFixtures.bodyTreeOf(captor.getValue()).get("input"))).doesNotContain("reasoning");
        assertThat(ResponsesFixtures.bodyOf(captor.getValue())).doesNotContain("thinking");
    }

    @Test
    @DisplayName("a stored payload this build cannot parse costs the trace, not the turn")
    void anUnparseablePayloadIsDroppedNotThrown() {
        final OpenAILlmClient client = client();
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenReturn(responseWithOutput());
        final ReasoningTrace broken = ReasoningTrace.builder().providerName("OpenAI").payload("{not json at all")
                .build();

        final LlmResponse response = client.sendMessage("sys",
                List.of(Message.assistant("hi").withReasoningTraces(List.of(broken))), List.of(),
                LlmModel.builder().build());

        assertThat(response).isNotNull();
        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService).create(captor.capture());
        assertThat(ResponsesFixtures.bodyOf(captor.getValue())).doesNotContain("not json at all");
    }

    @Test
    @DisplayName("a tool result becomes a function_call_output keyed by call_id, error prefix included")
    void toolResultsUseCallIdAndKeepTheErrorPrefix() {
        final OpenAILlmClient client = client();
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenReturn(responseWithOutput());

        client.sendMessage("sys",
                List.of(Message.toolUseResults(
                        List.of(ToolUseResult.success("call_1", "ok"), ToolUseResult.error("call_2", "boom")))),
                List.of(), LlmModel.builder().build());

        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService).create(captor.capture());
        final JsonNode input = ResponsesFixtures.bodyTreeOf(captor.getValue()).get("input");

        assertThat(input).hasSize(2);
        assertThat(input.get(0).get("call_id").asText()).isEqualTo("call_1");
        assertThat(input.get(0).get("output").asText()).isEqualTo("ok");
        assertThat(input.get(1).get("call_id").asText()).isEqualTo("call_2");
        // Parity with the Chat converter's formatToolResult: writing getContent() straight through would quietly
        // change what a failed tool call looks like to the model on the new endpoint.
        assertThat(input.get(1).get("output").asText()).isEqualTo("Error: boom");
    }

    /**
     * The {@code type} of each input item, or {@code role:<role>} for the two message carriers, which do not write a
     * {@code type} discriminator (the API infers those from their shape, and the SDK's builders leave the field unset
     * by default). Naming the role instead of {@code null} is what makes an ordering failure readable.
     */
    private static List<String> itemTypes(JsonNode input) {
        return java.util.stream.StreamSupport.stream(input.spliterator(), false).map(item -> {
            if (item.hasNonNull("type")) {
                return item.get("type").asText();
            }
            return "role:" + (item.hasNonNull("role") ? item.get("role").asText() : "?");
        }).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        final List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
