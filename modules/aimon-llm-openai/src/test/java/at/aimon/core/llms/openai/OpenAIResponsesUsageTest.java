package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseUsage;
import com.openai.services.blocking.ResponseService;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;

/**
 * Acceptance criterion 4 — {@code reasoning_tokens} reaches {@link TokenUsage} — plus every shape in which reading it
 * could fail the call instead.
 *
 * <p>
 * <strong>All five counters are required accessors, not just the reasoning one.</strong> {@code inputTokens()},
 * {@code outputTokens()} and {@code totalTokens()} throw at the top level, {@code outputTokensDetails()} throws beside
 * them, and {@code reasoningTokens()} throws one level down inside that — so a usage document missing any top-level
 * counter throws <em>before</em> the details are ever reached. The rule is <em>accounting degrades, identity
 * throws</em>:
 * a missing counter must not fail a turn that otherwise succeeded.
 *
 * <p>
 * The fixtures are deserialised rather than built, because that is the only way to express a document with a counter
 * missing: the SDK builder's {@code checkRequired} refuses to construct one, while its {@code @JsonCreator}
 * constructor — the path the real wire takes — does not.
 */
@DisplayName("OpenAI Responses - token usage")
@ExtendWith(MockitoExtension.class)
class OpenAIResponsesUsageTest {
    /**
     * A gpt-5-family reasoning model, meaning nothing more than that. It was {@code gpt-5.6-terra} until that name
     * got a built-in row of its own for its measured ladder; a name with its own row would keep every assertion here
     * green while quietly testing a different row from the one they are about.
     */
    private static final String A_REASONING_MODEL = "gpt-5-mini";

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ResponseService mockResponseService;

    private TokenUsage usageOf(String usageJson) {
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        final String usagePart = usageJson == null ? "" : ",\"usage\":" + usageJson;
        when(mockResponseService.create(any(ResponseCreateParams.class))).thenReturn(
                ResponsesFixtures.response("{\"id\":\"r\",\"created_at\":1,\"model\":\"m\",\"object\":\"response\","
                        + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
                        + "\"status\":\"completed\",\"output\":[]" + usagePart + "}"));

        final OpenAILlmClient client = new OpenAILlmClient(
                OpenAIConfig.builder().apiKey("k").model(A_REASONING_MODEL).build(), mockOpenAIClient);
        final LlmResponse response = client.sendMessage("sys", List.of(Message.user("hi")), List.of(),
                LlmModel.builder().build());
        return response.getTokenUsage();
    }

    @Test
    @DisplayName("reasoning_tokens reaches getReasoningTokens and is not added to the total")
    void reasoningTokensReachTheUsage() {
        // reasoning_tokens lives INSIDE output_tokens_details: 30 of the 50 output tokens were reasoning, and
        // total = input + output still holds. They are already billed as output tokens.
        final TokenUsage usage = usageOf("{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":10},"
                + "\"output_tokens\":50,\"output_tokens_details\":{\"reasoning_tokens\":30},\"total_tokens\":150}");

        assertThat(usage).isEqualTo(TokenUsage.of(100, 50, 150, 30));
        assertThat(usage.getReasoningTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("a usage document with no output_tokens_details yields zero and does not throw")
    void missingDetailsObjectDegradesToZero() {
        final TokenUsage usage = usageOf("{\"input_tokens\":100,\"output_tokens\":50,\"total_tokens\":150}");

        assertThat(usage).isEqualTo(TokenUsage.of(100, 50, 150, 0));
    }

    @Test
    @DisplayName("a present-but-empty output_tokens_details yields zero and does not throw")
    void emptyDetailsObjectDegradesToZero() {
        // The second level of the same trap: OutputTokensDetails.reasoningTokens() is also a required accessor, so
        // reading it after asKnown() succeeds on the outer object still throws on a thin inner one.
        final TokenUsage usage = usageOf(
                "{\"input_tokens\":100,\"output_tokens\":50,\"total_tokens\":150," + "\"output_tokens_details\":{}}");

        assertThat(usage).isEqualTo(TokenUsage.of(100, 50, 150, 0));
    }

    @Test
    @DisplayName("a usage document missing a top-level counter still completes the turn")
    void missingTopLevelCounterDegrades() {
        // The shape no earlier revision covered: total_tokens absent. It cannot literally read back as 0 -- TokenUsage
        // enforces total >= prompt + completion and throws otherwise, which would fail the very call this degradation
        // exists to protect -- so the sum is reported and the counters that WERE present survive.
        final TokenUsage usage = usageOf(
                "{\"input_tokens\":100,\"output_tokens\":50," + "\"output_tokens_details\":{\"reasoning_tokens\":30}}");

        assertThat(usage.getPromptTokens()).isEqualTo(100);
        assertThat(usage.getCompletionTokens()).isEqualTo(50);
        assertThat(usage.getReasoningTokens()).isEqualTo(30);
        assertThat(usage.getTotalTokens()).isEqualTo(150);
    }

    @Test
    @DisplayName("an absent usage object yields empty usage")
    void absentUsageYieldsEmpty() {
        assertThat(usageOf(null)).isEqualTo(TokenUsage.empty());
    }

    @Test
    @DisplayName("a counter beyond Integer.MAX_VALUE falls back to empty rather than throwing")
    void oversizedCounterFallsBackToEmpty() {
        // The same narrowing guard the Chat path already has: the SDK reports longs and TokenUsage takes ints.
        final ResponseUsage oversized = ResponsesFixtures.response("{\"id\":\"r\",\"created_at\":1,\"model\":\"m\","
                + "\"object\":\"response\",\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
                + "\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":3000000000,"
                + "\"output_tokens\":1,\"total_tokens\":3000000001}}").usage().orElseThrow();

        assertThat(OpenAiResponseUsages.toTokenUsage(Optional.of(oversized))).isEqualTo(TokenUsage.empty());
    }

    @Test
    @DisplayName("no usage argument at all yields empty usage")
    void emptyOptionalYieldsEmpty() {
        assertThat(OpenAiResponseUsages.toTokenUsage(Optional.empty())).isEqualTo(TokenUsage.empty());
    }

    @Test
    @DisplayName("a Response with no usage at all does not throw when its usage is read")
    void responseWithoutUsageIsSafe() {
        final Response response = ResponsesFixtures
                .response("{\"id\":\"r\",\"created_at\":1,\"model\":\"m\",\"object\":\"response\","
                        + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
                        + "\"status\":\"completed\",\"output\":[]}");

        assertThat(OpenAiResponseUsages.toTokenUsage(response.usage())).isEqualTo(TokenUsage.empty());
    }
}
