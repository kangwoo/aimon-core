package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.exception.LlmInvalidRequestException;

/**
 * The live half of the thinking round trip: the assertions that only a real Anthropic call can make.
 *
 * <p>
 * <strong>Why this class exists at all.</strong> Every other test of this feature is a fixture test, and the design
 * document's §9 says plainly what that leaves unproven — above all U-1, whether Anthropic's verifier accepts a
 * {@code signature} that this client parsed and re-serialised. A fixture can show the decoded string is unchanged; it
 * cannot show the server agrees, because key ordering and JSON escaping may differ from the bytes that arrived. That
 * question has exactly one instrument, and it is a live call.
 *
 * <p>
 * Gated on {@code ANTHROPIC_KEY} like {@link AnthropicLlmClientIntegrationTest}, so a keyless CI skips it rather than
 * failing. It is deliberately a <em>separate</em> class from that one: that class pins the client's ordinary
 * behaviour on a single model with no thinking configured, and mixing a model matrix into it would make one failure
 * ambiguous between "thinking broke" and "the client broke".
 *
 * <p>
 * <strong>The model names are load-bearing and are not interchangeable.</strong> Each dialect is rejected by the
 * models that speak the other one (§2.1), so the pairing of mode to model in each test is the thing under test as
 * much as the assertion is.
 */
@DisplayName("AnthropicLlmClient - thinking against the real API")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_KEY", matches = ".+")
class AnthropicThinkingLiveTest {

    /** Extended-only, and the cheapest model that speaks that dialect. */
    private static final String EXTENDED_MODEL = "claude-haiku-4-5-20251001";

    /** Adaptive-only. Also one of the models whose `temperature` rejection blocks the no-thinking path entirely. */
    private static final String ADAPTIVE_MODEL = "claude-sonnet-5";

    private static final String SYSTEM = "You are a helpful assistant. Answer in one or two sentences.";

    private static AnthropicConfig.Builder config(String model) {
        return AnthropicConfig.builder().apiKey(System.getenv("ANTHROPIC_KEY")).model(model).maxTokens(2000);
    }

    private static ToolDefinition weatherTool() {
        return ToolDefinition.of("get_weather", "Get the current weather in a city",
                Map.of("type", "object", "additionalProperties", false, "properties",
                        Map.of("city", Map.of("type", "string", "description", "The city")), "required",
                        List.of("city")));
    }

    private static final String ASK_TOOL = "What is the weather in Seoul? Use the tool.";

    /**
     * Reproduces what the executor does between turns, exactly as the fixture round-trip test does — the traces ride
     * back on the assistant message. If this helper and that one ever diverge, the live test stops corroborating the
     * fixture one.
     */
    private static List<Message> secondTurn(LlmResponse first) {
        return List.of(Message.user(ASK_TOOL),
                Message.assistant(first.getTextContent(), first.getToolUses())
                        .withReasoningTraces(first.getReasoningTraces()),
                Message.toolUseResults(first.getToolUses().stream()
                        .map(toolUse -> ToolUseResult.success(toolUse.getId(), "18C, clear")).toList()));
    }

    @Nested
    @DisplayName("U-1: a replayed signature is accepted by the server")
    class ReplayedSignatureIsAccepted {

        @Test
        @DisplayName("EXTENDED: a captured thinking block round-trips through this client and the API accepts it")
        void extendedRoundTripIsAccepted() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(EXTENDED_MODEL).thinkingMode(AnthropicThinkingMode.EXTENDED).build())) {

                final LlmResponse first = client.sendMessage(SYSTEM, List.of(Message.user(ASK_TOOL)),
                        List.of(weatherTool()), LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build());

                // The turn the whole feature is about: thinking, then the tool call it introduced.
                assertThat(first.getToolUses()).isNotEmpty();
                assertThat(first.getReasoningTraces()).isNotEmpty();
                final ReasoningTrace trace = first.getReasoningTraces().get(0);
                assertThat(trace.getProviderName()).isEqualTo("Anthropic");
                assertThat(trace.getToolUseId()).contains(first.getToolUses().get(0).getId());

                // U-1. Not "the string is unchanged" — a fixture already binds that. This is the server accepting a
                // block that went out through Jackson rather than straight back off the wire. A rejection here reads
                // `thinking` or `redacted_thinking` blocks ... cannot be modified.
                final LlmResponse second = client.sendMessage(SYSTEM, secondTurn(first), List.of(weatherTool()),
                        LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build());

                assertThat(second.getTextContent()).isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("U-2: the two dialect rejections are exactly what the javadoc quotes")
    class DialectMismatchesAreRejected {

        @Test
        @DisplayName("EXTENDED against an adaptive-only model is a non-retryable invalid request")
        void extendedAgainstAdaptiveOnlyModel() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(ADAPTIVE_MODEL).thinkingMode(AnthropicThinkingMode.EXTENDED).build())) {

                assertThatThrownBy(() -> client.sendMessage(SYSTEM, List.of(Message.user("hi")), List.of(),
                        LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build()))
                        .isInstanceOf(LlmInvalidRequestException.class).hasMessageContaining("thinking.type.enabled");
            }
        }

        @Test
        @DisplayName("ADAPTIVE against an extended-only model is a non-retryable invalid request")
        void adaptiveAgainstExtendedOnlyModel() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(EXTENDED_MODEL).thinkingMode(AnthropicThinkingMode.ADAPTIVE).build())) {

                assertThatThrownBy(() -> client.sendMessage(SYSTEM, List.of(Message.user("hi")), List.of(),
                        LlmModel.builder().build())).isInstanceOf(LlmInvalidRequestException.class)
                        .hasMessageContaining("adaptive thinking is not supported");
            }
        }
    }

    @Nested
    @DisplayName("The adaptive dialect, and the sampling rule that decides whether it is reachable")
    class AdaptiveReachability {

        @Test
        @DisplayName("ADAPTIVE reaches an always-on model, because a thinking request omits temperature")
        void adaptiveReachesAnAlwaysOnModel() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(ADAPTIVE_MODEL).thinkingMode(AnthropicThinkingMode.ADAPTIVE).build())) {

                // The point: this client sends `temperature` unconditionally when thinking is off, and these models
                // reject the parameter outright — so `OFF` cannot reach them at all. Asking for thinking is what
                // suppresses the setter, which makes ADAPTIVE the one configuration that works here today.
                final LlmResponse response = client.sendMessage(SYSTEM, List.of(Message.user("What is 2 + 3?")),
                        List.of(), LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build());

                assertThat(response.getTextContent()).contains("5");
            }
        }

        @Test
        @DisplayName("thinkingMode OFF cannot reach the same model — the pre-existing sampling defect, pinned")
        void offCannotReachAnAlwaysOnModel() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(config(ADAPTIVE_MODEL).build())) {

                // Pinned rather than fixed. It is a model fact needing a per-model source of truth, and the failure
                // is what makes "capture and replay need no configuration" untrue on exactly the models that think
                // by default. When that lands, this test is the one that must change.
                assertThatThrownBy(() -> client.sendMessage(SYSTEM, List.of(Message.user("hi")), List.of(),
                        LlmModel.builder().build())).isInstanceOf(LlmInvalidRequestException.class)
                        .hasMessageContaining("temperature");
            }
        }
    }

    @Nested
    @DisplayName("U-8: the thinking token counter is read off a real response")
    class ThinkingTokensAreReported {

        @Test
        @DisplayName("reasoningTokens is populated, and is contained in completionTokens rather than added to the total")
        void reasoningTokensArePopulated() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(EXTENDED_MODEL).thinkingMode(AnthropicThinkingMode.EXTENDED).build())) {

                final LlmResponse response = client.sendMessage(SYSTEM,
                        List.of(Message.user("What is 17 * 23? Think it through.")), List.of(),
                        LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build());

                // The field name came from documentation and had never been seen. Nothing else in the client fails
                // if it is wrong — the counter just reads 0 — which is exactly why it needs an assertion that only a
                // live response can satisfy.
                assertThat(response.getTokenUsage().getReasoningTokens()).isPositive();
                assertThat(response.getTokenUsage().getReasoningTokens())
                        .isLessThanOrEqualTo(response.getTokenUsage().getCompletionTokens());
                assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(
                        response.getTokenUsage().getPromptTokens() + response.getTokenUsage().getCompletionTokens());
            }
        }
    }

    @Nested
    @DisplayName("U-10: what stripping the thinking block actually does")
    class StrippingTheBlock {

        @Test
        @DisplayName("replayThinkingBlocks(false) under EXTENDED is accepted, not rejected — it degrades")
        void strippingDegradesRatherThanRejecting() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(config(EXTENDED_MODEL)
                    .thinkingMode(AnthropicThinkingMode.EXTENDED).replayThinkingBlocks(false).build())) {

                final LlmModel model = LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build();
                final LlmResponse first = client.sendMessage(SYSTEM, List.of(Message.user(ASK_TOOL)),
                        List.of(weatherTool()), model);
                assertThat(first.getToolUses()).isNotEmpty();

                // The claim this replaces: that the pair is "documented as incompatible" and a tool loop "is
                // expected to be rejected on its second iteration". It is not. The vendor's graceful-degradation
                // sentence is the one that governs, and this is the assertion that says so — which is why the
                // warning that used to predict a rejection now describes a loss instead.
                final LlmResponse second = client.sendMessage(SYSTEM, secondTurn(first), List.of(weatherTool()), model);
                assertThat(second.getTextContent()).isNotBlank();
            }
        }
    }
}
