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
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import at.aimon.core.llm.capability.ThinkingDialect;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The mode × dialect decision table of {@code docs/design/llm/reasoning-model-enablement.md} §3.3, one test per row.
 *
 * <p>
 * The table's claim is that the dialect a request speaks is a per-model <em>fact</em> rather than a per-operator
 * guess, and that acting on it costs nothing when the fact is missing. Both halves are asserted here: the rows where
 * the table has an answer assert the shape that reaches the wire and whether the substitution was reported, and the
 * {@link ThinkingDialect#UNKNOWN} rows assert the whole serialised body against a literal — <strong>the same literal
 * the code produced before this feature existed</strong>, captured by running the same request on the commit before
 * it. That is the additive claim and it is the one worth pinning: a model no row describes must keep its request byte
 * for byte.
 *
 * <p>
 * Assertions are against {@code _body()} rather than against {@code params.thinking().isEmpty()}, for the reason
 * {@link AnthropicThinkingRequestTest} states: the SDK's {@code getOptional} collapses "missing" and "explicit null",
 * so only the serialised body can tell them apart.
 *
 * <p>
 * The model names are load-bearing. {@code claude-opus-5} is in the built-in table as adaptive-only;
 * {@code claude-sonnet-4-5} is deliberately <em>not</em> in it and stands for every model the table cannot answer
 * for; {@code prod-thinker} is a gateway rename whose budgeted dialect an operator has declared, which is also the
 * only way a {@link ThinkingDialect#BUDGETED} row exists at all — the built-in table has none, because the Claude
 * models that speak that dialect accept the sampling parameters and so need no row.
 */
@DisplayName("AnthropicLlmClient - the thinking dialect, looked up per model")
@ExtendWith(MockitoExtension.class)
class AnthropicThinkingDialectTest {

    /** Aborts the SDK call once buildRequest has run, so no valid SDK Message has to be constructed. */
    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

    /** In the built-in table, from the vendor's per-model thinking matrix: adaptive only, rejects {@code enabled}. */
    private static final String ADAPTIVE_MODEL = "claude-opus-5";

    /** Deliberately absent from the built-in table: the fail-open path, and the byte-identical claim. */
    private static final String UNKNOWN_MODEL = "claude-sonnet-4-5";

    /** A gateway rename an operator has described as speaking the budgeted dialect. */
    private static final String BUDGETED_MODEL = "prod-thinker";

    /**
     * What the request body was before the dialect existed, captured at the parent commit by sending the same
     * message with {@code thinkingMode(EXTENDED)} and {@code reasoningEffort(LOW)} against a model no row describes.
     * {@code LOW}'s 2048-token budget fits under the default {@code maxTokens} of 4096, so no clamp warning muddies
     * the "nothing is reported" half of the row.
     */
    private static final String TODAYS_EXTENDED_BODY = "{\"max_tokens\":4096,"
            + "\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],\"model\":\"claude-sonnet-4-5\","
            + "\"system\":\"You are helpful\",\"thinking\":{\"budget_tokens\":2048,\"type\":\"enabled\"}}";

    /** The same, with {@code thinkingMode(ADAPTIVE)} and {@code reasoningEffort(MEDIUM)}. */
    private static final String TODAYS_ADAPTIVE_BODY = "{\"max_tokens\":4096,"
            + "\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],\"model\":\"claude-sonnet-4-5\","
            + "\"output_config\":{\"effort\":\"medium\"},\"system\":\"You are helpful\","
            + "\"thinking\":{\"type\":\"adaptive\"}}";

    /** The same, with thinking off — which is also what {@code AUTO} sends when the table cannot answer. */
    private static final String TODAYS_THINKING_OFF_BODY = "{\"max_tokens\":4096,"
            + "\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],\"model\":\"claude-sonnet-4-5\","
            + "\"system\":\"You are helpful\"}";

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

    /** The built-in table plus one declared budgeted row, which is the only source of that dialect in-tree. */
    private static ModelCapabilityRegistry registry() {
        return InMemoryModelCapabilityRegistry.builderWithDefaults()
                .register(BUDGETED_MODEL, ModelCapabilities.builder().thinkingDialect(ThinkingDialect.BUDGETED).build())
                .build();
    }

    private static AnthropicConfig.Builder config(String model, AnthropicThinkingMode mode) {
        return AnthropicConfig.builder().apiKey("test-key").model(model).thinkingMode(mode)
                .modelCapabilityRegistry(registry());
    }

    private AnthropicLlmClient client(AnthropicConfig config) {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        lenient().when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        return new AnthropicLlmClient(config, mockAnthropicClient);
    }

    private MessageCreateParams sendCapturingParams(AnthropicLlmClient client, LlmModel model) {
        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")), List.of(), model))
                .hasRootCause(SENTINEL);
        final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService, atLeastOnce()).create(captor.capture());
        return captor.getValue();
    }

    private JsonNode send(AnthropicLlmClient client, LlmModel model) {
        return AnthropicFixtures.bodyTreeOf(sendCapturingParams(client, model));
    }

    private String sendForBody(AnthropicLlmClient client, LlmModel model) {
        return AnthropicFixtures.bodyOf(sendCapturingParams(client, model));
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ── Row 1: OFF × any dialect → nothing, and nothing said ABOUT THE DIALECT ───────────────────────────────────

    @Test
    @DisplayName("OFF sends no thinking parameter whatever the model speaks, and says nothing about the dialect")
    void offIgnoresTheDialectEntirely() {
        // Silence about the dialect is correct here: the operator turned thinking off, so there is no question for
        // the table to have answered and nothing was substituted. The dialect is not even looked up --
        // resolveThinking returns first.
        //
        // What is NOT silent, since reasoningEffort became settable deployment-wide, is the effort itself: it was
        // configured and reaches nothing, which is a different report about a different thing. The two are separated
        // here rather than by dropping the assertion, because an empty-warnings assertion is what would notice a
        // dialect warning leaking into a mode that never asks the table anything.
        for (String model : new String[]{ADAPTIVE_MODEL, BUDGETED_MODEL, UNKNOWN_MODEL}) {
            logAppender.list.clear();
            final JsonNode body = send(client(config(model, AnthropicThinkingMode.OFF).build()),
                    LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

            assertThat(body.has("thinking")).as("%s thinking", model).isFalse();
            assertThat(body.has("output_config")).as("%s output_config", model).isFalse();
            assertThat(warnings()).as("%s dialect warnings", model)
                    .noneMatch(w -> w.contains("dialect") || w.contains("thinkingDialect"));
            // hasSize(1) rather than allMatch alone: allMatch passes vacuously on an empty list, so on its own it
            // would stop proving that the effort warning fires at all -- and the whole reason this assertion was
            // split from the one above is that the effort warning is now expected here.
            assertThat(warnings()).as("%s warnings", model).hasSize(1)
                    .allMatch(w -> w.contains("the effort reaches nothing"));
        }
    }

    // ── Row 2: any mode × UNKNOWN → byte-identical to today, and nothing said ────────────────────────────────────

    @Test
    @DisplayName("EXTENDED against a model no row describes sends the body it sent before this feature existed")
    void extendedAgainstAnUnknownDialectIsByteIdentical() {
        final String body = sendForBody(client(config(UNKNOWN_MODEL, AnthropicThinkingMode.EXTENDED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());

        // A whole-body comparison rather than a handful of keys, for the reason offSendsTodaysBodyUnchanged gives:
        // naming the keys this change might have touched cannot notice one that it did. This literal was captured by
        // running the identical request on the parent commit.
        assertThat(body).isEqualTo(TODAYS_EXTENDED_BODY);
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("ADAPTIVE against a model no row describes sends the body it sent before this feature existed")
    void adaptiveAgainstAnUnknownDialectIsByteIdentical() {
        final String body = sendForBody(client(config(UNKNOWN_MODEL, AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

        assertThat(body).isEqualTo(TODAYS_ADAPTIVE_BODY);
        assertThat(warnings()).isEmpty();
    }

    // ── Rows 3 and 4: the mode and the dialect agree → unchanged, and nothing said ───────────────────────────────

    @Test
    @DisplayName("EXTENDED against a budgeted model sends the budgeted shape, unchanged and unreported")
    void extendedAgainstABudgetedModelIsUnchanged() {
        final JsonNode body = send(client(config(BUDGETED_MODEL, AnthropicThinkingMode.EXTENDED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(body.get("thinking").get("budget_tokens").asInt()).isEqualTo(2048);
        assertThat(body.has("output_config")).isFalse();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("ADAPTIVE against an adaptive model sends the adaptive shape, unchanged and unreported")
    void adaptiveAgainstAnAdaptiveModelIsUnchanged() {
        final JsonNode body = send(client(config(ADAPTIVE_MODEL, AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("medium");
        assertThat(warnings()).isEmpty();
    }

    // ── Row 5: EXTENDED × ADAPTIVE → translated to adaptive, reported ────────────────────────────────────────────

    @Test
    @DisplayName("EXTENDED against an adaptive-only model is translated rather than sent as a certain 400")
    void extendedAgainstAnAdaptiveModelIsTranslated() {
        final JsonNode body = send(client(config(ADAPTIVE_MODEL, AnthropicThinkingMode.EXTENDED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

        // The dialect the model speaks reaches the wire, carrying the same rung the operator's intent named. Sending
        // thinking.type=enabled here is the HTTP 400 this whole lookup exists to prevent.
        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.get("thinking").has("budget_tokens")).isFalse();
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("medium");
        assertThat(warnings()).singleElement().asString().contains("EXTENDED").contains("ADAPTIVE")
                .contains(ADAPTIVE_MODEL).contains("HTTP 400").contains("translated");
    }

    @Test
    @DisplayName("an explicit budget translated onto the adaptive dialect names both the tokens and the rung")
    void anExplicitBudgetTranslatesToTheNearestRung() {
        // The lossiest corner in the change: a token count has no counterpart in a dialect where the model manages
        // its own budget. 2000 is nearest LOW (2048), which is what the request carries -- and the warning has to
        // name both numbers, because a silent substitution here is the failure mode this design set out to remove.
        final JsonNode body = send(
                client(config(ADAPTIVE_MODEL, AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(2000).build()),
                LlmModel.builder().build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("low");
        assertThat(warnings()).singleElement().asString().contains("2000").contains("LOW").contains("no counterpart");
    }

    // ── Row 6: ADAPTIVE × BUDGETED → translated to the budget dialect, reported ──────────────────────────────────

    @Test
    @DisplayName("ADAPTIVE against a budgeted-only model is translated the other way, with the budget from the rung")
    void adaptiveAgainstABudgetedModelIsTranslated() {
        final JsonNode body = send(client(config(BUDGETED_MODEL, AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(body.get("thinking").get("budget_tokens").asInt()).isEqualTo(2048);
        // output_config accompanies the adaptive dialect only, and this request no longer speaks it.
        assertThat(body.has("output_config")).isFalse();
        assertThat(warnings()).singleElement().asString().contains("ADAPTIVE").contains("BUDGETED")
                .contains(BUDGETED_MODEL).contains("translated");
    }

    // ── Row 7: AUTO × a known dialect → the model's own, and nothing said ────────────────────────────────────────

    @Test
    @DisplayName("AUTO sends the dialect the model speaks, either way, and says nothing — that is what it asked for")
    void autoSendsTheModelsOwnDialect() {
        final JsonNode adaptive = send(client(config(ADAPTIVE_MODEL, AnthropicThinkingMode.AUTO).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());
        assertThat(adaptive.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(adaptive.get("output_config").get("effort").asText()).isEqualTo("medium");
        assertThat(warnings()).isEmpty();

        final JsonNode budgeted = send(client(config(BUDGETED_MODEL, AnthropicThinkingMode.AUTO).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());
        assertThat(budgeted.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(budgeted.get("thinking").get("budget_tokens").asInt()).isEqualTo(2048);
        assertThat(budgeted.has("output_config")).isFalse();
        assertThat(warnings()).isEmpty();
    }

    // ── Row 8: AUTO × UNKNOWN → nothing, and this one is reported ────────────────────────────────────────────────

    @Test
    @DisplayName("AUTO against a model no row describes sends what OFF would, and is the one silence that is loud")
    void autoAgainstAnUnknownDialectSendsNothingAndSaysSo() {
        final String body = sendForBody(client(config(UNKNOWN_MODEL, AnthropicThinkingMode.AUTO).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

        assertThat(body).isEqualTo(TODAYS_THINKING_OFF_BODY);
        // Reported, unlike every other case that sends nothing, because AUTO is the mode that asked the table a
        // question rather than answering it -- and the wording has to separate the two things an operator could read
        // into the silence, since several current models think by default whatever the request says.
        assertThat(warnings()).singleElement().asString().contains("AUTO").contains(UNKNOWN_MODEL)
                .contains("no row describes").contains("still thinks").contains("Register the deployment's real name");
    }

    // ── The reporting rules the table's "once per signature" column relies on ────────────────────────────────────

    @Test
    @DisplayName("a translation is reported once across two sends, and twice across two model names")
    void translationIsReportedOncePerSignatureAndModel() {
        // Same register, same rule, same reason as the sampling divergences: buildRequest runs on every ReAct
        // iteration, so a setting made once would otherwise warn for the lifetime of the process. The model name is
        // in the signature because LlmModel can override it, which makes the same mode against two models two pieces
        // of news rather than one repeated.
        final AnthropicLlmClient client = client(config(ADAPTIVE_MODEL, AnthropicThinkingMode.EXTENDED).build());
        final LlmModel model = LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build();

        send(client, model);
        send(client, model);
        assertThat(warnings()).hasSize(1);

        send(client, LlmModel.builder().name("claude-sonnet-5").reasoningEffort(ReasoningEffort.MEDIUM).build());
        assertThat(warnings()).hasSize(2);
        assertThat(warnings()).anyMatch(w -> w.contains(ADAPTIVE_MODEL)).anyMatch(w -> w.contains("claude-sonnet-5"));
    }

    @Test
    @DisplayName("reasoningEffort NONE outranks the dialect look-up, under AUTO as much as under a named mode")
    void noneIsAnsweredBeforeTheTableIsAsked() {
        // NONE sends no thinking parameter, so it cannot earn the 400 the look-up prevents, and a warning about a
        // table that could not answer would be a complaint about a question nobody needed answering. It also keeps
        // AUTO honest: a caller who said NONE has already said what they want.
        final JsonNode auto = send(client(config(UNKNOWN_MODEL, AnthropicThinkingMode.AUTO).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build());
        assertThat(auto.has("thinking")).isFalse();
        assertThat(warnings()).isEmpty();

        final JsonNode translated = send(client(config(ADAPTIVE_MODEL, AnthropicThinkingMode.EXTENDED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build());
        assertThat(translated.has("thinking")).isFalse();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a declared row wins over the built-in one, so an operator can correct a wrong dialect")
    void aDeclaredDialectBeatsTheBuiltInRow() {
        // Failure mode 1 of the design: a row that states the wrong dialect costs the operator the 400 they get
        // today, and the remedy is the same one line that closes the sampling gap. UNKNOWN is a usable value here --
        // it means "do not act on any row for this name", which restores the pre-feature request exactly.
        final ModelCapabilityRegistry corrected = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .register(ADAPTIVE_MODEL, ModelCapabilities.builder().thinkingDialect(ThinkingDialect.UNKNOWN).build())
                .build();

        final JsonNode body = send(
                client(AnthropicConfig.builder().apiKey("test-key").model(ADAPTIVE_MODEL)
                        .thinkingMode(AnthropicThinkingMode.EXTENDED).modelCapabilityRegistry(corrected).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a registry that fails degrades to an unknown dialect rather than to a guessed one")
    void aFailingRegistryLeavesTheDialectUnknown() {
        // The fail-open rule applied to the SPI itself, which matters more on this axis than on the sampling one: a
        // guess here is a 400 half the time, so the degradation has to be "the table said nothing".
        final ModelCapabilityRegistry throwing = modelName -> {
            throw new IllegalStateException("registry is down");
        };
        final String body = sendForBody(
                client(AnthropicConfig.builder().apiKey("test-key").model(UNKNOWN_MODEL)
                        .thinkingMode(AnthropicThinkingMode.EXTENDED).modelCapabilityRegistry(throwing).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());

        assertThat(body).isEqualTo(TODAYS_EXTENDED_BODY);
        assertThat(warnings()).anyMatch(w -> w.contains("registry is down"));
    }

    @Test
    @DisplayName("the dialect follows a per-call model override, not the client's configured name")
    void theDialectFollowsTheNameThatGoesOnTheWire() {
        // The name whose capabilities are looked up is the name that reaches the wire, which is what makes one client
        // usable for a deployment running more than one Claude model -- the case AUTO exists for.
        final JsonNode body = send(client(config(UNKNOWN_MODEL, AnthropicThinkingMode.AUTO).build()),
                LlmModel.builder().name(ADAPTIVE_MODEL).reasoningEffort(ReasoningEffort.MEDIUM).build());

        assertThat(body.get("model").asText()).isEqualTo(ADAPTIVE_MODEL);
        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(warnings()).isEmpty();
    }
}
