package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ThinkingDialect;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The preserved-thinking block binding on the current generation ({@code claude-sonnet-5-5}, {@code claude-opus-5-5},
 * {@code claude-haiku-5-5}): when it goes on the wire, what it rides on, the beta header, the findings it must not
 * silence, the drop report, and the 400 hint for the models it does not cover.
 *
 * <p>
 * Every body here is built against a mocked SDK and none is sent. The live counterpart, which forces the server's
 * check, is {@code AnthropicThinkingBindingLiveTest}.
 */
@DisplayName("AnthropicLlmClient - preserved-thinking block binding")
@ExtendWith(MockitoExtension.class)
class AnthropicThinkingBlockBindingTest {

    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

    private static final String SONNET_5_5 = "claude-sonnet-5-5";
    private static final String HAIKU_5_5 = "claude-haiku-5-5";

    private static final String CARRIER = "{\"type\":\"adaptive\",\"block_binding\":"
            + "{\"prefix_mismatch_behavior\":\"drop_block\"}}";

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

    private static AnthropicConfig.Builder config(String model, AnthropicThinkingMode mode) {
        return AnthropicConfig.builder().apiKey("test-key").model(model).thinkingMode(mode);
    }

    private AnthropicLlmClient client(AnthropicConfig config) {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        lenient().when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        lenient().when(mockMessageService.createStreaming(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        return new AnthropicLlmClient(config, mockAnthropicClient);
    }

    private MessageCreateParams sendCapturingParams(AnthropicLlmClient client, LlmModel model,
            List<ToolDefinition> tools) {
        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")), tools, model))
                .hasRootCause(SENTINEL);
        final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService, atLeastOnce()).create(captor.capture());
        return captor.getValue();
    }

    private MessageCreateParams sendCapturingParams(AnthropicLlmClient client, LlmModel model) {
        return sendCapturingParams(client, model, List.of());
    }

    private JsonNode send(AnthropicLlmClient client, LlmModel model) {
        return AnthropicFixtures.bodyTreeOf(sendCapturingParams(client, model));
    }

    private static List<String> betaHeader(MessageCreateParams params) {
        return params._headers().values(AnthropicLlmClient.ANTHROPIC_BETA);
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Nested
    @DisplayName("the binding matrix on the three current-generation rows")
    class Matrix {

        @ParameterizedTest
        @ValueSource(strings = {SONNET_5_5, "claude-opus-5-5", HAIKU_5_5})
        @DisplayName("every mode carries drop_block and the beta header while replay is on")
        void everyModeCarriesTheBinding(String model) {
            for (AnthropicThinkingMode mode : AnthropicThinkingMode.values()) {
                final AnthropicConfig.Builder builder = config(model, mode);
                if (mode == AnthropicThinkingMode.EXTENDED) {
                    builder.thinkingBudgetTokens(4000);
                }
                final MessageCreateParams params = sendCapturingParams(client(builder.build()),
                        LlmModel.builder().build());
                final JsonNode thinking = AnthropicFixtures.bodyTreeOf(params).get("thinking");

                assertThat(thinking).as("%s %s thinking", model, mode).isNotNull();
                assertThat(thinking.get("type").asText()).as("%s %s type", model, mode).isEqualTo("adaptive");
                assertThat(thinking.at("/block_binding/prefix_mismatch_behavior").asText()).as("%s %s", model, mode)
                        .isEqualTo("drop_block");
                assertThat(thinking.has("budget_tokens")).as("%s %s budget_tokens", model, mode).isFalse();
                assertThat(betaHeader(params)).as("%s %s beta", model, mode)
                        .containsExactly(AnthropicThinkingResolution.BLOCK_BINDING_BETA);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {SONNET_5_5, "claude-opus-5-5", HAIKU_5_5})
        @DisplayName("with replayThinkingBlocks(false) no mode carries a binding or the header")
        void replayOffCarriesNoBinding(String model) {
            for (AnthropicThinkingMode mode : AnthropicThinkingMode.values()) {
                final AnthropicConfig.Builder builder = config(model, mode).replayThinkingBlocks(false);
                if (mode == AnthropicThinkingMode.EXTENDED) {
                    builder.thinkingBudgetTokens(4000);
                }
                final MessageCreateParams params = sendCapturingParams(client(builder.build()),
                        LlmModel.builder().build());
                final JsonNode body = AnthropicFixtures.bodyTreeOf(params);

                assertThat(body.at("/thinking/block_binding").isMissingNode()).as("%s %s", model, mode).isTrue();
                assertThat(betaHeader(params)).as("%s %s beta", model, mode).isEmpty();
            }
        }

        @Test
        @DisplayName("OFF with replay on sends exactly the carrier: no display, no output_config")
        void offSendsTheCarrierAlone() {
            final MessageCreateParams params = sendCapturingParams(
                    client(config(HAIKU_5_5, AnthropicThinkingMode.OFF).build()), LlmModel.builder().build());
            final JsonNode body = AnthropicFixtures.bodyTreeOf(params);

            assertThat(body.get("thinking")).isEqualTo(AnthropicFixtures.treeOf(CARRIER));
            assertThat(body.has("output_config")).isFalse();
            assertThat(betaHeader(params)).containsExactly("thinking-binding-controls-2026-08-01");
            assertThat(warnings()).isEmpty();
        }

        @Test
        @DisplayName("OFF with replay off sends today's body: no thinking key, no output_config, no header")
        void offWithReplayOffIsTodaysBody() {
            final MessageCreateParams params = sendCapturingParams(
                    client(config(HAIKU_5_5, AnthropicThinkingMode.OFF).replayThinkingBlocks(false).build()),
                    LlmModel.builder().build());
            final JsonNode body = AnthropicFixtures.bodyTreeOf(params);

            assertThat(body.has("thinking")).isFalse();
            assertThat(body.has("output_config")).isFalse();
            assertThat(betaHeader(params)).isEmpty();
        }

        @Test
        @DisplayName("reasoningEffort NONE also sends the carrier, with no effort")
        void effortNoneSendsTheCarrier() {
            final JsonNode body = send(client(config(SONNET_5_5, AnthropicThinkingMode.AUTO).build()),
                    LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build());

            assertThat(body.get("thinking")).isEqualTo(AnthropicFixtures.treeOf(CARRIER));
            assertThat(body.has("output_config")).isFalse();
        }

        @Test
        @DisplayName("ADAPTIVE with a display puts display and block_binding in one object")
        void displayAndBindingSitTogether() {
            final JsonNode body = send(
                    client(config(SONNET_5_5, AnthropicThinkingMode.ADAPTIVE)
                            .thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build()),
                    LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());

            assertThat(body.get("thinking")).isEqualTo(AnthropicFixtures.treeOf("{\"type\":\"adaptive\","
                    + "\"display\":\"summarized\",\"block_binding\":{\"prefix_mismatch_behavior\":\"drop_block\"}}"));
            assertThat(body.at("/output_config/effort").asText()).isEqualTo("low");
        }

        @Test
        @DisplayName("on claude-sonnet-5-5 no mode ever sends disabled or between_tools")
        void neverDisabledNorBetweenTools() {
            for (AnthropicThinkingMode mode : AnthropicThinkingMode.values()) {
                for (boolean replay : new boolean[]{true, false}) {
                    final AnthropicConfig.Builder builder = config(SONNET_5_5, mode).replayThinkingBlocks(replay);
                    if (mode == AnthropicThinkingMode.EXTENDED) {
                        builder.thinkingBudgetTokens(4000);
                    }
                    final JsonNode body = send(client(builder.build()),
                            LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());
                    final String type = body.at("/thinking/type").asText("");
                    assertThat(type).as("%s replay=%s", mode, replay).isNotIn("disabled", "between_tools");
                }
            }
        }
    }

    @Nested
    @DisplayName("the migration fixes on claude-haiku-5-5")
    class HaikuFiveFive {

        @Test
        @DisplayName("EXTENDED with a budget is translated to adaptive + effort, never budget_tokens, and reported")
        void extendedIsTranslated() {
            final JsonNode body = send(
                    client(config(HAIKU_5_5, AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(4000).build()),
                    LlmModel.builder().build());

            assertThat(body.at("/thinking/type").asText()).isEqualTo("adaptive");
            assertThat(body.at("/thinking/budget_tokens").isMissingNode()).isTrue();
            assertThat(body.at("/output_config/effort").isMissingNode()).isFalse();
            assertThat(warnings()).singleElement().asString().contains("translated").contains(HAIKU_5_5)
                    .contains("4000");
        }

        @Test
        @DisplayName("AUTO sends adaptive and no 'dialect unknown' warning")
        void autoIsAdaptive() {
            final JsonNode body = send(client(config(HAIKU_5_5, AnthropicThinkingMode.AUTO).build()),
                    LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

            assertThat(body.at("/thinking/type").asText()).isEqualTo("adaptive");
            assertThat(body.at("/output_config/effort").asText()).isEqualTo("medium");
            assertThat(warnings()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the findings a carrier must not silence")
    class FindingsSurviveTheCarrier {

        @Test
        @DisplayName("OFF with an effort and a display on claude-sonnet-5-5: both inert findings, once each")
        void offFindingsSurvive() {
            final AnthropicLlmClient client = client(config(SONNET_5_5, AnthropicThinkingMode.OFF)
                    .thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build());
            final LlmModel model = LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build();

            final JsonNode body = send(client, model);
            send(client, model);

            assertThat(body.get("thinking")).isEqualTo(AnthropicFixtures.treeOf(CARRIER));
            assertThat(warnings()).filteredOn(w -> w.startsWith("reasoningEffort MEDIUM is configured")).hasSize(1);
            assertThat(warnings()).filteredOn(
                    w -> w.startsWith("thinkingDisplay ") && w.contains("is configured but thinkingMode is OFF"))
                    .hasSize(1);
            assertThat(warnings()).hasSize(2);
        }

        @Test
        @DisplayName("ADAPTIVE on the same row asks for thinking, and neither OFF finding fires")
        void adaptiveRequestsThinking() {
            final AnthropicConfig config = config(SONNET_5_5, AnthropicThinkingMode.ADAPTIVE)
                    .thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build();
            final AnthropicThinkingResolution resolution = new AnthropicThinkingResolver(config).resolve(
                    LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build(), 16000,
                    InMemoryModelCapabilityRegistry.withDefaults().resolve(SONNET_5_5), SONNET_5_5);

            assertThat(resolution.requestsThinking()).isTrue();
            assertThat(resolution.findingsToReport()).isEmpty();
        }

        @Test
        @DisplayName("the resolver marks a carrier as not asking for thinking")
        void carrierDoesNotRequestThinking() {
            final AnthropicConfig config = config(SONNET_5_5, AnthropicThinkingMode.OFF).build();
            final AnthropicThinkingResolution resolution = new AnthropicThinkingResolver(config).resolve(
                    LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build(), 16000,
                    InMemoryModelCapabilityRegistry.withDefaults().resolve(SONNET_5_5), SONNET_5_5);

            assertThat(resolution.parameter()).isPresent();
            assertThat(resolution.requestsThinking()).isFalse();
            assertThat(resolution.betaHeader()).contains(AnthropicThinkingResolution.BLOCK_BINDING_BETA);
            assertThat(resolution.findingsToReport()).extracting(AnthropicThinkingResolution.Finding::signature)
                    .containsExactly("reasoningEffortWithThinkingOff=MEDIUM");
        }
    }

    @Nested
    @DisplayName("names the binding is not for")
    class OptIn {

        @ParameterizedTest
        @ValueSource(strings = {"claude-sonnet-5", "claude-opus-5", "claude-fable-5-1", "claude-haiku-4-5",
                "claude-sonnet-4-6", "gateway-claude-deployment"})
        @DisplayName("OFF and AUTO bodies carry no binding and no header")
        void unflaggedNamesAreUnchanged(String model) {
            for (AnthropicThinkingMode mode : new AnthropicThinkingMode[]{AnthropicThinkingMode.OFF,
                    AnthropicThinkingMode.AUTO}) {
                final MessageCreateParams params = sendCapturingParams(client(config(model, mode).build()),
                        LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());
                final JsonNode body = AnthropicFixtures.bodyTreeOf(params);

                assertThat(body.at("/thinking/block_binding").isMissingNode()).as("%s %s", model, mode).isTrue();
                if (mode == AnthropicThinkingMode.OFF) {
                    assertThat(body.has("thinking")).as("%s OFF", model).isFalse();
                }
                assertThat(betaHeader(params)).as("%s %s beta", model, mode).isEmpty();
            }
        }

        @Test
        @DisplayName("a declared row with the flag and a budgeted dialect gets no binding, reported once")
        void declaredBudgetedRowGetsNoBinding() {
            final AnthropicConfig config = config("prod-thinker", AnthropicThinkingMode.EXTENDED)
                    .modelCapabilityRegistry(InMemoryModelCapabilityRegistry.builderWithDefaults()
                            .register("prod-thinker",
                                    ModelCapabilities.builder().thinkingDialect(ThinkingDialect.BUDGETED)
                                            .supportsThinkingBlockBinding(true).build())
                            .build())
                    .build();
            final AnthropicLlmClient client = client(config);
            final LlmModel model = LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build();

            final MessageCreateParams params = sendCapturingParams(client, model);
            sendCapturingParams(client, model);

            final JsonNode body = AnthropicFixtures.bodyTreeOf(params);
            assertThat(body.at("/thinking/type").asText()).isEqualTo("enabled");
            assertThat(body.at("/thinking/block_binding").isMissingNode()).isTrue();
            assertThat(betaHeader(params)).isEmpty();
            assertThat(warnings()).filteredOn(w -> w.contains("supportsThinkingBlockBinding")).singleElement()
                    .asString().contains("prod-thinker").contains("BUDGETED");
        }
    }

    @Nested
    @DisplayName("the beta header")
    class BetaHeader {

        @Test
        @DisplayName("is comma-joined with a value the request already carries")
        void joinsAnExistingValue() {
            final MessageCreateParams existing = MessageCreateParams.builder().model(SONNET_5_5).maxTokens(1)
                    .addUserMessage("hi").putAdditionalHeader("anthropic-beta", "other-beta-2026-01-01").build();

            final MessageCreateParams joined = AnthropicLlmClient.withBetaHeader(existing,
                    AnthropicThinkingResolution.BLOCK_BINDING_BETA);

            assertThat(betaHeader(joined))
                    .containsExactly("other-beta-2026-01-01," + AnthropicThinkingResolution.BLOCK_BINDING_BETA);
            assertThat(betaHeader(
                    AnthropicLlmClient.withBetaHeader(joined, AnthropicThinkingResolution.BLOCK_BINDING_BETA)))
                    .hasSize(1);
        }

        @Test
        @DisplayName("rides the streaming path too, including the cancellable rebuild that adds the abort key")
        void streamingCarriesTheHeader() {
            final AnthropicLlmClient client = client(config(SONNET_5_5, AnthropicThinkingMode.OFF).build());
            final LlmCancellation cancellable = new AnthropicCancellationTransportTest.RecordingCancellation();

            for (LlmCancellation cancellation : List.of(LlmCancellation.none(), cancellable)) {
                assertThatThrownBy(() -> client.sendMessageStreaming(SystemPromptParts.empty(),
                        List.of(Message.user("hi")), List.of(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                        LlmStreamingOptions.defaults(), LlmStreamSink.discarding(), cancellation))
                        .hasRootCause(SENTINEL);
            }
            final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
            verify(mockMessageService, atLeastOnce()).createStreaming(captor.capture());

            assertThat(captor.getAllValues()).hasSize(2).allSatisfy(params -> assertThat(betaHeader(params))
                    .containsExactly(AnthropicThinkingResolution.BLOCK_BINDING_BETA));
        }
    }

    @Nested
    @DisplayName("dropped blocks reported from input_transformations")
    class DropReporting {

        private static final String RESPONSE = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "stop_reason":"end_turn","content":[{"type":"text","text":"ok"}],
                 "usage":{"input_tokens":10,"output_tokens":2}%s}""";

        private LlmResponse respond(String extra) {
            lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
            lenient().when(mockMessageService.create(any(MessageCreateParams.class)))
                    .thenReturn(AnthropicFixtures.message(RESPONSE.formatted(extra)));
            final AnthropicLlmClient client = new AnthropicLlmClient(
                    config(SONNET_5_5, AnthropicThinkingMode.OFF).build(), mockAnthropicClient);
            return client.sendMessage("s", List.of(Message.user("hi")), List.of(), LlmModel.builder().build());
        }

        @Test
        @DisplayName("a blocking response listing drops yields one WARN per reason naming the model and the count")
        void blockingDropsAreReported() {
            final LlmResponse response = respond(",\"input_transformations\":["
                    + "{\"type\":\"thinking_block_dropped\",\"reason\":\"prefix_mismatch\"},"
                    + "{\"type\":\"thinking_block_dropped\",\"reason\":\"prefix_mismatch\"},"
                    + "{\"type\":\"thinking_block_dropped\",\"reason\":\"organization_binding_mismatch\"}]");

            assertThat(response.getTextContent()).isEqualTo("ok");
            assertThat(warnings()).filteredOn(w -> w.contains("prefix_mismatch")).singleElement().asString()
                    .startsWith("2 thinking block(s) replayed to claude-sonnet-5-5 were dropped")
                    .contains("prompt prefix changed");
            assertThat(warnings()).filteredOn(w -> w.contains("organization_binding_mismatch")).singleElement()
                    .asString().startsWith("1 thinking block(s)").doesNotContain("prompt prefix changed");
        }

        @Test
        @DisplayName("an absent or malformed value says nothing, and the request still succeeds")
        void absentOrMalformedIsSilent() {
            for (String extra : new String[]{"", ",\"input_transformations\":[]",
                    ",\"input_transformations\":\"not-an-array\"", ",\"input_transformations\":{\"reason\":1}"}) {
                logAppender.list.clear();
                assertThat(respond(extra).getTextContent()).as(extra).isEqualTo("ok");
                assertThat(warnings()).as(extra).noneMatch(w -> w.contains("dropped"));
            }
        }

        @Test
        @DisplayName("a streamed response reports drops from message_start or message_delta, once per stream")
        void streamingDropsAreReported() {
            final List<String> reported = new java.util.ArrayList<>();
            final AnthropicStreamingMapper mapper = new AnthropicStreamingMapper(LlmStreamSink.discarding(),
                    new at.aimon.core.llm.streaming.ChunkAggregator(), "Anthropic",
                    (signature, message, args) -> reported.add(signature), false);

            mapper.consume(java.util.stream.Stream.of(AnthropicFixtures.event("""
                    {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",
                     "model":"claude-haiku-5-5","content":[],"usage":{"input_tokens":11,"output_tokens":0},
                     "input_transformations":[{"reason":"prefix_mismatch"}]}}"""), AnthropicFixtures.event("""
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1},
                     "input_transformations":[{"reason":"prefix_mismatch"}]}"""),
                    AnthropicFixtures.event("{\"type\":\"message_stop\"}")));

            assertThat(reported).containsExactly("thinkingBlocksDropped@claude-haiku-5-5@prefix_mismatch");
        }

        @Test
        @DisplayName("a stream whose only list is on message_delta is reported too")
        void streamingDeltaOnly() {
            final List<String> reported = new java.util.ArrayList<>();
            final AnthropicStreamingMapper mapper = new AnthropicStreamingMapper(LlmStreamSink.discarding(),
                    new at.aimon.core.llm.streaming.ChunkAggregator(), "Anthropic",
                    (signature, message, args) -> reported.add(signature), false);

            mapper.consume(java.util.stream.Stream.of(AnthropicFixtures.event("""
                    {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",
                     "model":"claude-haiku-5-5","content":[],"usage":{"input_tokens":11,"output_tokens":0}}}"""),
                    AnthropicFixtures.event("""
                            {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1},
                             "input_transformations":[{"reason":"organization_binding_mismatch"}]}"""),
                    AnthropicFixtures.event("{\"type\":\"message_stop\"}")));

            assertThat(reported)
                    .containsExactly("thinkingBlocksDropped@claude-haiku-5-5@organization_binding_mismatch");
        }
    }

    @Nested
    @DisplayName("the preserved-thinking 400 hint")
    class BindingRejectedHint {

        @Test
        @DisplayName("without baseUrl, a 'bound to a different conversation' 400 names both remedies once")
        void hintWithoutBaseUrl() {
            lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
            lenient().when(mockMessageService.create(any(MessageCreateParams.class)))
                    .thenThrow(BadRequestException.builder().headers(Headers.builder().build())
                            .body(JsonValue.from(Map.of("type", "error", "error",
                                    Map.of("type", "invalid_request_error", "message",
                                            "Invalid `signature` in `thinking` block. The block is bound to a "
                                                    + "different conversation."))))
                            .build());
            final AnthropicLlmClient client = new AnthropicLlmClient(
                    config("claude-fable-5-1", AnthropicThinkingMode.OFF).build(), mockAnthropicClient);

            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> client.sendMessage("s", List.of(Message.user("hi")), List.of(),
                        LlmModel.builder().build())).isInstanceOf(LlmInvalidRequestException.class)
                        .hasMessageContaining("bound to a different conversation");
            }

            assertThat(warnings()).singleElement().asString().contains("supportsThinkingBlockBinding")
                    .contains("replayThinkingBlocks").contains("aimon.llm.anthropic.replay-thinking-blocks");
        }

        @Test
        @DisplayName("another 400 says nothing about thinking blocks")
        void otherInvalidRequestIsSilent() {
            lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
            lenient().when(mockMessageService.create(any(MessageCreateParams.class)))
                    .thenThrow(BadRequestException.builder().headers(Headers.builder().build()).body(JsonValue.from(Map
                            .of("type", "error", "error", Map.of("type", "invalid_request_error", "message", "bad"))))
                            .build());
            final AnthropicLlmClient client = new AnthropicLlmClient(
                    config("claude-fable-5-1", AnthropicThinkingMode.OFF).build(), mockAnthropicClient);

            assertThatThrownBy(
                    () -> client.sendMessage("s", List.of(Message.user("hi")), List.of(), LlmModel.builder().build()))
                    .isInstanceOf(LlmInvalidRequestException.class);
            assertThat(warnings()).isEmpty();
        }
    }

    @Nested
    @DisplayName("regression guards for the migration")
    class Guards {

        @Test
        @DisplayName("a request with tools never forces tool_choice (any/tool is a 400 on the 5.5 models)")
        void noForcedToolChoice() {
            final ToolDefinition tool = ToolDefinition.of("Ls", "list a directory",
                    Map.of("type", "object", "properties", Map.of()));
            final JsonNode body = AnthropicFixtures
                    .bodyTreeOf(sendCapturingParams(client(config(SONNET_5_5, AnthropicThinkingMode.AUTO).build()),
                            LlmModel.builder().build(), List.of(tool)));

            assertThat(body.get("tools")).isNotNull();
            if (body.has("tool_choice")) {
                assertThat(body.at("/tool_choice/type").asText()).isEqualTo("auto");
            }
        }

        @Test
        @DisplayName("a deprecated model is warned about once across two requests, and the request still goes out")
        void deprecatedModelWarnsOnce() {
            final AnthropicLlmClient client = client(config("claude-sonnet-4-5", AnthropicThinkingMode.OFF).build());

            final JsonNode body = send(client, LlmModel.builder().build());
            send(client, LlmModel.builder().build());

            assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-4-5");
            assertThat(warnings()).singleElement().asString().contains("2026-11-30").contains("claude-sonnet-5-5");
        }

        @Test
        @DisplayName("a per-request model override is checked too")
        void overrideIsChecked() {
            final AnthropicLlmClient client = client(config(SONNET_5_5, AnthropicThinkingMode.OFF).build());

            send(client, LlmModel.builder().name("claude-sonnet-4-5-20250929").build());

            assertThat(warnings()).singleElement().asString().contains("claude-sonnet-4-5-20250929")
                    .contains("deprecated");
        }
    }
}
