package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

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
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Asserts what the {@code thinking} setting actually puts on the wire.
 *
 * <p>
 * Every assertion here is against the serialised request body, never against {@code params.temperature().isEmpty()}.
 * The SDK's {@code getOptional} collapses "missing" and "explicit null", so an implementation that left
 * {@code "temperature": null} in the body — and earned the exact 400 this design removes — would pass an
 * {@code isEmpty()} assertion. {@code _body()} is the surface that cannot lie.
 */
@DisplayName("AnthropicLlmClient - the thinking request parameter and its sampling consequences")
@ExtendWith(MockitoExtension.class)
class AnthropicThinkingRequestTest {

    /** Aborts the SDK call once buildRequest has run, so no valid SDK Message has to be constructed. */
    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

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

    private AnthropicLlmClient client(AnthropicConfig config) {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        lenient().when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        return new AnthropicLlmClient(config, mockAnthropicClient);
    }

    private static AnthropicConfig.Builder config() {
        return AnthropicConfig.builder().apiKey("test-key").model("claude-sonnet-4-5");
    }

    private JsonNode send(AnthropicLlmClient client, LlmModel model) {
        return AnthropicFixtures.bodyTreeOf(sendCapturingParams(client, model));
    }

    private MessageCreateParams sendCapturingParams(AnthropicLlmClient client, LlmModel model) {
        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")), List.of(), model))
                .hasRootCause(SENTINEL);
        // atLeastOnce plus the last captured value, because a test that sends twice shares one mock.
        final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService, atLeastOnce()).create(captor.capture());
        return captor.getValue();
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("thinkingMode OFF sends a body byte-identical to the pre-thinking one")
    void offSendsTodaysBodyUnchanged() {
        final String body = AnthropicFixtures
                .bodyOf(sendCapturingParams(client(config().build()), LlmModel.builder().build()));

        // A golden body, not a handful of key assertions. The claim being defended is that a thinking-off deployment
        // does not change by one character, and only a whole-body comparison can defend it: naming the three keys
        // this change might have touched cannot notice a fourth that it did. Brittleness is the point — this test is
        // supposed to go red for any request-shape change at all, so that the change has to be looked at.
        assertThat(body).isEqualTo("{\"max_tokens\":4096,\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],"
                + "\"model\":\"claude-sonnet-4-5\",\"system\":\"You are helpful\",\"temperature\":0.0}");
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("EXTENDED sends thinking.type=enabled with the clamped budget and no temperature key at all")
    void extendedSendsEnabledAndOmitsTemperature() {
        final JsonNode body = send(client(config().thinkingMode(AnthropicThinkingMode.EXTENDED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("enabled");
        // The default maxTokens of 4096 clamps HIGH's 16000 down to maxTokens - 1.
        assertThat(body.get("thinking").get("budget_tokens").asInt()).isEqualTo(4095);
        // Not "temperature is null" — the key is absent. A null would be rejected the same way a value is.
        assertThat(body.has("temperature")).isFalse();
        assertThat(warnings()).anyMatch(w -> w.contains("incompatible with Anthropic thinking"))
                .anyMatch(w -> w.contains("does not fit under maxTokens"));
    }

    @Test
    @DisplayName("ADAPTIVE sends thinking.type=adaptive plus output_config.effort")
    void adaptiveSendsAdaptiveAndEffort() {
        final JsonNode body = send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.get("thinking").has("budget_tokens")).isFalse();
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("medium");
        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    @DisplayName("ADAPTIVE without a reasoning effort sends the thinking key and no output_config")
    void adaptiveWithoutEffortSendsNoOutputConfig() {
        final JsonNode body = send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.has("output_config")).isFalse();
    }

    @Test
    @DisplayName("reasoningEffort NONE suppresses the thinking parameter in both modes")
    void noneSuppressesThinkingInBothModes() {
        final JsonNode extended = send(client(config().thinkingMode(AnthropicThinkingMode.EXTENDED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build());
        assertThat(extended.has("thinking")).isFalse();
        // The consequence stated in the design rather than hidden: with no thinking parameter the sampling omission
        // does not fire either, so temperature is still on the wire — and on an adaptive-capable model that is itself
        // a rejection. Naming it here is what keeps it a known cost rather than a surprise.
        assertThat(extended.has("temperature")).isTrue();

        final JsonNode adaptive = send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build());
        assertThat(adaptive.has("thinking")).isFalse();
        assertThat(adaptive.has("output_config")).isFalse();
    }

    @Test
    @DisplayName("an explicit config budget is sent as given when it fits")
    void explicitBudgetIsSentAsGiven() {
        final JsonNode body = send(client(config().thinkingMode(AnthropicThinkingMode.EXTENDED)
                .thinkingBudgetTokens(6000).maxTokens(16_000).build()), LlmModel.builder().build());

        assertThat(body.get("thinking").get("budget_tokens").asInt()).isEqualTo(6000);
    }

    @Test
    @DisplayName("an explicit config budget beats the call's effort, and the ignored effort is reported")
    void explicitBudgetBeatsEffortAndSaysSo() {
        final JsonNode body = send(client(config().thinkingMode(AnthropicThinkingMode.EXTENDED)
                .thinkingBudgetTokens(2000).maxTokens(16_000).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build());

        assertThat(body.get("thinking").get("budget_tokens").asInt()).isEqualTo(2000);
        assertThat(warnings()).anyMatch(w -> w.contains("the explicit budget wins"));
    }

    @Test
    @DisplayName("no legal budget fits: the thinking parameter is omitted and the operator is told")
    void impossibleBudgetOmitsThinkingLoudly() {
        final JsonNode body = send(client(config().thinkingMode(AnthropicThinkingMode.EXTENDED).maxTokens(512).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build());

        assertThat(body.has("thinking")).isFalse();
        // Sending a request the server is certain to reject would be worse than not asking for thinking, but doing it
        // silently would be worse still.
        assertThat(warnings()).anyMatch(w -> w.contains("leaves no room"));
        assertThat(body.has("temperature")).isTrue();
    }

    @Test
    @DisplayName("top_p outside [0.95, 1.0] is omitted alongside thinking; inside it is sent")
    void topPIsWindowedWhenThinkingIsOn() {
        final AnthropicConfig thinking = config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build();

        final JsonNode narrow = send(client(thinking), LlmModel.builder().topP(0.5).build());
        assertThat(narrow.has("top_p")).isFalse();
        assertThat(warnings()).anyMatch(w -> w.contains("outside the [0.95, 1.0] window"));

        logAppender.list.clear();
        final JsonNode inWindow = send(client(thinking), LlmModel.builder().topP(0.97).build());
        assertThat(inWindow.get("top_p").asDouble()).isEqualTo(0.97);
        assertThat(warnings()).noneMatch(w -> w.contains("top_p"));
    }

    @Test
    @DisplayName("top_p is untouched when thinking is off, whatever its value")
    void topPIsUntouchedWithoutThinking() {
        final JsonNode body = send(client(config().build()), LlmModel.builder().topP(0.5).build());

        assertThat(body.get("top_p").asDouble()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("each divergence is reported exactly once across two sends")
    void divergenceIsReportedOncePerSignature() {
        final AnthropicLlmClient client = client(config().thinkingMode(AnthropicThinkingMode.EXTENDED).build());
        final LlmModel model = LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build();

        assertThatThrownBy(() -> client.sendMessage("s", List.of(Message.user("hi")), List.of(), model))
                .hasRootCause(SENTINEL);
        assertThatThrownBy(() -> client.sendMessage("s", List.of(Message.user("hi")), List.of(), model))
                .hasRootCause(SENTINEL);

        assertThat(warnings()).filteredOn(w -> w.contains("incompatible with Anthropic thinking")).hasSize(1);
        assertThat(warnings()).filteredOn(w -> w.contains("does not fit under maxTokens")).hasSize(1);
    }

    @Test
    @DisplayName("replayThinkingBlocks does not change the request when there is nothing to replay")
    void replaySwitchIsInertWithoutTraces() {
        final JsonNode replaying = send(client(config().replayThinkingBlocks(true).build()),
                LlmModel.builder().build());
        final JsonNode notReplaying = send(client(config().replayThinkingBlocks(false).build()),
                LlmModel.builder().build());

        assertThat(replaying).isEqualTo(notReplaying);
    }

    @Test
    @DisplayName("replayThinkingBlocks(false) under EXTENDED is reported as the incompatible pair it is")
    void replayOffUnderExtendedThinkingIsReported() {
        send(client(config().thinkingMode(AnthropicThinkingMode.EXTENDED).replayThinkingBlocks(false).build()),
                LlmModel.builder().build());

        // Extended mode requires the final assistant turn to begin with a thinking block, and this configuration
        // strips exactly that block. The remedy named is OFF, not replay(true): the switch exists because replay can
        // itself fail, so telling the operator to undo it would be telling them to walk back into the other failure.
        assertThat(warnings()).anyMatch(w -> w.contains("documented as incompatible"))
                .anyMatch(w -> w.contains("thinkingMode(OFF)"));
    }

    @Test
    @DisplayName("replayThinkingBlocks(false) is not reported when no thinking is requested")
    void replayOffWithoutThinkingIsSilent() {
        send(client(config().replayThinkingBlocks(false).build()), LlmModel.builder().build());

        // OFF asks for no thinking, so neither vendor rule binds and there is nothing to warn about. This is the
        // configuration the EXTENDED warning points at.
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("replayThinkingBlocks(false) is not reported under ADAPTIVE, which drops the requirement")
    void replayOffUnderAdaptiveIsSilent() {
        send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).replayThinkingBlocks(false).build()),
                LlmModel.builder().build());

        assertThat(warnings()).noneMatch(w -> w.contains("documented as incompatible"));
    }

    @Test
    @DisplayName("the omitted temperature is described differently when the call set it and when it did not")
    void temperatureOmissionNamesWhoseValueItWas() {
        send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().temperature(0.7).build());
        assertThat(warnings()).anyMatch(w -> w.startsWith("temperature 0.7 is incompatible"));

        logAppender.list.clear();
        send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()), LlmModel.builder().build());

        // Nobody set a temperature here: 0.0 is AnthropicConfig's default. Saying "temperature 0.0 is incompatible"
        // reads as a complaint about a configuration the operator never wrote, which is the one thing a divergence
        // warning must not do — it is supposed to tell them something they can act on.
        assertThat(warnings()).anyMatch(w -> w.startsWith("No temperature was set on this call"))
                .noneMatch(w -> w.startsWith("temperature 0.0 is incompatible"));
    }
}
