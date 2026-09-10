package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
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
 * needs one, so the class makes roughly eight billable calls rather than one per test — a rejected request is not
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

    /**
     * Adaptive-only as well, and the model the streamed reasoning channel was measured on.
     *
     * <p>
     * Not {@link #ADAPTIVE_MODEL}, and the difference is measurement rather than preference: whether adaptive
     * thinking deliberates at all is decided per request, and the pair (this model, that prompt) is the one that was
     * seen to produce thinking deltas. See {@link ReasoningDeltasArriveOnAStream}'s prompt constant.
     */
    private static final String REASONING_STREAM_MODEL = "claude-opus-5";

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
                //
                // Not the whole sentence — and here that is not a preference but the only assertion that holds. The
                // server answers this one request with either of two 400s, chosen non-deterministically (3 and 3 in
                // six back-to-back runs on 2026-09-10):
                //
                // messages.1.content.0: Invalid `signature` in `thinking` block
                // messages.1.content.0: `thinking` or `redacted_thinking` blocks in the latest assistant message
                // cannot be modified. These blocks must remain as they were in the original response.
                //
                // Both are the verifier saying it noticed, which is the entire claim, so pinning either sentence
                // makes the test red half the time for a reason that has nothing to do with signatures. What is
                // asserted instead is what both bodies share, and each part earns its place by what it separates.
                // `invalid_request_error` is the error's type field, not its wording. `messages.1.content.0`, the
                // path of the block this test mutated, is wording, but the one piece both bodies carry, and it is
                // what tells this rejection apart from a 400 about any other part of the request. It is still the
                // server's text: a server that stopped prefixing its messages with the path would turn this red, and
                // the failure would print the message that shows it. The whole-sentence convention this class
                // observes at DialectMismatchesAreRejected is untouched — it covers the two dialect rejections that
                // AnthropicThinkingMode's javadoc quotes for operators to grep, and neither of these is one of them.
                assertThatThrownBy(() -> client.sendMessage(SYSTEM, secondTurn(first, mutated), List.of(weatherTool()),
                        minimalEffort())).isInstanceOf(LlmInvalidRequestException.class)
                        .hasMessageContaining("invalid_request_error").hasMessageContaining("messages.1.content.0");
            }
        }
    }

    @Nested
    @DisplayName("U-2: the two dialect rejections, asserted as whole sentences")
    class DialectMismatchesAreRejected {

        /**
         * <strong>Both tests here suppress the capability table, and that is the whole point of the nested
         * class.</strong>
         *
         * <p>
         * What is under test is the <em>server's</em> two rejection sentences — {@code AnthropicThinkingMode}'s
         * javadoc quotes them verbatim so an operator can grep an error and land there, and only a live call can say
         * they are still the sentences. The client's dialect translation exists precisely to stop a request in this
         * shape from ever being sent, so with the table in force there is nothing to reject: {@code EXTENDED} against
         * an adaptive-only model comes out adaptive and succeeds.
         *
         * <p>
         * This class was red on the {@code EXTENDED} half from the day the table shipped and nothing noticed, because
         * these tests are gated on a key that CI does not have. The 2026-09-10 dialect census would have turned the
         * {@code ADAPTIVE} half red the same way, by giving {@code claude-haiku-4-5} a budgeted row. Removing the
         * table restores what each test always meant to measure — and it is the instrument the sibling class already
         * uses at {@code AdaptiveReachability}'s negative control.
         */
        private AnthropicConfig.Builder withoutTheTable(String model) {
            return config(model).modelCapabilityRegistry(ModelCapabilityRegistry.EMPTY);
        }

        @Test
        @DisplayName("EXTENDED against an adaptive-only model")
        void extendedAgainstAdaptiveOnlyModel() throws Exception {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    withoutTheTable(ADAPTIVE_MODEL).thinkingMode(AnthropicThinkingMode.EXTENDED).build())) {

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
                    withoutTheTable(EXTENDED_MODEL).thinkingMode(AnthropicThinkingMode.ADAPTIVE).build())) {

                assertThatThrownBy(() -> client.sendMessage(SYSTEM, List.of(Message.user("hi")), List.of(),
                        LlmModel.builder().build())).isInstanceOf(LlmInvalidRequestException.class)
                        .hasMessageContaining("adaptive thinking is not supported on this model");
            }
        }

        @Test
        @DisplayName("with the table in force, neither request is sent in the shape that earns the rejection")
        void theTableIsWhatStopsBothOfThoseRequests() throws Exception {
            // The other half of the pair, and the reason the two above had to have the table removed. Same models,
            // same modes, table in force: the translation turns each into the shape its model accepts. Without this
            // test, "we removed the table to make them fail" would be indistinguishable from "we removed the table
            // to make them pass".
            try (AnthropicLlmClient extended = new AnthropicLlmClient(
                    config(ADAPTIVE_MODEL).thinkingMode(AnthropicThinkingMode.EXTENDED).build());
                    AnthropicLlmClient adaptive = new AnthropicLlmClient(
                            config(EXTENDED_MODEL).thinkingMode(AnthropicThinkingMode.ADAPTIVE).build())) {

                assertThatCode(
                        () -> extended.sendMessage(SYSTEM, List.of(Message.user("hi")), List.of(), minimalEffort()))
                        .doesNotThrowAnyException();
                assertThatCode(() -> adaptive.sendMessage(SYSTEM, List.of(Message.user("hi")), List.of(),
                        LlmModel.builder().build())).doesNotThrowAnyException();
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

    @Nested
    @DisplayName("#71: the reasoning channel, read off a real stream rather than a fixture")
    class ReasoningDeltasArriveOnAStream {

        /**
         * A problem hard enough that adaptive thinking chooses to think about it.
         *
         * <p>
         * <strong>The prompt and the model are one measurement, and neither half may be swapped without redoing
         * it.</strong> Adaptive decides per request whether to deliberate at all, so an easy question returns zero
         * thinking blocks even at {@code effort: high} with a display asked for — {@code 17 * 23} and a
         * three-variable word problem both came back empty. This pair was measured on 2026-09-10 and produced 34
         * {@code thinking_delta} events plus one {@code signature_delta}.
         */
        private static final String DELIBERATION_EARNING_PROMPT = "How many trailing zeros does 2026! have when "
                + "written in base 12? Work it out exactly.";

        private List<String> reasoningDeltasOf(List<LlmStreamChunk> chunks) {
            return chunks.stream().filter(chunk -> chunk.getKind() == LlmStreamChunk.Kind.REASONING_DELTA)
                    .map(chunk -> chunk.getReasoningDelta().orElseThrow()).toList();
        }

        @Test
        @DisplayName("thinking deltas reach the sink as REASONING_DELTA chunks, and stay out of the answer")
        void thinkingDeltasReachTheSinkAsReasoningDeltaChunks() throws Exception {
            final List<LlmStreamChunk> chunks = new ArrayList<>();
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config(REASONING_STREAM_MODEL).thinkingMode(AnthropicThinkingMode.ADAPTIVE)
                            .thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED).build())) {

                final LlmResponse response = client.sendMessageStreaming(SystemPromptParts.empty(),
                        List.of(Message.user(DELIBERATION_EARNING_PROMPT)), List.of(),
                        LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), LlmCallMetadata.empty(),
                        LlmStreamingOptions.defaults(), chunks::add);

                // Deliberately an assertion rather than an assumeTrue, which is the opposite of the rule the
                // captured turn above follows. There, no tool call is the model declining to set the test up; here,
                // an empty reasoning channel is indistinguishable from the defect this test exists to find — a
                // mapper reading an event name the server does not send produces exactly zero of these and no error.
                // The prompt is what makes the assertion safe to make.
                final List<String> reasoning = reasoningDeltasOf(chunks);
                assertThat(reasoning).as("the streamed reasoning channel must not be empty on this prompt")
                        .isNotEmpty();
                final String deliberation = String.join("", reasoning);
                assertThat(deliberation).isNotBlank();

                // The privacy invariant, on the live path: deliberation is a second channel, not a prefix of the
                // answer. Everything else that pins it is a fixture test over a hand-written event stream.
                assertThat(response.getTextContent()).isNotEmpty().doesNotContain(deliberation);

                // Opening the display gate must not cost the round trip. The trace here is unanchored — the turn is
                // [thinking, text] with no tool use — and it still has to arrive signed, or the next turn re-derives.
                assertThat(response.getReasoningTraces()).isNotEmpty();
                assertThat(ObjectMappers.jsonMapper().readTree(response.getReasoningTraces().get(0).getPayload())
                        .get("signature").asText()).isNotEmpty();

                // U-8's counter again, but off the other code path: the blocking half reads Usage, this one reads
                // the final message_delta's MessageDeltaUsage, and only a real stream carries that event.
                assertThat(response.getTokenUsage().getReasoningTokens()).isPositive();
                assertThat(response.getTokenUsage().getReasoningTokens())
                        .isLessThanOrEqualTo(response.getTokenUsage().getCompletionTokens());
            }
        }
    }
}
