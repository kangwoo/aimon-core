package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;

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
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
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

    /**
     * The undescribed model, and it is a fixture rather than an example.
     *
     * <p>
     * Every golden body below embeds this name, and the ADAPTIVE cases assert an untranslated request — both of
     * which are claims about a model the built-in capability table cannot answer for. It used to be
     * {@code claude-sonnet-4-5}; the 2026-09-10 dialect census measured that name and gave it a
     * {@link at.aimon.core.llm.capability.ThinkingDialect#BUDGETED} row, so the ADAPTIVE cases would have started
     * seeing a translation and a warning. {@code claude-sonnet-4-20250514} is {@code AnthropicConfig}'s own
     * {@code DEFAULT_MODEL}, which makes the golden bodies describe the shipped default rather than an arbitrary
     * name — undescribed by the table, not undialected in fact, and {@link #theUndescribedModelReallyIsUndescribed}
     * is what makes the next row that describes it fail with a sentence.
     */
    private static final String UNDESCRIBED_MODEL = "claude-sonnet-4-20250514";

    private static AnthropicConfig.Builder config() {
        return AnthropicConfig.builder().apiKey("test-key").model(UNDESCRIBED_MODEL);
    }

    @Test
    @DisplayName("the model these golden bodies are built on really is outside the capability table")
    void theUndescribedModelReallyIsUndescribed() {
        assertThat(InMemoryModelCapabilityRegistry.withDefaults().capabilitiesOf(UNDESCRIBED_MODEL))
                .as("%s must stay outside the built-in table for the golden bodies here to mean anything",
                        UNDESCRIBED_MODEL)
                .isEmpty();
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
    @DisplayName("thinkingMode OFF sends exactly what the caller asked for, and no manufactured temperature")
    void offSendsTodaysBodyUnchanged() {
        final String body = AnthropicFixtures
                .bodyOf(sendCapturingParams(client(config().build()), LlmModel.builder().build()));

        // A golden body, not a handful of key assertions. The claim being defended is that a thinking-off deployment
        // sends nothing it was not asked for, and only a whole-body comparison can defend it: naming the three keys
        // this change might have touched cannot notice a fourth that it did. Brittleness is the point — this test is
        // supposed to go red for any request-shape change at all, so that the change has to be looked at.
        //
        // This literal used to end in `"temperature":0.0`, and its removal is the whole of what stopping the config
        // from manufacturing a value does on the wire. Restoring a DEFAULT_TEMPERATURE in AnthropicConfig turns this
        // test red, which is the point of writing the body out in full.
        assertThat(body).isEqualTo("{\"max_tokens\":4096,\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],"
                + "\"model\":\"claude-sonnet-4-20250514\",\"system\":\"You are helpful\"}");
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("EXTENDED sends thinking.type=enabled with the clamped budget and no temperature key at all")
    void extendedSendsEnabledAndOmitsTemperature() {
        // A temperature is set on the call so that "omitted" is a claim about a value that existed. With nothing set
        // anywhere there is no longer anything to omit, and the assertion would pass vacuously.
        final JsonNode body = send(client(config().thinkingMode(AnthropicThinkingMode.EXTENDED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).temperature(0.7).build());

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
                LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).temperature(0.7).build());
        assertThat(extended.has("thinking")).isFalse();
        // The consequence stated in the design rather than hidden: with no thinking parameter the thinking-side
        // omission does not fire, so a configured temperature is still on the wire. What decides whether that is a
        // rejection is now the capability row — the fixture model is not in the table, so this is the fail-open path.
        assertThat(extended.get("temperature").asDouble()).isEqualTo(0.7);

        final JsonNode adaptive = send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build());
        assertThat(adaptive.has("thinking")).isFalse();
        assertThat(adaptive.has("output_config")).isFalse();
    }

    @Test
    @DisplayName("an effort configured on AnthropicConfig reaches the request when LlmModel sets none")
    void configLevelReasoningEffortIsUsed() {
        // The Anthropic half of the shared reasoningEffort key. Without the field this whole request would carry no
        // effort at all, so aimon.llm.reasoning-effort would mean something on one provider and nothing on the
        // other -- which is what would make a shared key a lie for half its users.
        final JsonNode fromConfig = send(client(
                config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).reasoningEffort(ReasoningEffort.MEDIUM).build()),
                LlmModel.builder().build());
        final JsonNode fromCall = send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

        assertThat(fromConfig.get("output_config").get("effort").asText()).isEqualTo("medium");
        // Stated as an equality of the two bodies rather than of the one key: the claim is that where the rung came
        // from does not change the request, and only comparing the whole body defends that.
        assertThat(fromConfig).isEqualTo(fromCall);
    }

    @Test
    @DisplayName("the LlmModel's effort beats the one configured on the client")
    void theRequestEffortBeatsTheConfiguredOne() {
        // The precedence of the shared key: LlmModel first, then the client config -- the same rule
        // OpenAiRequestParameters.requestedEffort has, written in the same shape on purpose. One configuration key
        // means one resolution rule, and a pair of tests that read alike is what notices if one provider's drifts.
        final JsonNode body = send(client(
                config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).reasoningEffort(ReasoningEffort.LOW).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build());

        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("high");
    }

    @Test
    @DisplayName("a config-level NONE suppresses thinking exactly as a call-level NONE does")
    void configLevelNoneSuppressesThinking() {
        // The assertion that fails if only one of the two read sites learned the precedence. resolveThinking decides
        // whether the request carries thinking at all; intendedEffort supplies the rung a translation warning names.
        // A precedence applied in one and not the other is a gate and a warning disagreeing about one request.
        final JsonNode body = send(client(
                config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).reasoningEffort(ReasoningEffort.NONE).build()),
                LlmModel.builder().build());

        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("output_config")).isFalse();
    }

    @Test
    @DisplayName("an effort under the default thinkingMode OFF reaches nothing, and is said once")
    void anInertEffortIsReportedOnce() {
        // "Configured and never read". reasoningEffort is settable deployment-wide from both surfaces and OFF is the
        // shipped default, so "make it think harder" is a reasonable thing to write and on its own does nothing
        // here. Not a refusal: the remedy is a second key, and failing the boot for a combination whose fix is
        // another setting turns valid configuration into a startup failure.
        final AnthropicLlmClient client = client(config().reasoningEffort(ReasoningEffort.HIGH).build());

        send(client, LlmModel.builder().build());
        send(client, LlmModel.builder().build());

        assertThat(warnings()).filteredOn(w -> w.contains("the effort reaches nothing")).hasSize(1);
        assertThat(warnings()).anyMatch(w -> w.contains("reasoningEffort HIGH") && w.contains("thinkingMode is OFF"));
    }

    @Test
    @DisplayName("a call-level effort under OFF is reported too — the rule does not depend on where the value came from")
    void anInertCallLevelEffortIsAlsoReported() {
        // The deliberate behaviour change: a deployment setting model.reasoningEffort in an agent definition today,
        // with the default mode, gets a new WARN line. Making the warning depend on the source would be an
        // asymmetry -- where the value came from does not change whether it reached anything.
        send(client(config().build()), LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build());

        assertThat(warnings()).anyMatch(w -> w.contains("the effort reaches nothing"));
    }

    @Test
    @DisplayName("reasoningEffort NONE under OFF is silent — that pair is consistent, not inert")
    void noneUnderOffIsSilent() {
        // Both mean "send no thinking parameter", so telling this operator to turn thinking on would be advice in
        // the wrong direction. The condition excluding NONE is the whole of this warning's correctness.
        send(client(config().reasoningEffort(ReasoningEffort.NONE).build()), LlmModel.builder().build());
        send(client(config().build()), LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build());

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("no effort anywhere under OFF is silent")
    void noEffortAnywhereUnderOffIsSilent() {
        send(client(config().build()), LlmModel.builder().build());

        assertThat(warnings()).isEmpty();
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
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).temperature(0.7).build());

        assertThat(body.has("thinking")).isFalse();
        // Sending a request the server is certain to reject would be worse than not asking for thinking, but doing it
        // silently would be worse still.
        assertThat(warnings()).anyMatch(w -> w.contains("leaves no room"));
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
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
        final LlmModel model = LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).temperature(0.7).build();

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
    @DisplayName("replayThinkingBlocks(false) while thinking is asked for names the cost, not a rejection")
    void replayOffWhileThinkingIsReported() {
        send(client(config().thinkingMode(AnthropicThinkingMode.EXTENDED).replayThinkingBlocks(false).build()),
                LlmModel.builder().build());

        // An earlier revision predicted a 400 here. AnthropicThinkingLiveTest measured the opposite, so what is left
        // to warn about is the cost: tokens spent on reasoning that is thrown away before the next turn. The message
        // says so explicitly, because a warning that names a failure the server does not produce teaches the operator
        // to distrust the next one.
        assertThat(warnings()).anyMatch(w -> w.contains("re-derives its reasoning every turn"))
                .anyMatch(w -> w.contains("is not rejected"));
    }

    @Test
    @DisplayName("replayThinkingBlocks(false) is reported under ADAPTIVE too — the cost does not pick a dialect")
    void replayOffUnderAdaptiveIsAlsoReported() {
        send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).replayThinkingBlocks(false).build()),
                LlmModel.builder().build());

        // The old gate was EXTENDED-only because the rule it was built on was EXTENDED-only. The reason is now a
        // cost, and an adaptive request discards exactly as much.
        assertThat(warnings()).anyMatch(w -> w.contains("re-derives its reasoning every turn"));
    }

    @Test
    @DisplayName("replayThinkingBlocks(false) is not reported when no thinking is requested")
    void replayOffWithoutThinkingIsSilent() {
        send(client(config().replayThinkingBlocks(false).build()), LlmModel.builder().build());

        // Nothing was asked for, so nothing is discarded and there is no cost to name.
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("the omitted temperature is described differently when the call set it and when the client did")
    void temperatureOmissionNamesWhoseValueItWas() {
        send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().temperature(0.7).build());
        assertThat(warnings()).anyMatch(w -> w.startsWith("temperature 0.7 is incompatible"));

        logAppender.list.clear();
        send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).temperature(0.4).build()),
                LlmModel.builder().build());

        // The value came from the client config, not from this call. Naming it "temperature 0.4" would send an
        // operator looking for it on a request that never carried one, so the sentence says where it came from.
        assertThat(warnings()).anyMatch(w -> w.startsWith("No temperature was set on this call"))
                .noneMatch(w -> w.startsWith("temperature 0.4 is incompatible"));
    }

    // ---- thinkingDisplay: the ask, and where it does not go ----

    @Test
    @DisplayName("thinkingDisplay unset leaves the adaptive body byte-identical to what it was")
    void displayUnsetLeavesTheAdaptiveBodyUnchanged() {
        // Criterion 6's Anthropic half, asserted on the serialised params rather than on a getter. A whole-body
        // comparison for the same reason offSendsTodaysBodyUnchanged uses one: naming the key this change added
        // cannot notice a second one it did not mean to add.
        final String body = AnthropicFixtures.bodyOf(sendCapturingParams(
                client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()), LlmModel.builder().build()));

        assertThat(body).isEqualTo("{\"max_tokens\":4096,\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],"
                + "\"model\":\"claude-sonnet-4-20250514\",\"system\":\"You are helpful\",\"thinking\":{\"type\":\"adaptive\"}}");
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("thinkingDisplay under ADAPTIVE writes display with the configured wire value")
    void displayUnderAdaptiveReachesTheRequest() {
        final JsonNode body = send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE)
                .thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build()), LlmModel.builder().build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.get("thinking").get("display").asText()).isEqualTo("summarized");
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("every constant spells a value the server actually accepts")
    void everyConstantSpellsAnAcceptedWireValue() {
        // The regression this pins is a shipped one: `UPDATES("updates")` rode an opted-in adaptive request until a
        // live probe returned 400 with `thinking.adaptive.display: Input should be 'summarized', 'omitted'` (a bogus
        // control 400s identically, so the field is validated rather than ignored). Nothing else catches a wrong
        // spelling -- the SDK does not model `display`, so it goes out through putAdditionalProperty untyped.
        final Set<String> acceptedByTheServer = Set.of("summarized", "omitted");

        assertThat(AnthropicThinkingDisplay.values()).allSatisfy(display -> assertThat(acceptedByTheServer)
                .as("`%s` is sent as `%s`, which the server rejects", display, display.wireValue())
                .contains(display.wireValue()));
    }

    @Test
    @DisplayName("omitted is accepted by the server and deliberately absent from the enum")
    void theEnumDoesNotCarryOmitted() {
        // Not an oversight and not a value waiting to be added. `omitted` is the server's default, so writing it is
        // behaviourally identical to leaving the key unset -- and because presence of the key is what opens the
        // forwarding gate, `thinkingDisplay: omitted` would mean "open the reasoning channel, and ask the server to
        // put nothing in it". AnthropicThinkingDisplay's javadoc carries the reasoning.
        assertThat(AnthropicThinkingDisplay.values()).extracting(AnthropicThinkingDisplay::wireValue)
                .containsExactly("summarized");
    }

    @Test
    @DisplayName("thinkingDisplay under EXTENDED leaves the body untouched and says so once")
    void displayUnderExtendedIsInertOnTheWireAndReported() {
        // The budgeted shape is not given a display sibling (unmeasured, and the deltas already arrive there), so
        // the word reaches nothing while the forwarding still works. Silence would let working output read as proof
        // the field went out.
        final JsonNode body = send(
                client(config().thinkingMode(AnthropicThinkingMode.EXTENDED)
                        .thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(body.get("thinking").has("display")).isFalse();
        assertThat(warnings()).anyMatch(w -> w.contains("is not given a `display` field"));
    }

    @Test
    @DisplayName("thinkingDisplay under the shipped default OFF reaches nothing, and says so once")
    void displayUnderOffIsInertAndReported() {
        final String body = AnthropicFixtures.bodyOf(
                sendCapturingParams(client(config().thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build()),
                        LlmModel.builder().build()));

        assertThat(body).doesNotContain("thinking");
        assertThat(warnings()).anyMatch(w -> w.contains("no display reaches the server"));
    }

    @Test
    @DisplayName("the two inert-display warnings are each said once, however many requests are sent")
    void inertDisplayIsReportedOncePerProcess() {
        // A property of the configuration, not of the traffic: the condition is constant for the life of the client,
        // so reportDivergence's once-per-signature rule is the right one and reportRecurringDivergence's 1/10/100
        // cadence would repeat a sentence nothing changed about.
        final AnthropicLlmClient client = client(config().thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build());
        send(client, LlmModel.builder().build());
        send(client, LlmModel.builder().build());
        send(client, LlmModel.builder().build());

        assertThat(warnings()).filteredOn(w -> w.contains("no display reaches the server")).hasSize(1);
    }

    @Test
    @DisplayName("nobody set a temperature, so nothing is omitted and nothing is said")
    void noTemperatureAnywhereIsSilent() {
        // This used to be the second half of the test above, and it used to produce a warning naming the config's
        // manufactured 0.0. There is no manufactured value now, so there is nothing to omit and nothing to report —
        // silence by construction rather than by a special case.
        final JsonNode body = send(client(config().thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().build());

        assertThat(body.has("temperature")).isFalse();
        assertThat(warnings()).noneMatch(w -> w.contains("temperature"));
    }
}
