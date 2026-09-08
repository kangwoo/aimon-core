package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import at.aimon.core.llm.ToolDefinition;
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

    private static final ModelCapabilities GPT5 = InMemoryModelCapabilityRegistry.withDefaults()
            .resolve("gpt-5.6-terra");

    private static final OpenAIDivergenceReporter SILENT = (signature, message, args) -> {
    };

    private static OpenAIResponsesRequestFactory factoryFor(OpenAIConfig config, OpenAIDivergenceReporter reporter) {
        return new OpenAIResponsesRequestFactory(new OpenAIResponsesMessageConverter(), config, "OpenAI", reporter);
    }

    private ResponseCreateParams build(OpenAIConfig config, LlmModel model, List<ToolDefinition> tools) {
        return factoryFor(config, SILENT).build("You are helpful", List.of(Message.user("hi")), tools, model, GPT5,
                "gpt-5.6-terra");
    }

    private static OpenAIConfig config() {
        return OpenAIConfig.builder().apiKey("k").model("gpt-5.6-terra").build();
    }

    @Test
    @DisplayName("sampling parameters are MISSING, not JSON null, even when configured")
    void samplingIsSuppressedAsMissing() {
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("k").model("gpt-5.6-terra").temperature(0.7).topP(0.5)
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
        assertThat(body.get("model").asText()).isEqualTo("gpt-5.6-terra");
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
    @DisplayName("an unconfigured reasoning effort is omitted so the server's own default applies")
    void noEffortConfiguredSendsNoReasoningBlock() {
        final ResponseCreateParams params = build(config(), LlmModel.builder().build(), List.of(aTool()));

        assertThat(params._reasoning()).isInstanceOf(JsonMissing.class);
        assertThat(ResponsesFixtures.bodyOf(params)).doesNotContain("reasoning\":{");
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
                "custom-reasoner");

        assertThat(reported).containsExactlyInAnyOrder("presencePenalty", "frequencyPenalty");
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
