package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.chat.ChatCompletionService;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;

/**
 * Binds which endpoint a request goes to, and — for cases 5 and 6 — that the decision follows the
 * <strong>per-request</strong> model name rather than the client's configured one.
 *
 * <p>
 * <strong>Cases 7 and 8 are a pair and neither stands alone.</strong> Round 8 measured reasoning-item replay for
 * eight o-series names and flipped only those, so the o-series is the first family the built-in table splits: 7 pins
 * that a measured name reaches {@code /v1/responses}, 8 that a prefix sibling nobody was allowed to call does not.
 * A later change that "simplifies" the eight exact rows into three prefix flips passes 7 and fails 8, which is the
 * whole reason 8 exists.
 *
 * <p>
 * <strong>Why the per-request cases exist.</strong> {@code OpenAILlmClientModelCapabilityTest}'s
 * {@code perRequestModelNameSelectsCapabilities} binds the per-request name to the <em>capability lookup</em>, on the
 * Chat path. Nothing implies the other half. An implementation resolving the endpoint from {@code config.getModel()}
 * instead of {@code modelConfig.getName().orElse(config.getModel())} would pass every test in that class while
 * breaking the exact property the single-client design was chosen for — a {@code gpt-4o} compaction call inside a
 * {@code gpt-5.x} session is the ordinary case, not an exotic one.
 *
 * <p>
 * <strong>How case 5 fails against that implementation.</strong> Its config model is {@code gpt-4o} and its request
 * model is {@code gpt-5.6-terra}. The correct resolution finds the built-in {@code gpt-5} prefix row, whose
 * {@code supportsReasoningTraceRoundTrip()} is true, and the predicate holds; the wrong one resolves {@code gpt-4o},
 * finds no row, degrades to {@code ModelCapabilities.unknown()} whose flag is false, and the predicate fails. So the
 * wrong implementation calls {@code chat()}, and both assertions go red — "Wanted but not invoked" on the responses
 * verify, and "Never wanted here, but invoked" on the paired chat {@code never()}.
 *
 * <p>
 * Case 6 is the mirror and is not redundant: it fails a <em>different</em> wrong implementation — one that routes to
 * Responses when <em>either</em> name is reasoning-capable, which passes case 5 and fails only this.
 *
 * <p>
 * <strong>Both services are stubbed in every test, and both throw the same sentinel.</strong> If only the expected one
 * were stubbed, the wrong implementation would call the other, get {@code null} back from the unstubbed accessor, and
 * go red on an NPE — red for the right bug but naming a null mock, and indistinguishable from a test that merely
 * forgot a stub. With both stubbed, the only thing the pair can fail on is which service was called.
 */
@DisplayName("OpenAILlmClient - endpoint selection")
@ExtendWith(MockitoExtension.class)
class OpenAILlmClientEndpointSelectionTest {

    /** Aborts each SDK call after the endpoint has been chosen, so no valid SDK response has to be constructed. */
    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ChatService mockChatService;

    @Mock
    private ChatCompletionService mockChatCompletionService;

    @Mock
    private ResponseService mockResponseService;

    private OpenAILlmClient clientFor(OpenAIConfig config) {
        lenient().when(mockOpenAIClient.chat()).thenReturn(mockChatService);
        lenient().when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        lenient().when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class))).thenThrow(SENTINEL);
        lenient().when(mockResponseService.create(any(ResponseCreateParams.class))).thenThrow(SENTINEL);
        return new OpenAILlmClient(config, mockOpenAIClient);
    }

    private static OpenAIConfig.Builder config(String model) {
        return OpenAIConfig.builder().apiKey("test-key").model(model);
    }

    private void send(OpenAIConfig config, LlmModel model) {
        final OpenAILlmClient client = clientFor(config);
        assertThatThrownBy(() -> client.sendMessage("sys", List.of(Message.user("hi")), List.of(), model))
                .hasRootCause(SENTINEL);
    }

    private ResponseCreateParams assertWentToResponses() {
        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService).create(captor.capture());
        // The parameter type is named because ResponseService declares four create(...) overloads; a bare any() would
        // be ambiguous across them and would not pin the one that matters.
        verify(mockChatCompletionService, never()).create(any(ChatCompletionCreateParams.class));
        return captor.getValue();
    }

    private ChatCompletionCreateParams assertWentToChat() {
        final ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor
                .forClass(ChatCompletionCreateParams.class);
        verify(mockChatCompletionService).create(captor.capture());
        verify(mockResponseService, never()).create(any(ResponseCreateParams.class));
        return captor.getValue();
    }

    @Test
    @DisplayName("1: a STOCK config on a reasoning model uses the Responses API")
    void stockConfigOnAReasoningModelUsesResponses() {
        // The shipped-default binding, inherited from what used to be
        // OpenAILlmClientModelCapabilityTest.stockConfigFixesTheReportedFourHundred: nothing overridden, no registry
        // passed. This is the test that fails if OpenAIConfig's default registry is wired to EMPTY, or if the gpt-5
        // row loses its traceRoundTrip flag.
        send(config("gpt-5.6-terra").build(), LlmModel.builder().build());

        Assertions.assertThat(assertWentToResponses().model()).isPresent();
    }

    @Test
    @DisplayName("2: a non-reasoning model stays on Chat Completions")
    void aNonReasoningModelStaysOnChatCompletions() {
        send(config("gpt-4o").build(), LlmModel.builder().build());

        assertWentToChat();
    }

    @Test
    @DisplayName("3: an EMPTY registry keeps even a literal gpt-5 name on Chat Completions")
    void anEmptyRegistryKeepsEvenALiteralGpt5OnChat() {
        // Round 1's acceptance criterion 4, restated for the endpoint: the client holds no model-name knowledge of
        // its own. If it did, this request would be routed by the name despite the registry knowing nothing.
        send(config("gpt-5.6-terra").modelCapabilityRegistry(ModelCapabilityRegistry.EMPTY).build(),
                LlmModel.builder().build());

        assertWentToChat();
    }

    @Test
    @DisplayName("4: responsesApiEnabled(false) keeps a reasoning model on Chat Completions")
    void responsesApiDisabledKeepsAReasoningModelOnChat() {
        // The escape hatch for a gateway that implements only /v1/chat/completions while passing real model names
        // through -- a deployment that works today and that this branch would otherwise 404.
        send(config("gpt-5.6-terra").responsesApiEnabled(false).build(), LlmModel.builder().build());

        assertWentToChat();
    }

    @Test
    @DisplayName("5: the per-request model name, not the config's, selects the ENDPOINT")
    void perRequestModelNameSelectsTheEndpoint() {
        send(config("gpt-4o").build(), LlmModel.builder().name("gpt-5.6-terra").build());

        Assertions.assertThat(assertWentToResponses().model().orElseThrow().asString()).isEqualTo("gpt-5.6-terra");
    }

    @Test
    @DisplayName("6: a per-request non-reasoning name sends a reasoning session's call back to Chat")
    void perRequestModelNameAlsoSendsAReasoningSessionBackToChat() {
        // The compaction case, as an assertion: a gpt-4o summarization call inside a gpt-5.6 session. Fails an
        // implementation that routes to Responses when EITHER name is reasoning-capable -- which passes case 5.
        send(config("gpt-5.6-terra").build(), LlmModel.builder().name("gpt-4o").build());

        Assertions.assertThat(assertWentToChat().model().asString()).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("7: a STOCK config on a MEASURED o-series name uses the Responses API")
    void stockConfigOnAMeasuredOSeriesNameUsesResponses() {
        // The behaviour change round 8 shipped, at the wire. o4-mini's reasoning-item replay was measured on
        // 2026-09-09 (200, turn completed, corrupted payload 400s), so its exact row carries
        // supportsReasoningTraceRoundTrip=true and a stock config now reaches the endpoint where reasoning survives
        // a tool call. Fails if the eight exact rows are removed or their flag reverted.
        send(config("o4-mini").build(), LlmModel.builder().build());

        Assertions.assertThat(assertWentToResponses().model().orElseThrow().asString()).isEqualTo("o4-mini");
    }

    @Test
    @DisplayName("8: a STOCK config on an UNMEASURED prefix sibling stays on Chat Completions")
    void stockConfigOnAnUnmeasuredOSeriesSiblingStaysOnChat() {
        // The boundary of case 7, and the reason the table has eight exact rows rather than three prefix flips.
        // o1-pro shares the o1 prefix with a measured model and was never called -- the probe's cost rules forbade
        // it -- so it falls to the prefix row, keeps false, and its behaviour is byte-identical to before round 8.
        // This is the case that goes red if somebody later flips the prefix rows instead.
        send(config("o1-pro").build(), LlmModel.builder().build());

        Assertions.assertThat(assertWentToChat().model().asString()).isEqualTo("o1-pro");
    }

    @Test
    @DisplayName("a Chat request is byte-identical whether or not the message carries reasoning traces")
    void chatCompletionsParamsAreUnchangedWhenAMessageCarriesTraces() {
        // Acceptance criterion 8, at the wire. A trace is meaningless to Chat Completions and must not leak into its
        // request in any form -- not as an item, not as a field, not as a changed byte anywhere.
        final Message plain = Message.assistant("thinking");
        final Message withTraces = plain.withReasoningTraces(List.of(at.aimon.core.llm.ReasoningTrace.builder()
                .providerName("OpenAI").payload("{\"type\":\"reasoning\",\"encrypted_content\":\"ZZZ\"}").build()));

        final ChatCompletionCreateParams withoutTraces = captureChat(plain);
        final ChatCompletionCreateParams withTracesParams = captureChat(withTraces);

        Assertions.assertThat(withTracesParams._body().toString()).isEqualTo(withoutTraces._body().toString());
        Assertions.assertThat(withTracesParams._body().toString()).doesNotContain("ZZZ");
    }

    private ChatCompletionCreateParams captureChat(Message message) {
        final OpenAIClient localClient = org.mockito.Mockito.mock(OpenAIClient.class);
        final ChatService chatService = org.mockito.Mockito.mock(ChatService.class);
        final ChatCompletionService completions = org.mockito.Mockito.mock(ChatCompletionService.class);
        lenient().when(localClient.chat()).thenReturn(chatService);
        lenient().when(chatService.completions()).thenReturn(completions);
        lenient().when(completions.create(any(ChatCompletionCreateParams.class))).thenThrow(SENTINEL);

        final OpenAILlmClient client = new OpenAILlmClient(config("gpt-4o").build(), localClient);
        assertThatThrownBy(() -> client.sendMessage("sys", List.of(message), List.of(), LlmModel.builder().build()))
                .hasRootCause(SENTINEL);

        final ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor
                .forClass(ChatCompletionCreateParams.class);
        verify(completions).create(captor.capture());
        return captor.getValue();
    }
}
