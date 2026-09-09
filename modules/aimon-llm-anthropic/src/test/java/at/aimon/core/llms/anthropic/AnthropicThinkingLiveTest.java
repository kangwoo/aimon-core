package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The live half of the thinking round trip: the assertions that only a real Anthropic call can make.
 *
 * <p>
 * <strong>Why this class exists at all.</strong> Every other test of this feature is a fixture test, and the design
 * document's §9 says plainly what that leaves unproven — above all U-1, whether Anthropic's verifier accepts a
 * {@code signature} that this client parsed and re-serialised. A fixture can show the decoded string is unchanged; it
 * cannot show the server agrees, because key ordering and JSON escaping may differ from the bytes that arrived.
 *
 * <p>
 * <strong>The positive case alone does not establish that, and an earlier revision of this class assumed it
 * did.</strong> "Replay the block, assert the second call succeeded" passes just as well when the block is silently
 * dropped — as {@link StrippingTheBlock} demonstrates on the same model, since a stripped turn is also accepted. So
 * the load-bearing test here is the <em>negative control</em>: change one character of the signature and the server
 * must reject it. Only the pair says the verifier looked.
 *
 * <p>
 * <strong>Cost and determinism.</strong> One tool-calling turn is captured once and reused by every assertion that
 * needs one, so the class makes roughly seven billable calls rather than one per test — a rejected request is not
 * billed for output, which is why the two negative controls here (a mutated signature, a removed capability row) cost
 * nothing. Where a claim depends on the model <em>choosing</em> to call a tool, which is a property of the
 * model rather than of this client, the test aborts through {@code assumeTrue} instead of failing. The line is drawn
 * deliberately: no tool call is the environment declining to set the test up, while a tool call that produced no
 * reasoning trace is this client's bug and must be red.
 *
 * <p>
 * Gated on {@code ANTHROPIC_KEY} like {@link AnthropicLlmClientIntegrationTest}, so a keyless CI skips it rather than
 * failing. It is deliberately a <em>separate</em> class from that one: that class pins the client's ordinary
 * behaviour on a single model with no thinking configured, and mixing a model matrix into it would make one failure
 * ambiguous between "thinking broke" and "the client broke".
 *
 * <p>
 * <strong>The model names are load-bearing and are not interchangeable.</strong> Each dialect is rejected by the
 * models that speak the other one (§2.1), so the pairing of mode to model in each test is as much the thing under
 * test as the assertion is.
 */
@DisplayName("AnthropicLlmClient - thinking against the real API")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_KEY", matches = ".+")
class AnthropicThinkingLiveTest {

    /** Extended-only, and the cheapest model that speaks that dialect. */
    private static final String EXTENDED_MODEL = "claude-haiku-4-5-20251001";

    /**
     * Adaptive-only, and one of the six models that reject a non-default {@code temperature}. That second property is
     * what {@link AdaptiveReachability} is about: it used to make {@code OFF} unreachable, and the capability row is
     * what makes it reachable now.
     */
    private static final String ADAPTIVE_MODEL = "claude-sonnet-5";

    private static final String SYSTEM = "You are a helpful assistant. Answer in one or two sentences.";
    private static final String ASK_TOOL = "What is the weather in Seoul? Use the get_weather tool.";

    /** The captured tool-calling turn, resolved once for the whole class. See the cost note in the class javadoc. */
    private static LlmResponse capturedTurn;

    private static AnthropicConfig.Builder config(String model) {
        return AnthropicConfig.builder().apiKey(System.getenv("ANTHROPIC_KEY")).model(model).maxTokens(2000);
    }

    private static LlmModel minimalEffort() {
        return LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build();
    }

    private static ToolDefinition weatherTool() {
        return ToolDefinition.of("get_weather", "Get the current weather in a city",
                Map.of("type", "object", "additionalProperties", false, "properties",
                        Map.of("city", Map.of("type", "string", "description", "The city")), "required",
                        List.of("city")));
    }

    /**
     * One real {@code [thinking, tool_use]} turn, captured once and shared.
     *
     * <p>
     * Not a {@code @BeforeAll}: three tests in this class need no captured turn at all, and paying for one so that a
     * rejection case can run would be a call spent on nothing.
     */
    private static synchronized LlmResponse capturedTurn() throws Exception {
        if (capturedTurn == null) {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(EXTENDED_MODEL).thinkingMode(AnthropicThinkingMode.EXTENDED).build())) {
                capturedTurn = client.sendMessage(SYSTEM, List.of(Message.user(ASK_TOOL)), List.of(weatherTool()),
                        minimalEffort());
            }
        }
        // Whether the model reaches for the tool is the model's decision, so its absence aborts rather than fails.
        assumeTrue(!capturedTurn.getToolUses().isEmpty(), "model did not call the tool; nothing to anchor a trace to");
        // Past that point the turn is ours: a tool call with no captured trace is a capture bug and must be red.
        assertThat(capturedTurn.getReasoningTraces())
                .as("a tool-calling turn under EXTENDED thinking must yield a reasoning trace").isNotEmpty();
        return capturedTurn;
    }

    /** The turn-two message list an executor would build, carrying whichever traces the caller supplies. */
    private static List<Message> secondTurn(LlmResponse first, List<ReasoningTrace> traces) {
        return List.of(Message.user(ASK_TOOL),
                Message.assistant(first.getTextContent(), first.getToolUses()).withReasoningTraces(traces),
                Message.toolUseResults(first.getToolUses().stream()
                        .map(toolUse -> ToolUseResult.success(toolUse.getId(), "18C, clear")).toList()));
    }

    /** The same trace with one character of its {@code signature} changed, and nothing else touched. */
    private static ReasoningTrace withMutatedSignature(ReasoningTrace trace) throws Exception {
        final ObjectNode payload = (ObjectNode) ObjectMappers.jsonMapper().readTree(trace.getPayload());
        final String signature = payload.get("signature").asText();
        final String mutated = signature.substring(0, signature.length() - 2)
                + (signature.endsWith("AB") ? "CD" : "AB");
        payload.put("signature", mutated);
        return ReasoningTrace.builder().providerName(trace.getProviderName())
                .payload(ObjectMappers.jsonMapper().writeValueAsString(payload))
                .toolUseId(trace.getToolUseId().orElse(null)).build();
    }

    @Nested
    @DisplayName("U-1: the server verifies the replayed signature, and accepts ours")
    class ReplayedSignatureIsAccepted {

        @Test
        @DisplayName("a signature this client re-serialised is accepted")
        void reserialisedSignatureIsAccepted() throws Exception {
            final LlmResponse first = capturedTurn();
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(EXTENDED_MODEL).thinkingMode(AnthropicThinkingMode.EXTENDED).build())) {

                final ReasoningTrace trace = first.getReasoningTraces().get(0);
                assertThat(trace.getProviderName()).isEqualTo("Anthropic");
                assertThat(trace.getToolUseId()).contains(first.getToolUses().get(0).getId());

                // The claim is exactly "not rejected", so that is the whole assertion. Asserting on the answer's
                // content would add a way to go red that has nothing to do with signatures — the model may reach for
                // the tool again rather than replying, which is its choice and not a defect.
                assertThatCode(() -> client.sendMessage(SYSTEM, secondTurn(first, first.getReasoningTraces()),
                        List.of(weatherTool()), minimalEffort())).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("the negative control: one changed character in the signature is rejected")
        void mutatedSignatureIsRejected() throws Exception {
            final LlmResponse first = capturedTurn();
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(EXTENDED_MODEL).thinkingMode(AnthropicThinkingMode.EXTENDED).build())) {

                final List<ReasoningTrace> mutated = List.of(withMutatedSignature(first.getReasoningTraces().get(0)));

                // Without this, the sibling above proves nothing: a stripped turn is *also* accepted
                // (StrippingTheBlock), so "the second call succeeded" is equally satisfied by a client that silently
                // dropped the block. This is what shows the verifier read the signature — and therefore that its
                // accepting ours means something.
                assertThatThrownBy(() -> client.sendMessage(SYSTEM, secondTurn(first, mutated), List.of(weatherTool()),
                        minimalEffort())).isInstanceOf(LlmInvalidRequestException.class)
                        .hasMessageContaining("Invalid `signature` in `thinking` block");
            }
        }
    }

    @Nested
    @DisplayName("U-2: the two dialect rejections, asserted as whole sentences")
    class DialectMismatchesAreRejected {

        @Test
        @DisplayName("EXTENDED against an adaptive-only model")
        void extendedAgainstAdaptiveOnlyModel() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(ADAPTIVE_MODEL).thinkingMode(AnthropicThinkingMode.EXTENDED).build())) {

                // The whole sentence, not the field path. AnthropicThinkingMode's javadoc quotes it verbatim so that
                // an operator can grep the error text and land there, and only an assertion this wide keeps that
                // true.
                assertThatThrownBy(
                        () -> client.sendMessage(SYSTEM, List.of(Message.user("hi")), List.of(), minimalEffort()))
                        .isInstanceOf(LlmInvalidRequestException.class)
                        .hasMessageContaining("\"thinking.type.enabled\" is not supported for this model. "
                                + "Use \"thinking.type.adaptive\" and \"output_config.effort\" "
                                + "to control thinking behavior.");
            }
        }

        @Test
        @DisplayName("ADAPTIVE against an extended-only model")
        void adaptiveAgainstExtendedOnlyModel() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(EXTENDED_MODEL).thinkingMode(AnthropicThinkingMode.ADAPTIVE).build())) {

                assertThatThrownBy(() -> client.sendMessage(SYSTEM, List.of(Message.user("hi")), List.of(),
                        LlmModel.builder().build())).isInstanceOf(LlmInvalidRequestException.class)
                        .hasMessageContaining("adaptive thinking is not supported on this model");
            }
        }
    }

    @Nested
    @DisplayName("The sampling rule that decides which configurations reach an always-on model")
    class AdaptiveReachability {

        @Test
        @DisplayName("ADAPTIVE reaches it, because asking for thinking is what omits temperature")
        void adaptiveReachesAnAlwaysOnModel() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(ADAPTIVE_MODEL).thinkingMode(AnthropicThinkingMode.ADAPTIVE).build())) {

                // "Accepted" is the entire claim, so it is the entire assertion. An earlier revision asserted the
                // answer contained "5", which could go red merely because a model phrased arithmetic differently.
                assertThatCode(() -> client.sendMessage(SYSTEM, List.of(Message.user("What is 2 + 3?")), List.of(),
                        LlmModel.builder().reasoningEffort(ReasoningEffort.MEDIUM).build())).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("OFF reaches it now — the defect this test used to pin, inverted into its regression guard")
        void offReachesAnAlwaysOnModel() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(config(ADAPTIVE_MODEL).build())) {

                // This assertion used to be its own opposite. It read:
                //
                // .isInstanceOf(LlmInvalidRequestException.class)
                // .hasMessageContaining("`temperature` is deprecated for this model")
                //
                // which was true because the config manufactured temperature 0.0 and this model refuses any
                // non-default value. Two changes remove it: the config no longer invents a value, and the capability
                // row would suppress one that had been set. The old sentence is kept here because it is why the test
                // exists — inverting it turns the record of the defect into the guard against its return.
                assertThatCode(() -> client.sendMessage(SYSTEM, List.of(Message.user("What is 2 + 3?")), List.of(),
                        LlmModel.builder().build())).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("an explicitly set temperature is suppressed, the call succeeds, and the operator is told")
        void anExplicitTemperatureIsSuppressedAndReported() throws Exception {
            final Logger clientLogger = (Logger) LoggerFactory.getLogger(AnthropicLlmClient.class);
            final ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            clientLogger.addAppender(appender);
            try (AnthropicLlmClient client = new AnthropicLlmClient(config(ADAPTIVE_MODEL).build())) {

                // The live half of "suppression is reported, not silent". Everything else about that claim is
                // asserted against a serialised body; this is the only place the real server agrees.
                assertThatCode(() -> client.sendMessage(SYSTEM, List.of(Message.user("What is 2 + 3?")), List.of(),
                        LlmModel.builder().temperature(0.7).build())).doesNotThrowAnyException();

                assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage))
                        .anyMatch(message -> message.contains("does not accept sampling parameters")
                                && message.contains(ADAPTIVE_MODEL));
            } finally {
                clientLogger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("the negative control: with the row removed, the same call is the 400 it always was")
        void withoutTheCapabilityRowTheSameCallIsRejected() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(ADAPTIVE_MODEL).modelCapabilityRegistry(ModelCapabilityRegistry.EMPTY).build())) {

                // Without this, "the call succeeded" above is equally satisfied by a client that sends nothing for
                // unrelated reasons — the same asymmetry mutatedSignatureIsRejected exists to close. The exact
                // sentence matters: a bare "temperature" substring would also pass on a range-validation error.
                //
                // This half is rejected at parameter validation, before generation, so it bills nothing.
                assertThatThrownBy(() -> client.sendMessage(SYSTEM, List.of(Message.user("hi")), List.of(),
                        LlmModel.builder().temperature(0.7).build())).isInstanceOf(LlmInvalidRequestException.class)
                        .hasMessageContaining("`temperature` is deprecated for this model");
            }
        }
    }

    @Nested
    @DisplayName("U-8: the thinking token counter is read off a real response")
    class ThinkingTokensAreReported {

        @Test
        @DisplayName("reasoningTokens is populated and is contained within completionTokens")
        void reasoningTokensArePopulated() throws Exception {
            final LlmResponse response = capturedTurn();

            // The field name came from documentation and had never been seen; nothing else in the client fails if it
            // is wrong, because the counter simply reads 0. Containment is §3.6's claim — a breakdown of output
            // rather than an addition to it. An earlier revision also asserted total == prompt + completion, which
            // this client computes that way itself, so it could never have gone red.
            assertThat(response.getTokenUsage().getReasoningTokens()).isPositive();
            assertThat(response.getTokenUsage().getReasoningTokens())
                    .isLessThanOrEqualTo(response.getTokenUsage().getCompletionTokens());
        }
    }

    @Nested
    @DisplayName("U-10: what stripping the thinking block actually does")
    class StrippingTheBlock {

        @Test
        @DisplayName("replayThinkingBlocks(false) is accepted, not rejected — it degrades")
        void strippingDegradesRatherThanRejecting() throws Exception {
            final LlmResponse first = capturedTurn();
            try (AnthropicLlmClient client = new AnthropicLlmClient(config(EXTENDED_MODEL)
                    .thinkingMode(AnthropicThinkingMode.EXTENDED).replayThinkingBlocks(false).build())) {

                // The claim this replaces: that the pair is "documented as incompatible" and a tool loop "is expected
                // to be rejected on its second iteration". It is not. The traces are present on the message and the
                // *client* strips them — asserting on a turn that never carried any would prove nothing, which is why
                // capturedTurn() fails rather than skips when a tool call produced no trace.
                assertThat(first.getReasoningTraces()).isNotEmpty();

                assertThatCode(() -> client.sendMessage(SYSTEM, secondTurn(first, first.getReasoningTraces()),
                        List.of(weatherTool()), minimalEffort())).doesNotThrowAnyException();
            }
        }
    }
}
