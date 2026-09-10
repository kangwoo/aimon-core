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
 * {@code claude-sonnet-4-20250514} — {@code AnthropicConfig}'s own default model — is deliberately <em>not</em> in it
 * and stands for every model the table cannot answer for; {@code prod-thinker} is a gateway rename whose budgeted
 * dialect an operator has declared.
 *
 * <p>
 * <strong>Two of those three sentences were rewritten on 2026-09-10 and the history is the point.</strong> The
 * undescribed model used to be {@code claude-sonnet-4-5}, which the dialect census then measured and gave a row —
 * so this class's fixture silently became a <em>described</em> model, and its golden bodies would have started
 * describing a translated request. And the declared {@code prod-thinker} row used to be the only source of the
 * budgeted dialect anywhere; the built-in table now ships three prefixes that state it. The declared row stays,
 * because what it tests is that a declaration reaches the client, not that the dialect exists.
 *
 * <p>
 * {@code claude-sonnet-4-20250514} is undescribed <strong>by the table</strong>, not undialected in fact — the
 * vendor's own per-model table puts the Claude 4 generation in the extended-only group, so a future
 * documentation-derived row will move it. {@link #theUndescribedModelReallyIsUndescribed} is what makes that
 * survivable: the next row that describes this name fails with a sentence rather than with a golden-body diff.
 *
 * <p>
 * {@code claude-haiku-4-5} is a built-in budgeted prefix, used where the row itself — not a declaration — is what is
 * under test: {@link #autoOnABuiltInBudgetedRowClampsUnderTheConfigDefaultMaxTokens} pins the request {@code AUTO}
 * sends on it when {@code AnthropicConfig}'s default {@code maxTokens} reaches the wire.
 */
@DisplayName("AnthropicLlmClient - the thinking dialect, looked up per model")
@ExtendWith(MockitoExtension.class)
class AnthropicThinkingDialectTest {

    /** Aborts the SDK call once buildRequest has run, so no valid SDK Message has to be constructed. */
    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

    /** In the built-in table, from the vendor's per-model thinking matrix: adaptive only, rejects {@code enabled}. */
    private static final String ADAPTIVE_MODEL = "claude-opus-5";

    /** Deliberately absent from the built-in table: the fail-open path, and the byte-identical claim. */
    private static final String UNKNOWN_MODEL = "claude-sonnet-4-20250514";

    /** A gateway rename an operator has described as speaking the budgeted dialect. */
    private static final String BUDGETED_MODEL = "prod-thinker";

    /** In the built-in table since the 2026-09-10 census: measured to accept both request shapes. */
    private static final String EITHER_MODEL = "claude-opus-4-6";

    /**
     * In the built-in table since the same census, as a budgeted prefix — a real row rather than a declared one, so
     * removing or renaming it fails with a sentence. What {@code AUTO} sends on it at {@code AnthropicConfig}'s default
     * {@code maxTokens} is decided, not accidental: {@code docs/design/llm/thinking-reporting-and-dialect-records.md}
     * §16 (#83).
     */
    private static final String BUILT_IN_BUDGETED_MODEL = "claude-haiku-4-5";

    /**
     * What the request body was before the dialect existed, captured at the parent commit by sending the same
     * message with {@code thinkingMode(EXTENDED)} and {@code reasoningEffort(LOW)} against a model no row describes.
     * {@code LOW}'s 2048-token budget fits under the default {@code maxTokens} of 4096, so no clamp warning muddies
     * the "nothing is reported" half of the row.
     */
    private static final String TODAYS_EXTENDED_BODY = "{\"max_tokens\":4096,"
            + "\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],\"model\":\"claude-sonnet-4-20250514\","
            + "\"system\":\"You are helpful\",\"thinking\":{\"budget_tokens\":2048,\"type\":\"enabled\"}}";

    /** The same, with {@code thinkingMode(ADAPTIVE)} and {@code reasoningEffort(MEDIUM)}. */
    private static final String TODAYS_ADAPTIVE_BODY = "{\"max_tokens\":4096,"
            + "\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],\"model\":\"claude-sonnet-4-20250514\","
            + "\"output_config\":{\"effort\":\"medium\"},\"system\":\"You are helpful\","
            + "\"thinking\":{\"type\":\"adaptive\"}}";

    /** The same, with thinking off — which is also what {@code AUTO} sends when the table cannot answer. */
    private static final String TODAYS_THINKING_OFF_BODY = "{\"max_tokens\":4096,"
            + "\"messages\":[{\"content\":\"hi\",\"role\":\"user\"}],\"model\":\"claude-sonnet-4-20250514\","
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

    /** The built-in table plus one declared budgeted row, standing for a gateway rename an operator described. */
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

    @Test
    @DisplayName("AUTO on a built-in budgeted row at AnthropicConfig's 4096 maxTokens sends 4095 and warns once")
    void autoOnABuiltInBudgetedRowClampsUnderTheConfigDefaultMaxTokens() {
        // #83, and a decision rather than an oversight: why this request stays as it is -- one token left for the
        // answer -- is docs/design/llm/thinking-reporting-and-dialect-records.md section 16. Any headroom policy added
        // to AUTO turns this red; read that section before changing the assertions.
        //
        // No reasoningEffort and no maxTokens on the call, so the MEDIUM rung's 4096 meets AnthropicConfig's 4096:
        // the shape of an agent definition that sets no model.maxTokens. Not the CLI's bundled agents -- they set
        // 40000 and never clamp here.
        final JsonNode body = send(client(config(BUILT_IN_BUDGETED_MODEL, AnthropicThinkingMode.AUTO).build()),
                LlmModel.builder().build());

        assertThat(body.get("max_tokens").asInt()).isEqualTo(4096);
        // Before the dialect assertions, and described: without the row the name reads as UNKNOWN, AUTO sends no
        // parameter, and the next line would die on a NullPointerException instead of naming the row.
        assertThat(body.has("thinking")).as("the built-in BUDGETED row for %s", BUILT_IN_BUDGETED_MODEL).isTrue();
        assertThat(body.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(body.get("thinking").get("budget_tokens").asInt()).isEqualTo(4095);
        // The message, not the signature: thinkingBudgetClamped=4096->4095 is the dedup key and never reaches the log
        // line. singleElement() is what notices a second warning starting to fire on this path.
        assertThat(warnings()).singleElement().asString().contains("does not fit under maxTokens")
                .contains("Raise maxTokens");
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
    @DisplayName("the undescribed model really is undescribed, so the next row that names it fails here first")
    void theUndescribedModelReallyIsUndescribed() {
        // Three golden bodies in this class assert what a model outside the table gets, and they only mean that
        // while the name really is outside it. The 2026-09-10 census took the previous fixture name away by
        // measuring it; without this assertion the loss would have shown up as an unexplained golden-body diff in
        // whichever change did it next.
        assertThat(InMemoryModelCapabilityRegistry.withDefaults().capabilitiesOf(UNKNOWN_MODEL))
                .as("%s must stay outside the built-in table for the golden bodies above to mean anything",
                        UNKNOWN_MODEL)
                .isEmpty();
    }

    // ── Rows 9 and 10: EITHER → the named mode is honoured, and AUTO picks ───────────────────────────────────────

    @Test
    @DisplayName("EXTENDED against a model that takes either dialect is honoured, not translated, and unreported")
    void extendedAgainstAnEitherModelIsHonoured() {
        // The reason EITHER is a constant rather than an ADAPTIVE row. An ADAPTIVE row here would translate a
        // working, explicitly requested shape carrying the operator's exact intent -- and the translation warning
        // would assert "which rejects the other one with HTTP 400" about a model measured to accept it.
        final JsonNode body = send(client(config(EITHER_MODEL, AnthropicThinkingMode.EXTENDED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(body.get("thinking").get("budget_tokens").asInt()).isEqualTo(2048);
        assertThat(body.has("output_config")).isFalse();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("ADAPTIVE against a model that takes either dialect is honoured too")
    void adaptiveAgainstAnEitherModelIsHonoured() {
        final JsonNode body = send(client(config(EITHER_MODEL, AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("medium");
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("AUTO against a model that takes either dialect picks the adaptive one, and says nothing")
    void autoAgainstAnEitherModelPicksAdaptive() {
        // The one place a policy is applied rather than a fact read. Unreported because AUTO asked the table a
        // question and the table answered; the vendor's own deprecation notice is what argues for this direction,
        // and the SDK prints it at every call without this client paraphrasing it.
        final JsonNode body = send(client(config(EITHER_MODEL, AnthropicThinkingMode.AUTO).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("medium");
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("EITHER never reaches the wire: every mode sends a real shape or none at all")
    void eitherIsNeverTheDialectARequestSpeaks() {
        // The invariant ThinkingDialect's javadoc states, asserted where it can actually be observed. The trap it
        // guards is concrete: resolveAutoDialect's old `if (known != UNKNOWN) return known` would have handed
        // EITHER to the budgeted branch, and a request meant to be honoured as adaptive would have gone out
        // budgeted.
        for (AnthropicThinkingMode mode : AnthropicThinkingMode.values()) {
            logAppender.list.clear();
            final JsonNode body = send(client(config(EITHER_MODEL, mode).build()),
                    LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

            if (mode == AnthropicThinkingMode.OFF) {
                assertThat(body.has("thinking")).as("%s thinking", mode).isFalse();
                continue;
            }
            assertThat(body.get("thinking").get("type").asText()).as("%s thinking type", mode).isIn("adaptive",
                    "enabled");
            // output_config accompanies the adaptive shape alone, on every path -- a bare output_config.effort is a
            // measured 400 on two of the three models the census gave a budgeted row.
            assertThat(body.has("output_config")).as("%s output_config", mode)
                    .isEqualTo("adaptive".equals(body.get("thinking").get("type").asText()));
        }
    }

    // ── #68: no warning describes a request that is not sent ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a request that abandons thinking is explained once, not described three times")
    void anAbandonedRequestGetsOneWarningAndNoDescriptionOfItself() {
        // ADAPTIVE against a budgeted model with a display set and no room for a budget. Three findings are made
        // and then made false: the translation, the display-on-budgeted note, and (in other shapes) the clamp. The
        // NEGATIVE half is the test -- a positive-only assertion passed before this change too.
        final JsonNode body = send(
                client(config(BUDGETED_MODEL, AnthropicThinkingMode.ADAPTIVE).maxTokens(512)
                        .thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());

        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("output_config")).isFalse();
        assertThat(warnings()).singleElement().asString().contains("leaves no room");
        assertThat(warnings()).noneMatch(w -> w.contains("translated"))
                .noneMatch(w -> w.contains("is not given a `display` field"));
    }

    @Test
    @DisplayName("a dropped finding does not consume its signature, so the same warning still fires later")
    void aDroppedFindingDoesNotSpendItsSignature() {
        // The failure no single-send test can see: if dedup ran while findings were collected rather than when they
        // are emitted, the first send's abandoned translation would register the signature and silence it for the
        // life of the client. Two sends on ONE client, the second with room for a budget.
        final AnthropicLlmClient client = client(
                config(BUDGETED_MODEL, AnthropicThinkingMode.ADAPTIVE).maxTokens(512).build());
        send(client, LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());
        assertThat(warnings()).noneMatch(w -> w.contains("translated"));

        logAppender.list.clear();
        send(client, LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).maxTokens(16_000).build());

        assertThat(warnings()).anyMatch(w -> w.contains("translated"));
    }

    @Test
    @DisplayName("an effort dropped by a budget on a TRANSLATED adaptive request is warned about, not swallowed")
    void aBudgetOverridingAnEffortIsReportedOnTheTranslatedAdaptivePath() {
        // #68 item 2, and the case the issue says is untested. thinkingBudgetTokens is legal only under EXTENDED, so
        // it reaches an adaptive request only by translation -- and there intendedEffort discards the call's rung in
        // favour of the budget's nearest one. The warning that exists for exactly this used to live in the budgeted
        // branch alone, so this path said nothing.
        final JsonNode body = send(client(config(ADAPTIVE_MODEL, AnthropicThinkingMode.EXTENDED)
                .thinkingBudgetTokens(2000).maxTokens(16_000).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        // nearestEffort(2000) is LOW -- the budget won, and HIGH is what was discarded.
        assertThat(body.get("output_config").get("effort").asText()).isEqualTo("low");
        assertThat(warnings()).anyMatch(w -> w.contains("the explicit budget wins"))
                .anyMatch(w -> w.contains("translated"));
    }

    @Test
    @DisplayName("the same warning covers the budgeted path, so one finding is pinned on both dialects")
    void aBudgetOverridingAnEffortIsReportedOnTheBudgetedPathToo() {
        final JsonNode body = send(client(config(BUDGETED_MODEL, AnthropicThinkingMode.EXTENDED)
                .thinkingBudgetTokens(2000).maxTokens(16_000).build()),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build());

        assertThat(body.get("thinking").get("budget_tokens").asInt()).isEqualTo(2000);
        assertThat(warnings()).anyMatch(w -> w.contains("the explicit budget wins"));
    }

    @Test
    @DisplayName("thinkingDisplay with reasoningEffort NONE is deliberately silent — the pair agrees")
    void displayWithEffortNoneIsSilent() {
        // #68 item 3, decided rather than remembered. Both clauses of the reporting rule fail: the only remedy is
        // "stop asking for no reasoning", which reverses a value the operator explicitly wrote, and no false
        // conclusion is available because absent thinking text is exactly what NONE asked for.
        //
        // If a later change adds a fourth reporter for this pair, this test goes red -- read the rule in
        // docs/design/llm/thinking-reporting-and-dialect-records.md section 3.2 before deleting it.
        for (AnthropicThinkingMode mode : new AnthropicThinkingMode[]{AnthropicThinkingMode.ADAPTIVE,
                AnthropicThinkingMode.EXTENDED, AnthropicThinkingMode.AUTO}) {
            logAppender.list.clear();
            send(client(config(ADAPTIVE_MODEL, mode).thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build()),
                    LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build());

            assertThat(warnings()).as("%s warnings", mode).isEmpty();
        }
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
