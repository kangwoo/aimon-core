package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The headline: a thinking block captured from one response comes back on the next request, in front of its own tool
 * use, with its signature unchanged.
 *
 * <p>
 * Each case drives the real {@code sendMessage} against a mocked {@code MessageService}, reproduces by hand what the
 * executor does between turns (attach {@code response.getReasoningTraces()} to the assistant message it builds), sends
 * again, and asserts on the serialised request body.
 *
 * <p>
 * What these tests <em>cannot</em> establish is stated once here rather than implied: no live call was made, so
 * nothing here shows that Anthropic's verifier accepts the re-serialised block. What is bound is the invariant this
 * client owns — the decoded signature that leaves equals the one that arrived, and the surrounding object gains and
 * loses no field.
 */
@DisplayName("AnthropicLlmClient - thinking blocks survive a tool call")
@ExtendWith(MockitoExtension.class)
class AnthropicReasoningRoundTripTest {

    private static final RuntimeException SENTINEL = new RuntimeException("second-create-invoked");

    private static final String SIGNATURE = "EqMBCkYICxIMabc/+DEF==";

    @Mock
    private AnthropicClient mockAnthropicClient;

    @Mock
    private MessageService mockMessageService;

    private Logger clientLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        clientLogger = (Logger) LoggerFactory.getLogger(AnthropicLlmClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static String thinkingBlock(String signature, String text) {
        return """
                {"type":"thinking","signature":"%s","thinking":"%s"}""".formatted(signature, text);
    }

    private static String toolUseBlock(String id, String name) {
        return """
                {"type":"tool_use","id":"%s","name":"%s","input":{"path":"/tmp"}}""".formatted(id, name);
    }

    private static String textBlock(String text) {
        return """
                {"type":"text","text":"%s"}""".formatted(text);
    }

    private static com.anthropic.models.messages.Message responseWith(String... contentBlocks) {
        return AnthropicFixtures.message("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
                 "stop_reason":"tool_use","content":[%s],
                 "usage":{"input_tokens":10,"output_tokens":20}}""".formatted(String.join(",", contentBlocks)));
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private AnthropicLlmClient client() {
        return client(new AnthropicLlmClient(config(), mockAnthropicClient));
    }

    private AnthropicLlmClient client(AnthropicLlmClient client) {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        return client;
    }

    private static AnthropicConfig config() {
        return AnthropicConfig.builder().apiKey("test-key").model("claude-sonnet-4-5").build();
    }

    /** Reproduces what an executor does between turns: the response's traces ride on the assistant message. */
    private static Message assistantTurn(LlmResponse response) {
        return Message.assistant(response.getTextContent(), response.getToolUses())
                .withReasoningTraces(response.getReasoningTraces());
    }

    private static List<Message> nextTurn(LlmResponse first) {
        final List<Message> messages = new ArrayList<>();
        messages.add(Message.user("list /tmp"));
        messages.add(assistantTurn(first));
        final List<ToolUseResult> results = first.getToolUses().stream()
                .map(toolUse -> ToolUseResult.success(toolUse.getId(), "ok")).toList();
        messages.add(Message.toolUseResults(results));
        return messages;
    }

    /** Sends the first turn against a fixture response, then a second turn whose body is returned as a tree. */
    private JsonNode replay(AnthropicLlmClient client, com.anthropic.models.messages.Message fixture) {
        when(mockMessageService.create(any(MessageCreateParams.class))).thenReturn(fixture).thenThrow(SENTINEL);

        final LlmResponse first = client.sendMessage("system", List.of(Message.user("list /tmp")), List.of(),
                LlmModel.builder().build());
        assertThatThrownBy(() -> client.sendMessage("system", nextTurn(first), List.of(), LlmModel.builder().build()))
                .hasRootCause(SENTINEL);

        final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService, atLeastOnce()).create(captor.capture());
        return AnthropicFixtures.bodyTreeOf(captor.getValue());
    }

    /** The assistant message of the replayed request, as an array of content blocks. */
    private static JsonNode assistantBlocks(JsonNode body) {
        for (JsonNode message : body.get("messages")) {
            if ("assistant".equals(message.get("role").asText())) {
                return message.get("content");
            }
        }
        throw new AssertionError("no assistant message in " + body);
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ── capture ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a thinking block in the response becomes a trace anchored to its tool use")
    void thinkingBlockIsCaptured() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class)))
                .thenReturn(responseWith(thinkingBlock(SIGNATURE, "check the path"), toolUseBlock("toolu_1", "Ls")));

        final LlmResponse response = new AnthropicLlmClient(config(), mockAnthropicClient).sendMessage("system",
                List.of(Message.user("hi")), List.of(), LlmModel.builder().build());

        assertThat(response.getReasoningTraces()).hasSize(1);
        final ReasoningTrace trace = response.getReasoningTraces().get(0);
        assertThat(trace.getProviderName()).isEqualTo("Anthropic");
        assertThat(trace.getToolUseId()).contains("toolu_1");
        assertThat(AnthropicFixtures.treeOf(trace.getPayload()).get("signature").asText()).isEqualTo(SIGNATURE);
    }

    @Test
    @DisplayName("a response with no thinking carries no traces, exactly as before this change")
    void noThinkingMeansNoTraces() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class)))
                .thenReturn(responseWith(textBlock("done"), toolUseBlock("toolu_1", "Ls")));

        final LlmResponse response = new AnthropicLlmClient(config(), mockAnthropicClient).sendMessage("system",
                List.of(Message.user("hi")), List.of(), LlmModel.builder().build());

        assertThat(response.getReasoningTraces()).isEmpty();
    }

    // ── replay ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the block comes back in front of its own tool use, signature unchanged")
    void blockIsReplayedBeforeItsToolUse() {
        final JsonNode blocks = assistantBlocks(replay(client(),
                responseWith(thinkingBlock(SIGNATURE, "check the path"), toolUseBlock("toolu_1", "Ls"))));

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("thinking");
        assertThat(blocks.get(0).get("signature").asText()).isEqualTo(SIGNATURE);
        assertThat(blocks.get(0).get("thinking").asText()).isEqualTo("check the path");
        assertThat(blocks.get(1).get("type").asText()).isEqualTo("tool_use");
        assertThat(blocks.get(1).get("id").asText()).isEqualTo("toolu_1");
    }

    @Test
    @DisplayName("two blocks each stay in front of their own call, not both in front of the first")
    void interleavedBlocksStayWithTheirOwnCalls() {
        final JsonNode blocks = assistantBlocks(replay(client(), responseWith(thinkingBlock("sig-1", "first"),
                toolUseBlock("toolu_1", "Ls"), thinkingBlock("sig-2", "second"), toolUseBlock("toolu_2", "Read"))));

        assertThat(blocks).hasSize(4);
        assertThat(blocks.get(0).get("signature").asText()).isEqualTo("sig-1");
        assertThat(blocks.get(1).get("id").asText()).isEqualTo("toolu_1");
        assertThat(blocks.get(2).get("signature").asText()).isEqualTo("sig-2");
        assertThat(blocks.get(3).get("id").asText()).isEqualTo("toolu_2");
    }

    @Test
    @DisplayName("an unanchored block leads the message, ahead of the text — the shape OpenAI's rule would break")
    void unanchoredBlockLeadsTheMessage() {
        final JsonNode blocks = assistantBlocks(replay(client(), responseWith(thinkingBlock(SIGNATURE, "plan"),
                textBlock("I will list it"), toolUseBlock("toolu_1", "Ls"))));

        // The provider's own order. Anchoring the thinking block to the call — OpenAI's rule — would produce
        // [text, thinking, tool_use], which no longer begins with a thinking block.
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("thinking");
        assertThat(blocks.get(1).get("type").asText()).isEqualTo("text");
        assertThat(blocks.get(2).get("type").asText()).isEqualTo("tool_use");
    }

    @Test
    @DisplayName("a redacted block survives alongside a signed one, keeping its own type and order")
    void redactedBlockSurvivesAlongsideASignedOne() {
        final JsonNode blocks = assistantBlocks(replay(client(), responseWith(thinkingBlock(SIGNATURE, "plan"), """
                {"type":"redacted_thinking","data":"EvgBCkYIARgC"}""", toolUseBlock("toolu_1", "Ls"))));

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("thinking");
        assertThat(blocks.get(1).get("type").asText()).isEqualTo("redacted_thinking");
        assertThat(blocks.get(1).get("data").asText()).isEqualTo("EvgBCkYIARgC");
        assertThat(blocks.get(2).get("type").asText()).isEqualTo("tool_use");
    }

    @Test
    @DisplayName("a thinking-only turn replays the block and does not invent an empty text block")
    void thinkingOnlyTurnEmitsNoEmptyText() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class)))
                .thenReturn(responseWith(thinkingBlock(SIGNATURE, "just thinking"))).thenThrow(SENTINEL);

        final AnthropicLlmClient client = new AnthropicLlmClient(config(), mockAnthropicClient);
        final LlmResponse first = client.sendMessage("system", List.of(Message.user("hi")), List.of(),
                LlmModel.builder().build());
        assertThatThrownBy(() -> client.sendMessage("system",
                List.of(Message.user("hi"), assistantTurn(first), Message.user("go on")), List.of(),
                LlmModel.builder().build())).hasRootCause(SENTINEL);

        final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService, atLeastOnce()).create(captor.capture());
        final JsonNode blocks = assistantBlocks(AnthropicFixtures.bodyTreeOf(captor.getValue()));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("thinking");
    }

    // ── the landmine ────────────────────────────────────────────────────────

    /** A client whose provider name is not the base class's, which is how the OpenAI path lost this feature once. */
    private static final class RenamedAnthropicClient extends AnthropicLlmClient {
        RenamedAnthropicClient(AnthropicConfig config, AnthropicClient client) {
            super(config, client);
        }

        @Override
        public String getProviderName() {
            return "MyAnthropic";
        }
    }

    @Test
    @DisplayName("a subclass that renames the provider still replays its own traces")
    void subclassProviderNameRoundTripsBothWays() {
        final JsonNode body = replay(client(new RenamedAnthropicClient(config(), mockAnthropicClient)),
                responseWith(thinkingBlock(SIGNATURE, "plan"), toolUseBlock("toolu_1", "Ls")));

        // Both halves read the same resolved name. Reading getProviderName() separately in the request factory and in
        // the response converter is how a trace gets tagged under one name and matched under another — the feature
        // then compiles, passes, and silently does nothing.
        final JsonNode blocks = assistantBlocks(body);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("thinking");
        assertThat(blocks.get(0).get("signature").asText()).isEqualTo(SIGNATURE);
        assertThat(warnings()).noneMatch(w -> w.contains("being dropped"));
    }

    // ── drops ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a trace authored by another provider is dropped, with exactly one warning")
    void foreignTraceIsDroppedAndReportedOnce() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);

        final AnthropicLlmClient client = new AnthropicLlmClient(config(), mockAnthropicClient);
        final Message assistant = Message.assistant("", List.of(ToolUse.of("toolu_1", "Ls", java.util.Map.of())))
                .withReasoningTraces(List.of(ReasoningTrace.builder().providerName("OpenAI")
                        .payload("{\"type\":\"reasoning\",\"id\":\"rs_1\"}").toolUseId("toolu_1").build()));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.sendMessage("system",
                    List.of(Message.user("hi"), assistant,
                            Message.toolUseResults(List.of(ToolUseResult.success("toolu_1", "ok")))),
                    List.of(), LlmModel.builder().build())).hasRootCause(SENTINEL);
        }

        final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService, atLeastOnce()).create(captor.capture());
        final JsonNode blocks = assistantBlocks(AnthropicFixtures.bodyTreeOf(captor.getValue()));

        // Feeding an OpenAI reasoning item to /v1/messages is a 400 at best, so the block must not be there.
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("tool_use");
        assertThat(warnings()).filteredOn(w -> w.contains("authored by OpenAI")).hasSize(1);
    }

    @Test
    @DisplayName("an unparseable payload costs the trace, not the turn")
    void unparseableTraceCostsOnlyTheTrace() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);

        final AnthropicLlmClient client = new AnthropicLlmClient(config(), mockAnthropicClient);
        final Message assistant = Message.assistant("here").withReasoningTraces(
                List.of(ReasoningTrace.builder().providerName("Anthropic").payload("{not json").build()));

        assertThatThrownBy(() -> client.sendMessage("system", List.of(Message.user("hi"), assistant), List.of(),
                LlmModel.builder().build())).hasRootCause(SENTINEL);

        final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService, atLeastOnce()).create(captor.capture());
        final JsonNode blocks = assistantBlocks(AnthropicFixtures.bodyTreeOf(captor.getValue()));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("text");
        assertThat(warnings()).anyMatch(w -> w.contains("could not be parsed by this build"));
    }

    @Test
    @DisplayName("a trace anchored to a tool use this message no longer carries is dropped and reported")
    void orphanedTraceIsReported() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);

        final AnthropicLlmClient client = new AnthropicLlmClient(config(), mockAnthropicClient);
        final Message assistant = Message.assistant("here")
                .withReasoningTraces(List.of(ReasoningTrace.builder().providerName("Anthropic")
                        .payload(thinkingBlock(SIGNATURE, "orphan")).toolUseId("toolu_gone").build()));

        assertThatThrownBy(() -> client.sendMessage("system", List.of(Message.user("hi"), assistant), List.of(),
                LlmModel.builder().build())).hasRootCause(SENTINEL);

        assertThat(warnings()).anyMatch(w -> w.contains("anchored to tool use toolu_gone"));
    }

    @Test
    @DisplayName("an orphaned foreign trace is reported under its own author, not under this client's name")
    void orphanedForeignTraceNamesItsRealAuthor() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);

        final AnthropicLlmClient client = new AnthropicLlmClient(config(), mockAnthropicClient);
        final Message assistant = Message.assistant("here")
                .withReasoningTraces(List.of(ReasoningTrace.builder().providerName("OpenAI")
                        .payload("{\"type\":\"reasoning\",\"id\":\"rs_1\"}").toolUseId("toolu_gone").build()));

        assertThatThrownBy(() -> client.sendMessage("system", List.of(Message.user("hi"), assistant), List.of(),
                LlmModel.builder().build())).hasRootCause(SENTINEL);

        // An orphan is reached by neither emit loop, so this is the only thing ever said about it. Naming this
        // client as the author would be the one drop message that states something false.
        assertThat(warnings())
                .anyMatch(w -> w.contains("A stored OpenAI reasoning trace is anchored to tool use " + "toolu_gone"));
        assertThat(warnings()).noneMatch(w -> w.contains("A stored Anthropic reasoning trace is anchored"));
    }

    @Test
    @DisplayName("replayThinkingBlocks(false) strips the block and leaves the rest of the message alone")
    void replayCanBeTurnedOff() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        when(mockMessageService.create(any(MessageCreateParams.class)))
                .thenReturn(responseWith(thinkingBlock(SIGNATURE, "plan"), toolUseBlock("toolu_1", "Ls")))
                .thenThrow(SENTINEL);

        final AnthropicLlmClient client = new AnthropicLlmClient(
                AnthropicConfig.builder().apiKey("k").replayThinkingBlocks(false).build(), mockAnthropicClient);
        final LlmResponse first = client.sendMessage("system", List.of(Message.user("hi")), List.of(),
                LlmModel.builder().build());

        // The capture half is unconditional, so the trace is still on the response — the switch governs only what is
        // sent back.
        assertThat(first.getReasoningTraces()).hasSize(1);

        assertThatThrownBy(() -> client.sendMessage("system", nextTurn(first), List.of(), LlmModel.builder().build()))
                .hasRootCause(SENTINEL);
        final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService, atLeastOnce()).create(captor.capture());
        final JsonNode blocks = assistantBlocks(AnthropicFixtures.bodyTreeOf(captor.getValue()));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("tool_use");
        // Stripped rather than dropped: the operator asked for this, so it is not a divergence.
        assertThat(warnings()).noneMatch(w -> w.contains("being dropped"));
    }
}
