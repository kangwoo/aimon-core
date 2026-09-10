package at.aimon.core.llms.openai;

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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.core.ObjectMappers;
import com.openai.errors.BadRequestException;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamingOptions;

/**
 * The live half of the Responses reasoning path: the assertions that only a real OpenAI call can make.
 *
 * <p>
 * <strong>Two issues meet here because one endpoint answers both.</strong> #43 says a {@code gpt-5.x} reasoning model
 * rejects every tool-calling request; #71 says the reasoning <em>stream</em> has never been run against a live API.
 * Both are claims about {@code /v1/responses}, and both were until now supported by fixtures that this repository
 * wrote itself — a hand-written event name is green against a hand-written stream whatever the server actually sends.
 *
 * <p>
 * <strong>What a fixture cannot establish, in three parts.</strong> That the endpoint selector routes a reasoning
 * model somewhere the 400 does not happen; that a reasoning item this client parsed, stored and re-serialised is
 * accepted by the server that issued it; and that the event names {@link OpenAIResponsesStreamingMapper} reads are the
 * ones on the wire. The third is the quiet one: a wrong event name does not fail, it produces an empty channel
 * indistinguishable from a model that chose not to think.
 *
 * <p>
 * <strong>The negative controls are the load-bearing half, exactly as in
 * {@code AnthropicThinkingLiveTest}.</strong> "The call succeeded" is equally satisfied by a client that silently
 * dropped what it was supposed to send, so each positive claim here is paired with a request that must be refused:
 * the same reproduction forced back onto Chat Completions, and a reasoning item with its ciphertext corrupted. Both
 * are rejected before generation and so are billed nothing.
 *
 * <p>
 * <strong>Cost.</strong> One tool-calling turn is captured once and shared, so the class makes four billable calls.
 * Where a claim depends on the model <em>choosing</em> to call a tool, the test aborts through {@code assumeTrue}
 * rather than failing — but an empty reasoning channel is not in that category and is red, see
 * {@link ReasoningDeltasArriveOnAStream}.
 *
 * <p>
 * Gated on {@code OPENAI_KEY} like {@link OpenAILlmClientIntegrationTest}, so a keyless CI skips rather than fails. It
 * is a separate class from that one because that one pins ordinary behaviour on {@code gpt-4o-mini} through Chat
 * Completions, and every request here deliberately leaves that endpoint.
 */
@DisplayName("OpenAILlmClient - the Responses reasoning path against the real API")
@EnabledIfEnvironmentVariable(named = "OPENAI_KEY", matches = ".+")
class OpenAIReasoningLiveTest {

    /**
     * #43's model, named in the issue and not a stand-in for the family.
     *
     * <p>
     * It carries an exact capability row rather than the {@code gpt-5} prefix's, and the row is what routes it to
     * {@code /v1/responses}. Swapping in a family member would keep every assertion here green while testing a
     * different row from the one the issue is about.
     */
    private static final String REASONING_MODEL = "gpt-5.6-terra";

    /**
     * #71's model: the cheapest name that still routes to Responses, and the one RD-2 measured summaries on.
     *
     * <p>
     * A different model from the one above on purpose. RD-2 established non-streaming that this name returns
     * {@code summary_text} parts when asked and none when not, so a streaming run against it extends a measurement
     * rather than starting a new one.
     */
    private static final String SUMMARY_MODEL = "gpt-5-mini";

    private static final String SYSTEM = "You are a helpful assistant. Answer in one or two sentences.";

    /**
     * The system prompt of the captured turn, which has to insist on the tool because the answer is a number the
     * model would otherwise simply write out.
     */
    private static final String RECORD_SYSTEM = "You are a careful mathematician. Work the problem out, then report "
            + "the final answer by calling the record_answer tool. Do not answer in prose.";

    /**
     * The captured turn's question, and it is doing two jobs at once.
     *
     * <p>
     * <strong>A tool call alone does not produce a reasoning item.</strong> Measured on 2026-09-10: the obvious
     * captured turn — "What is the weather in Seoul? Use the get_weather tool." — returns
     * {@code output: [function_call]} and {@code reasoning_tokens: 0} on this model. Nothing was dropped; the model
     * decided the question needed no thought, exactly as adaptive thinking does on the other provider. So the turn
     * that anchors a reasoning item has to earn the reasoning <em>and</em> require the tool, which is why the
     * question is arithmetic the model must work through and the tool is the only way to report it. That pairing
     * returns {@code [reasoning, function_call]} with 1484 characters of {@code encrypted_content} for 131 output
     * tokens.
     */
    private static final String ASK_TOOL = "How many trailing zeros does 2026! have when written in base 12? "
            + "Work it out exactly, then record the answer.";

    /** The captured tool-calling turn, resolved once for the whole class. See the cost note in the class javadoc. */
    private static LlmResponse capturedTurn;

    /** Why the capture failed, when it did — so the attempt is made once rather than once per dependent test. */
    private static RuntimeException capturedTurnFailure;

    private static OpenAIConfig.Builder config(String model) {
        return OpenAIConfig.builder().apiKey(System.getenv("OPENAI_KEY")).model(model).maxTokens(2000);
    }

    private static ToolDefinition recordAnswerTool() {
        return ToolDefinition.of("record_answer", "Record the final numeric answer",
                Map.of("type", "object", "additionalProperties", false, "properties",
                        Map.of("value", Map.of("type", "integer", "description", "The answer")), "required",
                        List.of("value")));
    }

    /**
     * One real {@code [reasoning, function_call]} turn on the reasoning model, captured once and shared.
     *
     * <p>
     * Not a {@code @BeforeAll}: the two rejection cases need no turn at all, and paying for one so that a refusal can
     * be asserted would be a call spent on nothing.
     */
    private static synchronized LlmResponse capturedTurn() {
        // A failed capture is remembered and re-thrown rather than retried. Without this the field stays null on the
        // way out and each of the three tests below attempts the call again — three billed captures on exactly the
        // run that is already going wrong, and "captured once and shared" in the javadoc above stops being true in
        // the one case where it costs something.
        if (capturedTurnFailure != null) {
            throw capturedTurnFailure;
        }
        if (capturedTurn == null) {
            try {
                // LOW rather than the server's default: the ladder rung only has to be high enough that the model
                // thinks at all, and every rung above it is output tokens this test does not read.
                capturedTurn = new OpenAILlmClient(config(REASONING_MODEL).build()).sendMessage(RECORD_SYSTEM,
                        List.of(Message.user(ASK_TOOL)), List.of(recordAnswerTool()),
                        LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());
            } catch (RuntimeException e) {
                capturedTurnFailure = e;
                throw e;
            }
        }
        // Whether the model reaches for the tool is the model's decision, so its absence aborts rather than fails.
        assumeTrue(!capturedTurn.getToolUses().isEmpty(), "model did not call the tool; nothing to anchor an item to");
        return capturedTurn;
    }

    /** The turn-two message list an executor would build, carrying whichever traces the caller supplies. */
    private static List<Message> secondTurn(LlmResponse first, List<ReasoningTrace> traces) {
        return List.of(Message.user(ASK_TOOL),
                Message.assistant(first.getTextContent(), first.getToolUses()).withReasoningTraces(traces),
                Message.toolUseResults(first.getToolUses().stream()
                        .map(toolUse -> ToolUseResult.success(toolUse.getId(), "recorded")).toList()));
    }

    /**
     * How many trailing characters of {@code encrypted_content} the corruption helper overwrites. Enough that the
     * server's decrypt cannot succeed by chance, and small enough to leave the payload otherwise itself.
     */
    private static final int CORRUPTED_CHARS = 40;

    /** The same trace with the tail of its {@code encrypted_content} rewritten, and nothing else touched. */
    private static ReasoningTrace withCorruptedCiphertext(ReasoningTrace trace) throws Exception {
        final ObjectNode payload = (ObjectNode) ObjectMappers.jsonMapper().readTree(trace.getPayload());
        // Both preconditions asserted rather than relied upon. The sibling test that checks the field is present is
        // a different method with no ordering guarantee, so without the first line a payload that lost
        // `encrypted_content` fails as a NullPointerException inside a helper; without the second, a payload that
        // kept it but shrank below the corruption width fails as a StringIndexOutOfBoundsException one line later.
        // Same failure shape either way — a stack trace in a helper instead of a sentence naming what was wrong.
        assertThat(payload.has("encrypted_content"))
                .as("the captured item must carry encrypted_content for there to be ciphertext to corrupt").isTrue();
        final String ciphertext = payload.get("encrypted_content").asText();
        assertThat(ciphertext.length())
                .as("encrypted_content must be longer than the %d characters this helper overwrites", CORRUPTED_CHARS)
                .isGreaterThan(CORRUPTED_CHARS);
        payload.put("encrypted_content",
                ciphertext.substring(0, ciphertext.length() - CORRUPTED_CHARS) + "A".repeat(CORRUPTED_CHARS));
        return ReasoningTrace.builder().providerName(trace.getProviderName())
                .payload(ObjectMappers.jsonMapper().writeValueAsString(payload))
                .toolUseId(trace.getToolUseId().orElse(null)).build();
    }

    @Nested
    @DisplayName("#43: the reproduction from the issue, and the 400 it used to produce")
    class TheReproduction {

        @Test
        @DisplayName("the issue's own reproduction is accepted — a non-empty tool list no longer fails")
        void theIssuesOwnReproductionIsAccepted() {
            // Verbatim from #43: a config that sets nothing but the key and the model, a one-line question, any
            // non-empty tool list, and a default LlmModel. The issue's note is that sending `tools` is what trips it,
            // so the prompt is beside the point and is kept trivial to keep the call cheap.
            final OpenAILlmClient client = new OpenAILlmClient(
                    OpenAIConfig.builder().apiKey(System.getenv("OPENAI_KEY")).model(REASONING_MODEL).build());

            final LlmResponse response = client.sendMessage(SYSTEM, List.of(Message.user("What is 2+2?")),
                    List.of(recordAnswerTool()), LlmModel.builder().build());

            assertThat(response).isNotNull();
            // Two of #43's work items, asserted where they are cheapest. Usage that is present at all is the
            // Responses `input_tokens`/`output_tokens` naming having been read; a stop reason that is not UNKNOWN is
            // `status` having been mapped, which the issue lists as its own item because Chat's vocabulary does not
            // cover this endpoint.
            assertThat(response.getTokenUsage().getPromptTokens()).isPositive();
            assertThat(response.getTokenUsage().getCompletionTokens()).isPositive();
            assertThat(response.getStopReason()).isPresent().get().isNotEqualTo(StopReason.UNKNOWN);
        }

        @Test
        @DisplayName("the negative control: the same request on Chat Completions is still the original 400")
        void theSameRequestOnChatCompletionsIsStillTheOriginal400() {
            // Without this, the sibling above proves only that some request succeeded — not that routing is what
            // fixed it. `responsesApiEnabled(false)` is the one switch that puts this model back where the issue
            // found it, and this rejection is the issue's own 400 still being returned.
            //
            // Note what is *not* configured: no temperature, and no reasoning effort. The issue says omitting the
            // effort is not a workaround because the server's default for these models is `medium`, and this
            // rejection is that claim still holding.
            final OpenAILlmClient client = new OpenAILlmClient(
                    config(REASONING_MODEL).responsesApiEnabled(false).build());

            // Not the message. An earlier revision asserted the issue's sentence whole, and no narrower substring would
            // be better: the SDK builds this exception's message as "400: " followed by the server's English sentence,
            // so every part of it — the parameter, the model, the endpoint — is wording OpenAI can change. A rewording
            // would turn this red while the routing it guards is still correct, and a test that goes red for a reason
            // unrelated to its subject teaches the next reader to skip it.
            //
            // The mapper keeps the SDK exception as the cause, and that exception carries the error object's structured
            // fields, which a rewording does not touch. Measured on 2026-09-10, the server fills two of them: `type` is
            // invalid_request_error, and `param` names the refused parameter — which is what separates this refusal
            // from a 400 about anything else in the same request. The model and the endpoint are not asserted: this
            // test sets both itself.
            assertThatThrownBy(() -> client.sendMessage(SYSTEM, List.of(Message.user("What is 2+2?")),
                    List.of(recordAnswerTool()), LlmModel.builder().build()))
                    .isInstanceOf(LlmInvalidRequestException.class).cause()
                    .isInstanceOfSatisfying(BadRequestException.class, error -> {
                        assertThat(error.type()).contains("invalid_request_error");
                        assertThat(error.param()).contains("reasoning_effort");
                    });
        }
    }

    @Nested
    @DisplayName("#43: the reasoning item survives a tool call, and the server verifies it")
    class ReasoningItemRoundTrip {

        @Test
        @DisplayName("the item is captured, anchored to the tool call, and carries encrypted_content")
        void theItemIsCapturedAndAnchoredToTheToolCall() {
            final LlmResponse first = capturedTurn();

            // A tool call with no captured item is the failure #43's phase 2 exists to remove: the reasoning is
            // discarded between turns and the model re-derives it every iteration. Past the assumeTrue above, that
            // is this client's bug and must be red.
            assertThat(first.getReasoningTraces()).as("a tool-calling turn on a reasoning model must yield an item")
                    .isNotEmpty();
            final ReasoningTrace trace = first.getReasoningTraces().get(0);
            assertThat(trace.getProviderName()).isEqualTo("OpenAI");
            // Responses names a tool call `call_id` where Chat names it `id`; the issue lists that as its own work
            // item because the converter assumed the latter. This is the two ends agreeing on one identifier.
            assertThat(trace.getToolUseId()).contains(first.getToolUses().get(0).getId());
            assertThat(trace.getPayload()).contains("encrypted_content");

            // The other half of the stop-reason work item: Responses reports `status` plus
            // `incomplete_details.reason` and knows nothing of Chat's `tool_calls`, so a turn that called a tool has
            // to be derived from the output rather than read off a field. The sibling in TheReproduction covers the
            // turn that did not.
            assertThat(first.getStopReason()).contains(StopReason.TOOL_USE);
        }

        @Test
        @DisplayName("an item this client re-serialised is accepted when replayed on the next turn")
        void theReSerialisedItemIsAccepted() {
            final LlmResponse first = capturedTurn();
            final OpenAILlmClient client = new OpenAILlmClient(config(REASONING_MODEL).build());

            // "Not rejected" is the whole claim, so it is the whole assertion. Asserting on the answer would add a
            // way to go red that has nothing to do with the round trip — the model may reach for the tool again.
            assertThatCode(() -> client.sendMessage(RECORD_SYSTEM, secondTurn(first, first.getReasoningTraces()),
                    List.of(recordAnswerTool()), LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the negative control: corrupted ciphertext is rejected, so the server did decrypt ours")
        void corruptedCiphertextIsRejected() throws Exception {
            final LlmResponse first = capturedTurn();
            final OpenAILlmClient client = new OpenAILlmClient(config(REASONING_MODEL).build());

            final List<ReasoningTrace> corrupted = List.of(withCorruptedCiphertext(first.getReasoningTraces().get(0)));

            // Without this the sibling above proves nothing: a turn carrying no item at all is also accepted, so
            // "the second call succeeded" is equally satisfied by a client that dropped the item on the way out.
            // This is what shows the server consumed the payload rather than tolerating it.
            assertThatThrownBy(() -> client.sendMessage(RECORD_SYSTEM, secondTurn(first, corrupted),
                    List.of(recordAnswerTool()), LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build()))
                    .isInstanceOf(LlmInvalidRequestException.class);
        }
    }

    @Nested
    @DisplayName("#71: the reasoning channel, read off a real stream rather than a fixture")
    class ReasoningDeltasArriveOnAStream {

        /**
         * A problem hard enough that a reasoning model spends tokens on it.
         *
         * <p>
         * The same prompt the Anthropic half uses, for the same reason: an easy question can return no deliberation
         * at all even at {@code effort: high}, and a test that is flaky for that reason is worse than no test. On
         * {@code gpt-5-mini} this pair was measured on 2026-09-10 and produced 611
         * {@code response.reasoning_summary_text.delta} events over 1280 reasoning tokens.
         */
        private static final String DELIBERATION_EARNING_PROMPT = "How many trailing zeros does 2026! have when "
                + "written in base 12? Work it out exactly.";

        @Test
        @DisplayName("reasoning summary deltas reach the sink as REASONING_DELTA chunks, and stay out of the answer")
        void reasoningDeltasReachTheSinkAsReasoningDeltaChunks() {
            final List<LlmStreamChunk> chunks = new ArrayList<>();
            final OpenAILlmClient client = new OpenAILlmClient(
                    config(SUMMARY_MODEL).reasoningSummary(OpenAiReasoningSummary.AUTO).maxTokens(4000).build());

            final LlmResponse response = client.sendMessageStreaming(SystemPromptParts.empty(),
                    List.of(Message.user(DELIBERATION_EARNING_PROMPT)), List.of(),
                    LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), LlmCallMetadata.empty(),
                    LlmStreamingOptions.defaults(), chunks::add);

            // Deliberately an assertion rather than an assumeTrue. An empty channel is indistinguishable from the
            // defect being hunted — a mapper reading an event name the server does not send emits exactly zero of
            // these and raises nothing — so the prompt is chosen to make this assertion safe to make.
            final List<String> reasoning = chunks.stream()
                    .filter(chunk -> chunk.getKind() == LlmStreamChunk.Kind.REASONING_DELTA)
                    .map(chunk -> chunk.getReasoningDelta().orElseThrow()).toList();
            assertThat(reasoning).as("the streamed reasoning channel must not be empty on this prompt").isNotEmpty();

            final String deliberation = String.join("", reasoning);
            assertThat(deliberation).isNotBlank();
            // The privacy invariant on the live path: deliberation is a second channel, not a prefix of the answer.
            assertThat(response.getTextContent()).isNotEmpty().doesNotContain(deliberation);

            // The fourth usage field, off the streaming terminal event. Without it every downstream cost calculation
            // under-counts, which is why #43 lists it as load-bearing; containment is what makes it a breakdown of
            // output rather than an addition to it.
            assertThat(response.getTokenUsage().getReasoningTokens()).isPositive();
            assertThat(response.getTokenUsage().getReasoningTokens())
                    .isLessThanOrEqualTo(response.getTokenUsage().getCompletionTokens());
            assertThat(response.getStopReason()).contains(StopReason.END_TURN);
        }
    }
}
