package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.responses.ResponseCreateParams;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llms.openai.exception.ToolConversionException;

/**
 * Tests the {@link ResponseCreateParams} counterpart to the Chat request builder.
 *
 * <p>
 * <strong>Absence is asserted on the raw {@code _xxx()} accessors or on the serialised body, never on
 * {@code xxx().isEmpty()}.</strong> The trap is identical on this endpoint: {@code temperature(Optional.empty())}
 * produces {@code "temperature": null} while {@code temperature()} reads {@code Optional.empty} — so an
 * implementation that put a JSON null on the wire, and earned the same HTTP 400 this whole branch exists to remove,
 * would still pass an {@code isEmpty()} assertion.
 */
@DisplayName("OpenAIResponsesRequestFactory")
class OpenAIResponsesRequestFactoryTest {

    /**
     * A gpt-5-family reasoning model and its row. The name was {@code gpt-5.6-terra} until that name got a row of
     * its own for its measured ladder — which would have kept every assertion below green while testing a different
     * row from the one they are about.
     */
    private static final String GPT5_NAME = "gpt-5-mini";

    private static final ModelCapabilities GPT5 = InMemoryModelCapabilityRegistry.withDefaults().resolve(GPT5_NAME);

    /** The one name whose ladder has a gap in it: {@code none} accepted, {@code minimal} rejected (measured). */
    private static final String TERRA_NAME = "gpt-5.6-terra";

    private static final ModelCapabilities TERRA = InMemoryModelCapabilityRegistry.withDefaults().resolve(TERRA_NAME);

    private static final OpenAIDivergenceReporter SILENT = (signature, message, args) -> {
    };

    private static OpenAIResponsesRequestFactory factoryFor(OpenAIConfig config, OpenAIDivergenceReporter reporter) {
        return new OpenAIResponsesRequestFactory(new OpenAIResponsesMessageConverter(), config, reporter);
    }

    private ResponseCreateParams build(OpenAIConfig config, LlmModel model, List<ToolDefinition> tools) {
        return build(config, model, tools, GPT5, SILENT);
    }

    private ResponseCreateParams build(OpenAIConfig config, LlmModel model, List<ToolDefinition> tools,
            ModelCapabilities capabilities, OpenAIDivergenceReporter reporter) {
        return build(config, model, tools, capabilities, reporter, GPT5_NAME);
    }

    private ResponseCreateParams build(OpenAIConfig config, LlmModel model, List<ToolDefinition> tools,
            ModelCapabilities capabilities, OpenAIDivergenceReporter reporter, String modelName) {
        return factoryFor(config, reporter).build("You are helpful", List.of(Message.user("hi")), tools, model,
                capabilities, modelName, "OpenAI");
    }

    private static OpenAIConfig config() {
        return OpenAIConfig.builder().apiKey("k").model(GPT5_NAME).build();
    }

    @Test
    @DisplayName("sampling parameters are MISSING, not JSON null, even when configured")
    void samplingIsSuppressedAsMissing() {
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model(GPT5_NAME).temperature(0.7).topP(0.5)
                .build();

        final ResponseCreateParams params = build(config, LlmModel.builder().temperature(0.3).build(), List.of());

        assertThat(params._temperature()).isInstanceOf(JsonMissing.class);
        assertThat(params._topP()).isInstanceOf(JsonMissing.class);
        assertThat(ResponsesFixtures.bodyOf(params)).doesNotContain("temperature").doesNotContain("top_p");
    }

    @Test
    @DisplayName("the system prompt becomes instructions, and the request carries store=false plus the include")
    void requestShape() {
        final ResponseCreateParams params = build(config(), LlmModel.builder().maxTokens(1234).build(), List.of());
        final JsonNode body = ResponsesFixtures.bodyTreeOf(params);

        assertThat(body.get("instructions").asText()).isEqualTo("You are helpful");
        assertThat(body.get("max_output_tokens").asInt()).isEqualTo(1234);
        // store:false is a decision, not a default -- server-side state would be a second source of truth no
        // SessionRecord knows about, in a system that resumes sessions on other nodes.
        assertThat(body.get("store").asBoolean()).isFalse();
        // ...and the include is asked for explicitly because store:false is the case the SDK's javadoc singles out.
        assertThat(body.get("include").get(0).asText()).isEqualTo("reasoning.encrypted_content");
        assertThat(body.get("model").asText()).isEqualTo(GPT5_NAME);
    }

    @Test
    @DisplayName("a configured reasoning effort reaches the wire WITH tools present -- the clamp is a Chat rule")
    void effortIsNotClampedWhenToolsArePresent() {
        // The single assertion that fails if phase 1's NONE clamp is copied onto this endpoint. Copying it would
        // silently re-disable reasoning and undo the entire round while every other test stayed green.
        final ResponseCreateParams params = build(config(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of(aTool()));

        assertThat(ResponsesFixtures.bodyTreeOf(params).get("reasoning").get("effort").asText()).isEqualTo("high");
    }

    @Test
    @DisplayName("a rung off the model's ladder is omitted, not sent -- NONE is off the gpt-5 family's")
    void effortOffTheLadderIsOmitted() {
        // The endpoint gpt-5.x is routed to by default, so this is where a configured NONE actually lands. The rung
        // is off this row's ladder on either surface (measured 2026-09-09: "Supported values are: 'minimal', 'low',
        // 'medium', and 'high'"), so sending it is a 400 -- and the Chat path guarded it while this one did not.
        //
        // The fixture is gpt-5-mini rather than gpt-5.6-terra, and the pair of tests is why: 'none' is not absent
        // everywhere on OpenAI, terra accepts it, and since round 9 terra has a row that says so. Asserting the
        // omission on a name whose row accepts the rung would assert nothing.
        final List<String> reported = new ArrayList<>();
        final ResponseCreateParams params = build(config(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build(), List.of(aTool()), GPT5,
                (signature, message, args) -> reported.add(signature));

        assertThat(params._reasoning()).isInstanceOf(JsonMissing.class);
        assertThat(ResponsesFixtures.bodyOf(params)).doesNotContain("\"effort\"");
        assertThat(reported).containsExactly("reasoningEffortOffLadder=NONE@" + GPT5_NAME);
    }

    @Test
    @DisplayName("NONE reaches the wire on gpt-5.6-terra, whose row says it accepts that rung")
    void noneReachesTheWireOnTerra() {
        // The other half of the row round 9 fixed, and the only place it is observable. Until then this rung was
        // withheld from a model measured to accept it -- the mirror of the MINIMAL 400 the row was written for.
        final List<String> reported = new ArrayList<>();
        final ResponseCreateParams params = build(config(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build(), List.of(aTool()), TERRA,
                (signature, message, args) -> reported.add(signature), TERRA_NAME);

        assertThat(ResponsesFixtures.bodyTreeOf(params).get("reasoning").get("effort").asText()).isEqualTo("none");
        assertThat(reported).isEmpty();
    }

    @Test
    @DisplayName("MINIMAL is off gpt-5.6-terra's ladder even though it is on the family's -- the gap, at the wire")
    void minimalIsOffTerrasLadderThoughItIsOnTheFamilysOne() {
        // The 400 this round removed. Terra resolved to the gpt-5 prefix row, whose ladder starts at MINIMAL, so a
        // configured MINIMAL reached the wire as "effort":"minimal" and the API rejected it (measured 2026-09-09).
        // No floor describes terra's ladder -- 'none' is below 'minimal' and terra takes it -- which is why the
        // capability became a set and why this pair of assertions can both be true at once.
        final List<String> reported = new ArrayList<>();
        final ResponseCreateParams onTerra = build(config(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build(), List.of(), TERRA,
                (signature, message, args) -> reported.add(signature), TERRA_NAME);
        final ResponseCreateParams onFamily = build(config(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build(), List.of(), GPT5, SILENT);

        assertThat(onTerra._reasoning()).isInstanceOf(JsonMissing.class);
        assertThat(reported).containsExactly("reasoningEffortOffLadder=MINIMAL@" + TERRA_NAME);
        assertThat(ResponsesFixtures.bodyTreeOf(onFamily).get("reasoning").get("effort").asText()).isEqualTo("minimal");
    }

    @Test
    @DisplayName("MINIMAL is below the o-series ladder and is omitted there, while gpt-5.x still takes it")
    void minimalIsOnGpt5sLadderButNotTheOSeriesOne() {
        // Same rule, second rung. The o-series rejects 'minimal' -- measured on this endpoint on 2026-09-09:
        // "Unsupported value: 'minimal' is not supported with the 'o4-mini' model. Supported values are: 'low',
        // 'medium', and 'high'." -- so the neutral MINIMAL has no wire value there either, and it is omitted rather
        // than raised to 'low', because a raised rung is a request the operator did not make. Since round 8 this is
        // also the endpoint o4-mini actually reaches, so the o-series half is no longer hypothetical.
        //
        // The gpt-5.x half asserts that 'minimal' DOES reach the wire for the gpt-5 family row, and the fixture
        // name is what makes that true rather than a knowingly wrong assertion: it used to resolve through
        // gpt-5.6-terra, which rejects 'minimal' at the live API, and round 9 gave that name a row of its own.
        // minimalIsOffTerrasLadderThoughItIsOnTheFamilysOne above is where that fact now lives.
        final ModelCapabilities oSeries = InMemoryModelCapabilityRegistry.withDefaults().resolve("o4-mini");
        final List<String> reported = new ArrayList<>();

        final ResponseCreateParams onGpt5 = build(config(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build(), List.of(), GPT5, SILENT);
        final ResponseCreateParams onOSeries = build(config(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build(), List.of(), oSeries,
                (signature, message, args) -> reported.add(signature));

        assertThat(ResponsesFixtures.bodyTreeOf(onGpt5).get("reasoning").get("effort").asText()).isEqualTo("minimal");
        assertThat(onOSeries._reasoning()).isInstanceOf(JsonMissing.class);
        assertThat(reported).containsExactly("reasoningEffortOffLadder=MINIMAL@" + GPT5_NAME);
    }

    @Test
    @DisplayName("an unconfigured reasoning effort is omitted so the server's own default applies")
    void noEffortConfiguredSendsNoReasoningBlock() {
        final ResponseCreateParams params = build(config(), LlmModel.builder().build(), List.of(aTool()));

        assertThat(params._reasoning()).isInstanceOf(JsonMissing.class);
        assertThat(ResponsesFixtures.bodyOf(params)).doesNotContain("reasoning\":{");
    }

    // ---- reasoning.summary: the second, independent contributor to the reasoning object ----

    @Test
    @DisplayName("summary unset and effort unset: no reasoning object at all, exactly as before")
    void noSummaryAndNoEffortSendsNoReasoningBlock() {
        // Criterion 6's OpenAI half. The whole reasoning object has to stay absent, not merely be empty: an empty
        // object is a wire change, and a deployment that set nothing must see none.
        final ResponseCreateParams params = build(config(), LlmModel.builder().build(), List.of());

        assertThat(params._reasoning()).isInstanceOf(JsonMissing.class);
        assertThat(ResponsesFixtures.bodyOf(params)).doesNotContain("reasoning\":{");
    }

    @Test
    @DisplayName("summary unset and effort set: the object is what it was — effort alone")
    void effortWithoutSummaryIsUnchanged() {
        final ResponseCreateParams params = build(config(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of());

        final JsonNode reasoning = ResponsesFixtures.bodyTreeOf(params).get("reasoning");
        assertThat(reasoning.get("effort").asText()).isEqualTo("high");
        assertThat(reasoning.has("summary")).isFalse();
    }

    @Test
    @DisplayName("summary set and effort unset: a reasoning object carrying only the summary")
    void summaryWithoutEffortStillProducesAReasoningObject() {
        // The case the old applyReasoningEffort shape could not produce: it called builder.reasoning(...) only when
        // an effort was present, so asking for a summary alone would have sent no reasoning object at all.
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model(GPT5_NAME)
                .reasoningSummary(OpenAiReasoningSummary.DETAILED).build();

        final JsonNode reasoning = ResponsesFixtures.bodyTreeOf(build(config, LlmModel.builder().build(), List.of()))
                .get("reasoning");

        assertThat(reasoning.get("summary").asText()).isEqualTo("detailed");
        assertThat(reasoning.has("effort")).isFalse();
    }

    @Test
    @DisplayName("summary set and effort set: both fields, on one object")
    void summaryAndEffortTogether() {
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model(GPT5_NAME)
                .reasoningSummary(OpenAiReasoningSummary.AUTO).build();

        final JsonNode reasoning = ResponsesFixtures
                .bodyTreeOf(
                        build(config, LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build(), List.of()))
                .get("reasoning");

        assertThat(reasoning.get("summary").asText()).isEqualTo("auto");
        assertThat(reasoning.get("effort").asText()).isEqualTo("medium");
    }

    @Test
    @DisplayName("an effort that fails the ladder check is still omitted while the summary is still sent")
    void anOmittedEffortDoesNotSuppressTheSummary() {
        // The two contributors are independent, and this is the row that proves it: the effort's two gates keep
        // their old semantics — omitted and reported — but no longer return early from the whole method.
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model(GPT5_NAME)
                .reasoningSummary(OpenAiReasoningSummary.CONCISE).build();
        final List<String> reported = new ArrayList<>();

        final JsonNode reasoning = ResponsesFixtures
                .bodyTreeOf(build(config, LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build(), List.of(),
                        GPT5, (signature, message, args) -> reported.add(signature)))
                .get("reasoning");

        assertThat(reasoning.has("effort")).isFalse();
        assertThat(reasoning.get("summary").asText()).isEqualTo("concise");
        assertThat(reported).containsExactly("reasoningEffortOffLadder=NONE@" + GPT5_NAME);
    }

    @Test
    @DisplayName("a model that takes no reasoning effort at all still receives the summary")
    void aModelWithoutAnEffortLadderStillReceivesTheSummary() {
        // The summary's own gate is a different flag from the effort's, and both fail open in the direction that
        // preserves what the caller asked for: supportsReasoningSummary defaults to true because a summary is only
        // ever on a request because somebody set it, so withholding it would be fail-CLOSED. gpt-4o's row states no
        // summary flag, so it inherits that true, and this request is byte-identical to the one before #72.
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model("gpt-4o")
                .reasoningSummary(OpenAiReasoningSummary.AUTO).build();
        final ModelCapabilities noEffort = InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-4o");
        assertThat(noEffort.supportsReasoningEffort()).as("fixture precondition").isFalse();
        assertThat(noEffort.supportsReasoningSummary()).as("fixture precondition").isTrue();

        final JsonNode reasoning = ResponsesFixtures
                .bodyTreeOf(build(config, LlmModel.builder().build(), List.of(), noEffort, SILENT, "gpt-4o"))
                .get("reasoning");

        assertThat(reasoning.get("summary").asText()).isEqualTo("auto");
        assertThat(reasoning.has("effort")).isFalse();
    }

    @Test
    @DisplayName("a model declared not to accept a summary gets none, while its effort still lands")
    void aDeclaredSummaryRefusalWithholdsOnlyTheSummary() {
        // #72: the gateway that implements reasoning.effort and 400s on reasoning.summary. Before this gate the
        // summary went unconditionally and the request failed on the one parameter the table was never asked about.
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model(GPT5_NAME)
                .reasoningSummary(OpenAiReasoningSummary.AUTO).build();
        final ModelCapabilities refusesSummary = ModelCapabilities.builder().supportsReasoningEffort(true)
                .supportsReasoningTraceRoundTrip(true).supportsReasoningSummary(false).build();

        final JsonNode reasoning = ResponsesFixtures.bodyTreeOf(build(config,
                LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build(), List.of(), refusesSummary, SILENT))
                .get("reasoning");

        assertThat(reasoning.has("summary")).isFalse();
        assertThat(reasoning.get("effort").asText()).isEqualTo("medium");
    }

    @Test
    @DisplayName("a withheld summary that was the only contributor leaves no reasoning object at all")
    void aWithheldSummaryLeavesNoReasoningObject() {
        // The "set the object only if at least one part landed" shape, on the new arm: an empty reasoning object is
        // itself a wire change, so withholding the sole contributor has to withhold the object with it.
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model(GPT5_NAME)
                .reasoningSummary(OpenAiReasoningSummary.AUTO).build();
        final ModelCapabilities refusesSummary = ModelCapabilities.builder().supportsReasoningSummary(false).build();

        final ResponseCreateParams params = build(config, LlmModel.builder().build(), List.of(), refusesSummary,
                SILENT);

        assertThat(params._reasoning()).isInstanceOf(JsonMissing.class);
        assertThat(ResponsesFixtures.bodyOf(params)).doesNotContain("reasoning\":{");
    }

    @Test
    @DisplayName("a withheld summary is reported once, under a signature the Chat path's two do not use")
    void aWithheldSummaryIsReported() {
        // The repository's standing rule on this request: do not send a parameter a described model does not accept,
        // and say that you withheld it. The signature is distinct from the client's reasoningSummaryOffResponsesPath
        // and reasoningSummaryWithResponsesDisabled, which fire only when the request went to Chat Completions.
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model(GPT5_NAME)
                .reasoningSummary(OpenAiReasoningSummary.CONCISE).build();
        final ModelCapabilities refusesSummary = ModelCapabilities.builder().supportsReasoningSummary(false).build();
        final List<String> reported = new ArrayList<>();

        build(config, LlmModel.builder().build(), List.of(), refusesSummary,
                (signature, message, args) -> reported.add(signature));

        assertThat(reported).containsExactly("reasoningSummary=CONCISE@" + GPT5_NAME);
    }

    @Test
    @DisplayName("a deployment that declares nothing sends the request it sent before the summary had a gate")
    void anUndeclaredModelIsByteIdentical() {
        // The acceptance claim behind fail-open true: nothing on the wire moves for a deployment that writes none of
        // the new keys. Asserted as the serialized body rather than field by field, because "byte-identical" is the
        // claim.
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model(GPT5_NAME)
                .reasoningSummary(OpenAiReasoningSummary.AUTO).build();

        final String body = ResponsesFixtures
                .bodyOf(build(config, LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of(),
                        ModelCapabilities.unknown(), SILENT));

        assertThat(body).contains("\"summary\":\"auto\"");
    }

    @Test
    @DisplayName("a tool's schema is copied verbatim and strict is false")
    void toolSchemaIsCopiedVerbatimAndStrictIsFalse() {
        // The schema shape strict mode REJECTS: an optional property (present in properties, absent from required)
        // and no additionalProperties:false. Every MCP tool is in that population, and the repo's own
        // additionalProperties rule is scoped to at.aimon.core.tools and exempts MCP schemas -- so writing
        // strict(true) would turn tools that work today into server-side rejections on the endpoint switch.
        final ToolDefinition tool = ToolDefinition.of("Read", "Reads a file",
                Map.of("type", "object", "properties",
                        Map.of("file_path", Map.of("type", "string"), "offset", Map.of("type", "integer")), "required",
                        List.of("file_path")));

        final JsonNode tools = ResponsesFixtures.bodyTreeOf(build(config(), LlmModel.builder().build(), List.of(tool)))
                .get("tools");

        assertThat(tools).hasSize(1);
        final JsonNode entry = tools.get(0);
        // Asserted on the body rather than on functionTool.strict(): that accessor returns Optional<Boolean> and
        // collapses JsonMissing and JsonNull exactly as temperature() does, so an implementation passing JsonNull
        // would read back as empty and could pass a weaker assertion while putting "strict": null on the wire.
        assertThat(entry.get("strict").asBoolean()).isFalse();
        assertThat(entry.get("name").asText()).isEqualTo("Read");
        assertThat(entry.get("description").asText()).isEqualTo("Reads a file");

        // ...and the parameters are byte-identical to what the Chat converter emits for the same ToolDefinition.
        assertThat(entry.get("parameters")).isEqualTo(chatParametersOf(tool));
    }

    @Test
    @DisplayName("a tool definition that fails conversion throws the same exception as the Chat path")
    void aBadToolSchemaThrowsTheSameExceptionAsChat() {
        // A schema value Jackson cannot represent. The Chat converter wraps per tool in a ToolConversionException
        // with this exact message and lets it escape before the try-with-resources; giving the same failure a
        // different type on the two endpoints is the divergence this parity rule exists to prevent.
        final ToolDefinition tool = ToolDefinition.of("Broken", "boom",
                Map.of("type", "object", "properties", Map.of("x", new Object())));

        assertThatThrownBy(() -> build(config(), LlmModel.builder().build(), List.of(tool)))
                .isInstanceOf(ToolConversionException.class).hasMessage("Failed to convert tool definition: Broken");
    }

    @Test
    @DisplayName("a penalty this endpoint has no slot for is reported rather than silently dropped")
    void aPenaltyWithNoCounterpartIsReported() {
        // The Responses API carries temperature and top_p and has NO presence/frequency penalty at all. On a model
        // that accepts sampling, a configured penalty would otherwise vanish with no error and no log line -- exactly
        // the "succeeds with settings other than the configured ones" failure the divergence reporting exists for.
        final ModelCapabilities samplingOk = ModelCapabilities.builder().supportsSamplingParameters(true)
                .supportsReasoningTraceRoundTrip(true).build();
        final List<String> reported = new java.util.ArrayList<>();

        factoryFor(config(), (signature, message, args) -> reported.add(String.valueOf(args[0]))).build("sys",
                List.of(Message.user("hi")), List.of(),
                LlmModel.builder().temperature(0.7).presencePenalty(1.0).frequencyPenalty(-1.0).build(), samplingOk,
                "custom-reasoner", "OpenAI");

        assertThat(reported).containsExactlyInAnyOrder("presencePenalty", "frequencyPenalty");
    }

    @Test
    @DisplayName("a trace anchored to a call this message does not carry is reported rather than silently dropped")
    void anOrphanedTraceIsReported() {
        // The emit rule sends unanchored traces first and each anchored trace in front of its own call, so a trace
        // whose anchor names a call that is not here is sent by neither loop. Dropping it stays right -- there is no
        // item to put it in front of -- but the module reports its other two drop reasons (a foreign author, an
        // unparseable payload), and an omission nobody announces is one nobody can diagnose from the outside.
        //
        // Not reachable through in-tree callers today; a caller assembling a Message by hand can reach it.
        final ReasoningTrace orphan = ReasoningTrace.builder().providerName("OpenAI")
                .payload("{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]}").toolUseId("call_gone").build();
        final Message assistant = Message.assistant("", List.of(ToolUse.of("call_here", "Bash", Map.of())))
                .withReasoningTraces(List.of(orphan));
        final List<String> reported = new java.util.ArrayList<>();

        factoryFor(config(), (signature, message, args) -> reported.add(signature)).build("sys", List.of(assistant),
                List.of(), LlmModel.builder().build(), GPT5, GPT5_NAME, "OpenAI");

        assertThat(reported).containsExactly("orphanedReasoningTrace@OpenAI");
    }

    @Test
    @DisplayName("a trace anchored to a call the message does carry is not reported")
    void anAnchoredTraceIsNotReported() {
        // The arm that keeps the one above honest: reporting every anchored trace would be just as wrong as
        // reporting none, and would train an operator to ignore the signal.
        final ReasoningTrace anchored = ReasoningTrace.builder().providerName("OpenAI")
                .payload("{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]}").toolUseId("call_here").build();
        final Message assistant = Message.assistant("", List.of(ToolUse.of("call_here", "Bash", Map.of())))
                .withReasoningTraces(List.of(anchored));
        final List<String> reported = new java.util.ArrayList<>();

        factoryFor(config(), (signature, message, args) -> reported.add(signature)).build("sys", List.of(assistant),
                List.of(), LlmModel.builder().build(), GPT5, GPT5_NAME, "OpenAI");

        assertThat(reported).isEmpty();
    }

    private static ToolDefinition aTool() {
        return ToolDefinition.of("Read", "Reads a file", Map.of("type", "object", "properties", Map.of()));
    }

    /** What {@code OpenAIMessageConverter.convertTools} produces for the same definition, as a tree. */
    private static JsonNode chatParametersOf(ToolDefinition tool) {
        final FunctionParameters.Builder params = FunctionParameters.builder();
        tool.getInputSchema().forEach((key, value) -> params.putAdditionalProperty(key, JsonValue.from(value)));
        final ChatCompletionFunctionTool chatTool = ChatCompletionFunctionTool.builder().function(FunctionDefinition
                .builder().name(tool.getName()).description(tool.getDescription()).parameters(params.build()).build())
                .build();
        try {
            return com.openai.core.ObjectMappers.jsonMapper()
                    .readTree(com.openai.core.ObjectMappers.jsonMapper().writeValueAsString(chatTool)).get("function")
                    .get("parameters");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
