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
import at.aimon.core.llm.capability.ModelCapabilityDeclaration;
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
 *
 * <p>
 * <strong>Every test here is about the Chat Completions request shape</strong>, which is why the eight that name a
 * {@code gpt-5.x} model on a stock registry now set {@code responsesApiEnabled(false)}. That flag is a fixture line,
 * not an assertion change: the model name stays in the fixture, so what each test is <em>about</em> is unchanged, and
 * two of them ({@code toolsClampReasoningEffortToNone}, and {@code clampedReasoningEffortIsReported} in the
 * divergence sibling) test a clamp that is a Chat Completions rule outright and belongs nowhere else. The endpoint
 * choice itself is bound by {@link OpenAILlmClientEndpointSelectionTest}, and the Responses request shape by
 * {@code OpenAIResponsesRequestFactoryTest}.
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

    /** An LlmModel carrying all four sampling values, so a fail-open test can assert they arrive. */
    private static LlmModel modelWithAllSamplingValues() {
        return LlmModel.builder().temperature(0.3).topP(0.9).presencePenalty(1.0).frequencyPenalty(-1.0).build();
    }

    /**
     * Fail open, first half: every value the caller set arrived, and no reasoning effort was invented.
     *
     * <p>
     * This replaces round 1's {@code assertTodaysDefaultShape}, which asserted an <em>unset</em> temperature arrived
     * as {@code 0.0}. After round 2 removed that fallback, an unset temperature is absent — which would have made
     * that helper identical to {@link #assertSamplingOmitted} and left its five callers passing just as well under a
     * fail-<em>closed</em> {@code unknown()}, i.e. proving nothing about the property they exist for. So the inputs
     * changed too: these tests now send values and assert they survive.
     */
    private static void assertSamplingPassedThrough(ChatCompletionCreateParams params) {
        assertThat(params.temperature()).contains(0.3);
        assertThat(params.topP()).contains(0.9);
        assertThat(params.presencePenalty()).contains(1.0);
        assertThat(params.frequencyPenalty()).contains(-1.0);
        assertThat(params._reasoningEffort()).isInstanceOf(JsonMissing.class);
    }

    // ------------------------------------------------------------------------------------------------------------
    // The issue's own reproduction, against a stock config
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("gpt-5.6-terra on the Chat path with a tool omits sampling and omits reasoning_effort")
    void chatPathFixesTheReportedFourHundred() {
        // The reported bug, end to end, with nothing overridden except the endpoint: no registry passed, no
        // temperature configured, one tool in the request. This is the only test in the suite that fails if
        // OpenAIConfig's default registry is wired to ModelCapabilityRegistry.EMPTY instead of
        // InMemoryModelCapabilityRegistry.withDefaults() -- every other suppression test here builds its registry by
        // hand and would stay green while the 400 shipped.
        //
        // responsesApiEnabled(false) is what keeps this on Chat Completions now that the built-in gpt-5 row also says
        // its reasoning traces round-trip. The test is still about the Chat request shape, and the NONE clamp it
        // asserts is a Chat rule outright -- but it is no longer the STOCK-config binding, because a stock config
        // now routes this model to /v1/responses. That binding moved to
        // OpenAILlmClientEndpointSelectionTest.stockConfigOnAReasoningModelUsesResponses, which is where a reader
        // looking for "what does the shipped default do" should go.
        final ChatCompletionCreateParams params = capture(config("gpt-5.6-terra").responsesApiEnabled(false).build(),
                LlmModel.builder().build(), List.of(A_TOOL));

        assertThat(params._temperature()).isInstanceOf(JsonMissing.class);
        // Round 6, measured 2026-09-09: this used to assert an explicit NONE. The API rejects that value outright
        // ("Supported values are: 'minimal', 'low', 'medium', and 'high'"), while a tools request that omits the
        // parameter returns 200 -- so the shipped gpt-5 row now says supportsToolsWithReasoning=true and nothing is
        // sent. See docs/design/llm/openai-model-capabilities.md section 11.
        assertThat(params._reasoningEffort()).isInstanceOf(JsonMissing.class);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Fail open
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a model no registry knows is sent everything the caller asked for")
    void unknownModelSendsWhatTheCallerAskedFor() {
        // Round 2 reversal, by maintainer ruling: this test used to send an EMPTY LlmModel and assert the request
        // still carried temperature=0.0, from OpenAIConfig.DEFAULT_TEMPERATURE. That fallback was removed
        // deliberately -- issue #43's "sampling parameters are sent only when the caller explicitly set them" beats
        // round 1's "an unknown model sends exactly what it sends today", which round 1 had recorded as design
        // O-1/A6. See docs/design/llm/openai-model-capabilities.md section 9.
        final ChatCompletionCreateParams params = capture(configWith("gpt-4o", ModelCapabilityRegistry.EMPTY),
                modelWithAllSamplingValues(), List.of(A_TOOL));

        assertSamplingPassedThrough(params);
    }

    @Test
    @DisplayName("a sampling parameter nobody set is absent even on a model no registry knows")
    void unsetTemperatureIsAbsentEvenOnAnUnknownModel() {
        // The other direction of fail open, and the pair is what makes it binding: the test above fails if unknown()
        // were flipped to fail-closed, this one fails if the DEFAULT_TEMPERATURE fallback came back. Neither alone
        // pins both halves of "nothing withheld, nothing invented".
        //
        // DO NOT DELETE THIS AS A DUPLICATE. It is the whole binding for acceptance criterion 2 -- an unset sampling
        // parameter is omitted rather than defaulted. Its near-namesake in OpenAILlmClientParameterDivergenceTest,
        // suppressionIsSilentForATemperatureNobodySet, runs on gpt-5.6-terra, where applySamplingParameters returns
        // at the suppression guard before the setter: round 2's review measured that neither temperature mutation
        // made that test fail. gpt-4o reaches the setter, so only this test does.
        //
        // Asserted on the raw field: params.temperature() is Optional.empty() for a missing field AND for an
        // explicit JsonNull, and gpt-5.x rejects "temperature": null as present -- so the weak accessor cannot fail
        // on the bug this guards.
        final ChatCompletionCreateParams params = capture(config("gpt-4o").build(), LlmModel.builder().build(),
                List.of(A_TOOL));

        assertThat(params._temperature()).isInstanceOf(JsonMissing.class).isNotInstanceOf(JsonNull.class);
        assertThat(params._temperature()).isSameAs(JsonMissing.of());
        assertSamplingOmitted(params);
    }

    @Test
    @DisplayName("o3 on the shipped defaults behaves exactly like an unknown model")
    void oSeriesSuppressesSamplingOnShippedDefaults() {
        // Round 6 reversal, measured 2026-09-09. Round 1 cut the o-series rows because the belief that they reject
        // sampling was unverified and a wrong row is a *silent* sampling change, while no row left those users
        // exactly where they were. The probes closed that: o3-mini and o4-mini answer 400 to temperature 0.0 and 200
        // to 1.0, accept tools with no effort, and reject effort "none". So the rows are in, and this test says the
        // opposite of what it used to -- deliberately. See docs/design/llm/openai-model-capabilities.md section 11.
        //
        // supportsReasoningTraceRoundTrip stays FALSE for these: replay was never measured for the o-series, and
        // asserting a round trip nobody has seen is how the gpt-5 row came out wrong the first time.
        // Round 2 reversal, by maintainer ruling: this test used to send an EMPTY LlmModel and assert the request
        // still carried temperature=0.0, from OpenAIConfig.DEFAULT_TEMPERATURE. That fallback was removed
        // deliberately -- issue #43's "sampling parameters are sent only when the caller explicitly set them" beats
        // round 1's "an unknown model sends exactly what it sends today", which round 1 had recorded as design
        // O-1/A6. See docs/design/llm/openai-model-capabilities.md section 9.
        final ChatCompletionCreateParams params = capture(config("o3").build(), modelWithAllSamplingValues(),
                List.of(A_TOOL));

        assertSamplingOmitted(params);
        assertThat(params._reasoningEffort()).isInstanceOf(JsonMissing.class);
    }

    @Test
    @DisplayName("gpt-5-chat-latest keeps its sampling parameters")
    void gpt5ChatKeepsSampling() {
        // Round 2 reversal, by maintainer ruling: this test used to send an EMPTY LlmModel and assert the request
        // still carried temperature=0.0, from OpenAIConfig.DEFAULT_TEMPERATURE. That fallback was removed
        // deliberately -- issue #43's "sampling parameters are sent only when the caller explicitly set them" beats
        // round 1's "an unknown model sends exactly what it sends today", which round 1 had recorded as design
        // O-1/A6. See docs/design/llm/openai-model-capabilities.md section 9.
        final ChatCompletionCreateParams params = capture(config("gpt-5-chat-latest").build(),
                modelWithAllSamplingValues(), List.of(A_TOOL));

        assertSamplingPassedThrough(params);
    }

    @Test
    @DisplayName("a registry that throws is treated as knowing nothing")
    void throwingRegistryFailsOpen() {
        // buildRequest runs outside the streaming path's try-with-resources, so an escaping exception would bypass
        // both the exception mapper and the cancellation classification.
        final ModelCapabilityRegistry exploding = modelName -> {
            throw new IllegalStateException("registry is broken");
        };

        // Round 2 reversal, by maintainer ruling: this test used to send an EMPTY LlmModel and assert the request
        // still carried temperature=0.0, from OpenAIConfig.DEFAULT_TEMPERATURE. That fallback was removed
        // deliberately -- issue #43's "sampling parameters are sent only when the caller explicitly set them" beats
        // round 1's "an unknown model sends exactly what it sends today", which round 1 had recorded as design
        // O-1/A6. See docs/design/llm/openai-model-capabilities.md section 9.
        final ChatCompletionCreateParams params = capture(configWith("gpt-5.6-terra", exploding),
                modelWithAllSamplingValues(), List.of(A_TOOL));

        assertSamplingPassedThrough(params);
    }

    @Test
    @DisplayName("a registry whose resolve() returns null is treated as knowing nothing")
    void nullReturningRegistryFailsOpen() {
        // A lambda cannot produce this state -- resolve() is a default method over the functional capabilitiesOf --
        // so the double has to be a named class that overrides resolve() itself. Without the null check in
        // capabilitiesFor this NPEs inside buildRequest, which on the streaming path runs before the
        // try-with-resources: the escape the surrounding catch exists to prevent.
        final ChatCompletionCreateParams params = capture(configWith("gpt-5.6-terra", new NullResolvingRegistry()),
                modelWithAllSamplingValues(), List.of(A_TOOL));

        assertSamplingPassedThrough(params);
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
        final OpenAIConfig config = config("gpt-5.6-terra").responsesApiEnabled(false).temperature(0.7).topP(0.5)
                .presencePenalty(0.5).frequencyPenalty(0.5).build();

        assertSamplingOmitted(capture(config, LlmModel.builder().build(), List.of()));
    }

    @Test
    @DisplayName("the streaming path suppresses exactly as the blocking path does")
    void streamingPathSuppressesToo() {
        final ChatCompletionCreateParams params = captureStreaming(
                config("gpt-5.6-terra").responsesApiEnabled(false).temperature(0.7).build(), LlmModel.builder().build(),
                List.of(A_TOOL));

        assertSamplingOmitted(params);
        assertThat(params._reasoningEffort()).isInstanceOf(JsonMissing.class);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Reasoning effort
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a model that cannot combine tools with reasoning has the effort OMITTED, never sent as none")
    void toolsOmitReasoningEffort() {
        // Round 6 reversal, measured 2026-09-09. This used to assert an explicit NONE on the shipped gpt-5 row, on
        // the theory that omission would leave the server default of "medium" in force. Both halves were false:
        // "none" is not an accepted value for these models, and a tools request that omits the parameter returns 200.
        // Sending NONE was the bug, not the fix.
        //
        // The shipped gpt-5 row therefore says supportsToolsWithReasoning=true and no longer reaches this branch, so
        // the flag is driven from a hand-built row here -- the branch still exists for a caller who registers one.
        final ModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builder()
                .register("no-tools-with-reasoning", ModelCapabilities.builder().supportsSamplingParameters(true)
                        .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build())
                .build();

        final ChatCompletionCreateParams params = capture(
                config("no-tools-with-reasoning").responsesApiEnabled(false).modelCapabilityRegistry(registry).build(),
                LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of(A_TOOL));

        assertThat(params._reasoningEffort()).isInstanceOf(JsonMissing.class);
    }

    @Test
    @DisplayName("a reasoning model with no tools and no configured effort sends nothing")
    void noToolsNoEffortSendsNothing() {
        // The compaction / summarization path calls sendMessage(..., List.of(), ...), so a reasoning model can still
        // reason there; phase 1 does not disable reasoning further than the endpoint already forces.
        final ChatCompletionCreateParams params = capture(config("gpt-5.6-terra").responsesApiEnabled(false).build(),
                LlmModel.builder().build(), List.of());

        assertThat(params._reasoningEffort()).isInstanceOf(JsonMissing.class);
    }

    @Test
    @DisplayName("a reasoning model with no tools sends the configured effort as asked")
    void noToolsSendsTheConfiguredEffort() {
        final ChatCompletionCreateParams params = capture(config("gpt-5.6-terra").responsesApiEnabled(false).build(),
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
                config("gpt-5.6-terra").responsesApiEnabled(false).reasoningEffort(ReasoningEffort.LOW).build(),
                LlmModel.builder().build(), List.of());

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
        // Round 2 reversal, by maintainer ruling: this test used to send an EMPTY LlmModel and assert the request
        // still carried temperature=0.0, from OpenAIConfig.DEFAULT_TEMPERATURE. That fallback was removed
        // deliberately -- issue #43's "sampling parameters are sent only when the caller explicitly set them" beats
        // round 1's "an unknown model sends exactly what it sends today", which round 1 had recorded as design
        // O-1/A6. See docs/design/llm/openai-model-capabilities.md section 9.
        final ChatCompletionCreateParams params = capture(configWith("gpt-5.6-terra", ModelCapabilityRegistry.EMPTY),
                LlmModel.builder().temperature(0.3).topP(0.9).presencePenalty(1.0).frequencyPenalty(-1.0)
                        .reasoningEffort(ReasoningEffort.HIGH).build(),
                List.of(A_TOOL));

        assertSamplingPassedThrough(params);
    }

    @Test
    @DisplayName("the per-request model name, not the config's, selects the capabilities")
    void perRequestModelNameSelectsCapabilities() {
        // Still the binding for "the per-request name drives the CAPABILITY lookup", on the Chat path. The endpoint
        // half of the same property is bound separately, by OpenAILlmClientEndpointSelectionTest cases 5 and 6 --
        // neither implies the other, which is exactly why both exist.
        final ChatCompletionCreateParams params = capture(
                config("gpt-4o").responsesApiEnabled(false)
                        .modelCapabilityRegistry(InMemoryModelCapabilityRegistry.withDefaults()).build(),
                LlmModel.builder().name("gpt-5.6-terra").build(), List.of(A_TOOL));

        // The binding is assertSamplingOmitted plus the model name: gpt-4o would pass sampling through, gpt-5.6-terra
        // suppresses it, so suppression here proves the lookup used the PER-REQUEST name. Round 6 dropped a second
        // signal from this test -- an explicit reasoning_effort=NONE -- because the API rejects that value and the
        // shipped gpt-5 row no longer produces it. The property this test exists for is untouched.
        assertSamplingOmitted(params);
        assertThat(params._reasoningEffort()).isInstanceOf(JsonMissing.class);
        assertThat(params.model().asString()).isEqualTo("gpt-5.6-terra");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Issue #46: a renamed gateway deployment, described from configuration
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a gateway deployment named in configuration gets the same suppression a real name would")
    void aConfiguredDeploymentNameSuppressesSampling() {
        // Issue #46's reproduction, and the second half of a chain that cannot live in one test. The CLI and starter
        // tests carry "yaml/properties -> Map<String, ModelCapabilityDeclaration> -> OpenAIConfig"; they cannot make
        // this assertion because JsonMissing comes from an SDK that is an implementation dependency of this module and
        // is absent from their compile classpaths. So the seam between the halves is the translator itself --
        // withDefaultsExtendedBy, called here with what those tests hand it -- rather than a hand-built
        // ModelCapabilities, which would leave both halves green if the translator's defaulting were wrong.
        //
        // Asserted on the raw field. params.temperature() collapses a missing field and an explicit JsonNull to
        // Optional.empty(), and "temperature": null is exactly as fatal as a value -- so the weak accessor cannot fail
        // on the bug this closes.
        final ChatCompletionCreateParams params = capture(
                configWith("prod-assistant",
                        InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(Map.of("prod-assistant",
                                ModelCapabilityDeclaration.builder().supportsSamplingParameters(false).build()))),
                modelWithAllSamplingValues(), List.of(A_TOOL));

        assertThat(params._temperature()).isSameAs(JsonMissing.of()).isNotInstanceOf(JsonNull.class);
        assertSamplingOmitted(params);
    }

    @Test
    @DisplayName("declaring one deployment does not disturb the built-in rows")
    void aConfiguredDeploymentLeavesTheBuiltInRowsAlone() {
        // The extension is one name wide. gpt-5-mini is not declared, so it must still resolve through the built-in
        // gpt-5 prefix -- which on the Chat path means sampling is suppressed for it too, for the table's reason
        // rather than the declaration's.
        final ChatCompletionCreateParams params = capture(config("gpt-5-mini").responsesApiEnabled(false)
                .modelCapabilityRegistry(InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(Map.of("prod-assistant",
                        ModelCapabilityDeclaration.builder().supportsSamplingParameters(true).build())))
                .build(), modelWithAllSamplingValues(), List.of(A_TOOL));

        assertSamplingOmitted(params);
    }

    @Test
    @DisplayName("the minimal declaration keeps the deployment on Chat Completions")
    void theMinimalDeclarationStaysOnChatCompletions() {
        // Why all five flags are not required: what an operator omits keeps today's behaviour. A declaration naming
        // only supportsSamplingParameters leaves supportsReasoningTraceRoundTrip false, so the deployment is not
        // routed at /v1/responses -- which on a Chat-only gateway would turn the 400 into a 404. responsesApiEnabled
        // is left at its default here on purpose: the point is that the declaration, not a second switch, is what
        // keeps this request on the Chat path.
        final ChatCompletionCreateParams params = capture(
                configWith("prod-assistant",
                        InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(Map.of("prod-assistant",
                                ModelCapabilityDeclaration.builder().supportsSamplingParameters(false).build()))),
                LlmModel.builder().build(), List.of(A_TOOL));

        assertSamplingOmitted(params);
        assertThat(params.model().asString()).isEqualTo("prod-assistant");
    }
}
