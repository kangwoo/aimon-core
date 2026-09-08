package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonMissing;
import com.openai.core.JsonNull;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * Tests that the request this client builds is shaped by the model's {@link ModelCapabilities} and by nothing else it
 * knows about the model name.
 *
 * <p>
 * <strong>Absence is asserted on the raw {@code _xxx()} accessors, never on {@code xxx().isEmpty()}.</strong> The
 * SDK's {@code temperature()} delegates to {@code JsonField.getOptional}, which collapses {@code JsonMissing} and
 * {@code JsonNull} to {@code Optional.empty()} — so an implementation that called
 * {@code requestBuilder.temperature(null)} would put {@code "temperature": null} on the wire, earn exactly the HTTP
 * 400 this branch exists to remove, and still pass an {@code isEmpty()} assertion. The raw accessor is the only one
 * that can fail on that bug.
 */
@DisplayName("OpenAILlmClient - per-model capabilities")
@ExtendWith(MockitoExtension.class)
class OpenAILlmClientModelCapabilityTest {

    /** Aborts the SDK call after buildRequest has run, so no valid SDK ChatCompletion has to be constructed. */
    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

    private static final ToolDefinition A_TOOL = ToolDefinition.of("Read", "Reads a file",
            Map.of("type", "object", "properties", Map.of()));

    /** What the built-in table says about gpt-5.x, restated by hand for the tests that build their own registry. */
    private static final ModelCapabilities GPT5_LIKE = ModelCapabilities.builder().supportsSamplingParameters(false)
            .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build();

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ChatService mockChatService;

    @Mock
    private ChatCompletionService mockChatCompletionService;

    // ------------------------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------------------------

    private OpenAILlmClient clientFor(OpenAIConfig config) {
        lenient().when(mockOpenAIClient.chat()).thenReturn(mockChatService);
        lenient().when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        return new OpenAILlmClient(config, mockOpenAIClient);
    }

    private OpenAIConfig.Builder config(String model) {
        return OpenAIConfig.builder().apiKey("test-key").model(model);
    }

    private OpenAIConfig configWith(String model, ModelCapabilityRegistry registry) {
        return config(model).modelCapabilityRegistry(registry).build();
    }

    private static ModelCapabilityRegistry registryOf(String modelName, ModelCapabilities capabilities) {
        return name -> modelName.equals(name) ? Optional.of(capabilities) : Optional.empty();
    }

    /** Runs the blocking path and returns the params the SDK was called with. */
    private ChatCompletionCreateParams capture(OpenAIConfig config, LlmModel model, List<ToolDefinition> tools) {
        when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class))).thenThrow(SENTINEL);
        final OpenAILlmClient client = clientFor(config);

        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")), tools, model))
                .hasRootCause(SENTINEL);

        final ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor
                .forClass(ChatCompletionCreateParams.class);
        verify(mockChatCompletionService).create(captor.capture());
        return captor.getValue();
    }

    /** Runs the streaming path and returns the params the SDK was called with. */
    private ChatCompletionCreateParams captureStreaming(OpenAIConfig config, LlmModel model,
            List<ToolDefinition> tools) {
        @SuppressWarnings("unchecked")
        final StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.empty());
        when(mockChatCompletionService.createStreaming(any(ChatCompletionCreateParams.class)))
                .thenReturn(streamResponse);

        clientFor(config).sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")), tools, model,
                LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), chunk -> {
                }, LlmCancellation.none());

        final ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor
                .forClass(ChatCompletionCreateParams.class);
        verify(mockChatCompletionService).createStreaming(captor.capture());
        return captor.getValue();
    }

    /** Every suppressible sampling parameter is genuinely absent — missing, not null. */
    private static void assertSamplingOmitted(ChatCompletionCreateParams params) {
        assertThat(params._temperature()).isInstanceOf(JsonMissing.class);
        assertThat(params._topP()).isInstanceOf(JsonMissing.class);
        assertThat(params._presencePenalty()).isInstanceOf(JsonMissing.class);
        assertThat(params._frequencyPenalty()).isInstanceOf(JsonMissing.class);
    }

    /** The request shape every release before the capability registry produced. */
    private static void assertTodaysDefaultShape(ChatCompletionCreateParams params) {
        assertThat(params.temperature()).contains(0.0);
        assertThat(params._topP()).isInstanceOf(JsonMissing.class);
        assertThat(params._presencePenalty()).isInstanceOf(JsonMissing.class);
        assertThat(params._frequencyPenalty()).isInstanceOf(JsonMissing.class);
        assertThat(params._reasoningEffort()).isInstanceOf(JsonMissing.class);
    }

    // ------------------------------------------------------------------------------------------------------------
    // The issue's own reproduction, against a stock config
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a STOCK config on gpt-5.6-terra with a tool omits sampling and sends reasoning_effort=none")
    void stockConfigFixesTheReportedFourHundred() {
        // The reported bug, end to end, with nothing overridden: no registry passed, no temperature configured, one
        // tool in the request. This is the only test in the suite that fails if OpenAIConfig's default registry is
        // wired to ModelCapabilityRegistry.EMPTY instead of InMemoryModelCapabilityRegistry.withDefaults() -- every
        // other suppression test here builds its registry by hand and would stay green while the 400 shipped.
        final ChatCompletionCreateParams params = capture(config("gpt-5.6-terra").build(), LlmModel.builder().build(),
                List.of(A_TOOL));

        assertThat(params._temperature()).isInstanceOf(JsonMissing.class);
        assertThat(params.reasoningEffort()).contains(com.openai.models.ReasoningEffort.NONE);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Fail open
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a model no registry knows keeps exactly today's request shape")
    void unknownModelKeepsTodaysShape() {
        final ChatCompletionCreateParams params = capture(configWith("gpt-4o", ModelCapabilityRegistry.EMPTY),
                LlmModel.builder().build(), List.of(A_TOOL));

        assertTodaysDefaultShape(params);
    }

    @Test
    @DisplayName("o3 on the shipped defaults behaves exactly like an unknown model")
    void oSeriesBehavesAsUnknownOnShippedDefaults() {
        // Pins the decision to cut the o-series rows from the built-in table: those models are believed to reject
        // sampling, but the belief is unverified and a wrong entry would be a *silent* sampling change, while leaving
        // them out leaves them exactly where they are. Re-adding the rows has to change this test.
        final ChatCompletionCreateParams params = capture(config("o3").build(), LlmModel.builder().build(),
                List.of(A_TOOL));

        assertTodaysDefaultShape(params);
    }

    @Test
    @DisplayName("gpt-5-chat-latest keeps its sampling parameters")
    void gpt5ChatKeepsSampling() {
        final ChatCompletionCreateParams params = capture(config("gpt-5-chat-latest").build(),
                LlmModel.builder().build(), List.of(A_TOOL));

        assertTodaysDefaultShape(params);
    }

    @Test
    @DisplayName("a registry that throws is treated as knowing nothing")
    void throwingRegistryFailsOpen() {
        // buildRequest runs outside the streaming path's try-with-resources, so an escaping exception would bypass
        // both the exception mapper and the cancellation classification.
        final ModelCapabilityRegistry exploding = modelName -> {
            throw new IllegalStateException("registry is broken");
        };

        final ChatCompletionCreateParams params = capture(configWith("gpt-5.6-terra", exploding),
                LlmModel.builder().build(), List.of(A_TOOL));

        assertTodaysDefaultShape(params);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Suppression
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a model that rejects sampling receives none of the four parameters")
    void samplingSuppressed() {
        final LlmModel model = LlmModel.builder().name("no-sampling").temperature(0.7).topP(0.9).presencePenalty(1.0)
                .frequencyPenalty(1.0).build();

        final ChatCompletionCreateParams params = capture(configWith("gpt-4", registryOf("no-sampling", GPT5_LIKE)),
                model, List.of());

        assertSamplingOmitted(params);
    }

    @Test
    @DisplayName("the suppressed temperature is MISSING, not JSON null")
    void suppressedTemperatureIsMissingNotNull() {
        // The trap: ChatCompletionCreateParams.Builder.temperature(Double) routes through JsonField.ofNullable, which
        // maps null to JsonNull and serialises as "temperature": null -- present, which is what gpt-5.x rejects.
        // Both states read back as Optional.empty(), so only the raw field can tell them apart.
        final LlmModel model = LlmModel.builder().name("no-sampling").temperature(0.7).build();

        final ChatCompletionCreateParams params = capture(configWith("gpt-4", registryOf("no-sampling", GPT5_LIKE)),
                model, List.of());

        assertThat(params._temperature()).isInstanceOf(JsonMissing.class).isNotInstanceOf(JsonNull.class);
        assertThat(params._temperature()).isSameAs(JsonMissing.of());
    }

    @Test
    @DisplayName("a temperature configured on OpenAIConfig is suppressed too")
    void configLevelTemperatureSuppressed() {
        final OpenAIConfig config = config("gpt-5.6-terra").temperature(0.7).topP(0.5).presencePenalty(0.5)
                .frequencyPenalty(0.5).build();

        assertSamplingOmitted(capture(config, LlmModel.builder().build(), List.of()));
    }

    @Test
    @DisplayName("the streaming path suppresses exactly as the blocking path does")
    void streamingPathSuppressesToo() {
        final ChatCompletionCreateParams params = captureStreaming(config("gpt-5.6-terra").temperature(0.7).build(),
                LlmModel.builder().build(), List.of(A_TOOL));

        assertSamplingOmitted(params);
        assertThat(params.reasoningEffort()).contains(com.openai.models.ReasoningEffort.NONE);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Reasoning effort
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a reasoning model that cannot combine tools with reasoning gets an EXPLICIT none")
    void toolsClampReasoningEffortToNone() {
        // Explicit rather than omitted: the server default for these models is "medium", so leaving the parameter out
        // fails the same way sending "medium" would.
        final ChatCompletionCreateParams params = capture(config("gpt-5.6-terra").build(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of(A_TOOL));

        assertThat(params.reasoningEffort()).contains(com.openai.models.ReasoningEffort.NONE);
    }

    @Test
    @DisplayName("a reasoning model with no tools and no configured effort sends nothing")
    void noToolsNoEffortSendsNothing() {
        // The compaction / summarization path calls sendMessage(..., List.of(), ...), so a reasoning model can still
        // reason there; phase 1 does not disable reasoning further than the endpoint already forces.
        final ChatCompletionCreateParams params = capture(config("gpt-5.6-terra").build(), LlmModel.builder().build(),
                List.of());

        assertThat(params._reasoningEffort()).isInstanceOf(JsonMissing.class);
    }

    @Test
    @DisplayName("a reasoning model with no tools sends the configured effort as asked")
    void noToolsSendsTheConfiguredEffort() {
        final ChatCompletionCreateParams params = capture(config("gpt-5.6-terra").build(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of());

        assertThat(params.reasoningEffort()).contains(com.openai.models.ReasoningEffort.HIGH);
    }

    @Test
    @DisplayName("a model that DOES combine tools with reasoning is not clamped")
    void toolsWithReasoningIsNotClamped() {
        // The shape a caller-registered o-series entry has: it takes an effort, it accepts tools alongside one, and it
        // rejects the value "none" -- so clamping it would break the very deployment the entry exists to describe.
        final ModelCapabilities oSeriesLike = ModelCapabilities.builder().supportsSamplingParameters(false)
                .supportsReasoningEffort(true).supportsToolsWithReasoning(true).build();

        final ChatCompletionCreateParams params = capture(configWith("o3-custom", registryOf("o3-custom", oSeriesLike)),
                LlmModel.builder().name("o3-custom").reasoningEffort(ReasoningEffort.HIGH).build(), List.of(A_TOOL));

        assertThat(params.reasoningEffort()).contains(com.openai.models.ReasoningEffort.HIGH);
        assertSamplingOmitted(params);
    }

    @Test
    @DisplayName("an effort configured on OpenAIConfig reaches the model when LlmModel sets none")
    void configLevelReasoningEffortIsUsed() {
        final ChatCompletionCreateParams params = capture(
                config("gpt-5.6-terra").reasoningEffort(ReasoningEffort.LOW).build(), LlmModel.builder().build(),
                List.of());

        assertThat(params.reasoningEffort()).contains(com.openai.models.ReasoningEffort.LOW);
    }

    // ------------------------------------------------------------------------------------------------------------
    // No model-name knowledge of the client's own
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("with an EMPTY registry even a literal gpt-5.6-terra gets the untouched request")
    void clientHoldsNoModelNameKnowledge() {
        // Acceptance criterion 4: there is no `model.startsWith("gpt-5")` anywhere in buildRequest. If there were,
        // this request would be suppressed despite the registry knowing nothing.
        final ChatCompletionCreateParams params = capture(configWith("gpt-5.6-terra", ModelCapabilityRegistry.EMPTY),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of(A_TOOL));

        assertTodaysDefaultShape(params);
    }

    @Test
    @DisplayName("the per-request model name, not the config's, selects the capabilities")
    void perRequestModelNameSelectsCapabilities() {
        final ChatCompletionCreateParams params = capture(
                config("gpt-4o").modelCapabilityRegistry(InMemoryModelCapabilityRegistry.withDefaults()).build(),
                LlmModel.builder().name("gpt-5.6-terra").build(), List.of(A_TOOL));

        assertSamplingOmitted(params);
        assertThat(params.reasoningEffort()).contains(com.openai.models.ReasoningEffort.NONE);
        assertThat(params.model().asString()).isEqualTo("gpt-5.6-terra");
    }
}
