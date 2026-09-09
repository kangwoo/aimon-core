package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.chat.ChatCompletionService;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Divergence reporting on the Responses path — the gap re-pointing the Chat divergence tests would otherwise open.
 *
 * <p>
 * Six tests in {@code OpenAILlmClientParameterDivergenceTest} moved to {@code responsesApiEnabled(false)} because they
 * assert Chat behaviour, and that left <strong>nothing</strong> asserting that suppression is still reported on the new
 * endpoint. Sampling suppression is deliberately implemented once and shared, precisely so it is; these tests are what
 * make that checkable.
 *
 * <p>
 * The WARN is asserted on {@code OpenAILlmClient}'s own logger, because the reporter is the client's own method — its
 * once-per-signature dedup set lives there and would not survive being moved into a collaborator.
 */
@DisplayName("OpenAI Responses - request parameter divergence reporting")
@ExtendWith(MockitoExtension.class)
class OpenAIResponsesParameterDivergenceTest {
    /**
     * A gpt-5-family reasoning model, meaning nothing more than that. It was {@code gpt-5.6-terra} until that name
     * got a built-in row of its own for its measured ladder; a name with its own row would keep every assertion here
     * green while quietly testing a different row from the one they are about.
     */
    private static final String A_REASONING_MODEL = "gpt-5-mini";

    private static final ToolDefinition A_TOOL = ToolDefinition.of("Read", "Reads a file",
            Map.of("type", "object", "properties", Map.of()));

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ResponseService mockResponseService;

    @Mock
    private ChatService mockChatService;

    @Mock
    private ChatCompletionService mockChatCompletionService;

    private Logger clientLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachAppender() {
        clientLogger = (Logger) LoggerFactory.getLogger(OpenAILlmClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachAppender() {
        clientLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    private OpenAILlmClient client(OpenAIConfig config) {
        lenient().when(mockOpenAIClient.responses()).thenReturn(mockResponseService);
        lenient().when(mockResponseService.create(any(ResponseCreateParams.class))).thenReturn(emptyResponse());
        return new OpenAILlmClient(config, mockOpenAIClient);
    }

    private static Response emptyResponse() {
        return ResponsesFixtures.response("{\"id\":\"r\",\"created_at\":1,\"model\":\"m\",\"object\":\"response\","
                + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],\"status\":\"completed\","
                + "\"output\":[]}");
    }

    private static OpenAIConfig.Builder config() {
        return OpenAIConfig.builder().apiKey("test-key").model(A_REASONING_MODEL);
    }

    private void send(OpenAILlmClient client, LlmModel model, List<ToolDefinition> tools) {
        client.sendMessage("You are helpful", List.of(Message.user("hi")), tools, model);
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("a suppressed sampling parameter is still reported once on the Responses path")
    void suppressedSamplingIsStillReportedOnTheResponsesPath() {
        final OpenAILlmClient client = client(config().build());
        final LlmModel model = LlmModel.builder().temperature(0.7).build();

        send(client, model, List.of());
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("temperature").contains("0.7").contains(A_REASONING_MODEL)
                .contains("does not accept sampling parameters");

        // Same value again: the request is built on every ReAct iteration, so this must not repeat.
        send(client, model, List.of());
        assertThat(warnings()).hasSize(1);
    }

    @Test
    @DisplayName("each suppressed parameter is reported separately on the Responses path")
    void eachSuppressedParameterIsReportedSeparatelyOnTheResponsesPath() {
        final OpenAILlmClient client = client(config().build());

        send(client, LlmModel.builder().temperature(0.7).topP(0.9).presencePenalty(1.0).frequencyPenalty(-1.0).build(),
                List.of());

        assertThat(warnings()).hasSize(4);
        assertThat(warnings()).anyMatch(w -> w.startsWith("topP")).anyMatch(w -> w.startsWith("presencePenalty"))
                .anyMatch(w -> w.startsWith("frequencyPenalty"));
    }

    @Test
    @DisplayName("there is NO clamp warning on this path, and the configured effort reaches the wire with tools")
    void noClampWarningOnTheResponsesPath() {
        // The positive statement of "the NONE clamp is a Chat rule". Copying it onto this endpoint would silently
        // re-disable reasoning and undo the entire round while every other test stayed green -- so this is the one
        // assertion that catches that, and it checks both halves: no warning, and the effort actually sent.
        final OpenAILlmClient client = client(config().build());

        send(client, LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of(A_TOOL));

        assertThat(warnings()).isEmpty();
        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService).create(captor.capture());
        assertThat(ResponsesFixtures.bodyTreeOf(captor.getValue()).get("reasoning").get("effort").asText())
                .isEqualTo("high");
    }

    @Test
    @DisplayName("a model whose capabilities cannot be resolved is never routed to the new endpoint")
    void degradedRegistryRoutesToChatCompletions() {
        // Fail-open, one level above where it was designed. Both degradation paths -- a throwing registry and one
        // that breaks its total contract by returning null -- give ModelCapabilities.unknown(), whose
        // supportsReasoningTraceRoundTrip() is false.
        assertRoutesToChat(config().modelCapabilityRegistry(name -> {
            throw new IllegalStateException("registry exploded");
        }).build());
        assertRoutesToChat(config().modelCapabilityRegistry(new NullResolvingRegistry()).build());
    }

    private void assertRoutesToChat(OpenAIConfig config) {
        final OpenAIClient localClient = org.mockito.Mockito.mock(OpenAIClient.class);
        final ChatService chat = org.mockito.Mockito.mock(ChatService.class);
        final ChatCompletionService completions = org.mockito.Mockito.mock(ChatCompletionService.class);
        final ResponseService responses = org.mockito.Mockito.mock(ResponseService.class);
        lenient().when(localClient.chat()).thenReturn(chat);
        lenient().when(chat.completions()).thenReturn(completions);
        lenient().when(localClient.responses()).thenReturn(responses);
        final RuntimeException sentinel = new RuntimeException("chat-invoked");
        lenient().when(completions.create(any(com.openai.models.chat.completions.ChatCompletionCreateParams.class)))
                .thenThrow(sentinel);

        final OpenAILlmClient client = new OpenAILlmClient(config, localClient);
        assertThatThrownBy(
                () -> client.sendMessage("sys", List.of(Message.user("hi")), List.of(), LlmModel.builder().build()))
                .hasRootCause(sentinel);

        verify(completions).create(any(com.openai.models.chat.completions.ChatCompletionCreateParams.class));
        verify(responses, never()).create(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("a registry that knows nothing keeps even a literal gpt-5 name off the new endpoint")
    void emptyRegistryRoutesToChatCompletions() {
        assertRoutesToChat(config().modelCapabilityRegistry(ModelCapabilityRegistry.EMPTY).build());
    }

    @Test
    @DisplayName("a reasoning effort set for a model that takes none is reported on this path too")
    void effortForANonReasoningModelIsReportedOnTheResponsesPath() {
        // A caller-supplied registry can describe a model that round-trips traces but takes no effort parameter.
        // The message is the shared one, so both endpoints say the same sentence about the same fact.
        final OpenAILlmClient client = client(config()
                .modelCapabilityRegistry(name -> java.util.Optional.of(at.aimon.core.llm.capability.ModelCapabilities
                        .builder().supportsReasoningEffort(false).supportsReasoningTraceRoundTrip(true).build()))
                .build());

        send(client, LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of());

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("takes no reasoning-effort parameter");
        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService).create(captor.capture());
        assertThat(ResponsesFixtures.bodyOf(captor.getValue())).doesNotContain("\"effort\"");
    }

    @Test
    @DisplayName("a penalty the Responses API has no slot for is reported rather than silently dropped")
    void aPenaltyWithNoCounterpartIsReported() {
        // The one divergence this endpoint introduces on its own: it carries temperature and top_p and has NO
        // presence/frequency penalty. On a model that accepts sampling, a configured penalty would otherwise vanish
        // with no error and no log line.
        final OpenAILlmClient client = client(config()
                .modelCapabilityRegistry(name -> java.util.Optional.of(at.aimon.core.llm.capability.ModelCapabilities
                        .builder().supportsSamplingParameters(true).supportsReasoningTraceRoundTrip(true).build()))
                .build());

        send(client, LlmModel.builder().temperature(0.7).presencePenalty(1.0).build(), List.of());

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("presencePenalty").contains("has no such parameter");
        // ...and the parameter it DOES carry still reaches the wire.
        final ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(mockResponseService).create(captor.capture());
        assertThat(ResponsesFixtures.bodyTreeOf(captor.getValue()).get("temperature").asDouble()).isEqualTo(0.7);
    }

    // ---- reasoningSummary reaching the endpoint that has no such parameter ----

    @Test
    @DisplayName("a reasoning summary on a model routed to Chat Completions is reported, not silently inert")
    void reasoningSummaryOnAChatOnlyModelIsReported() {
        // The OpenAI counterpart of the Anthropic "set under OFF" warning, and the case the Responses path cannot
        // see: reasoning.summary is a Responses parameter, so a model that does not do the reasoning round trip goes
        // to Chat Completions and the key reaches nothing at all -- no ask, no channel, and without this line no
        // signal either. Distinct from a model that received the ask and ignored it, which is honestly quiet.
        lenient().when(mockOpenAIClient.chat()).thenReturn(mockChatService);
        lenient().when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        lenient()
                .when(mockChatCompletionService
                        .create(any(com.openai.models.chat.completions.ChatCompletionCreateParams.class)))
                .thenThrow(new RuntimeException("create-invoked"));
        final OpenAILlmClient client = new OpenAILlmClient(OpenAIConfig.builder().apiKey("test-key").model("gpt-4o")
                .reasoningSummary(OpenAiReasoningSummary.DETAILED).build(), mockOpenAIClient);

        assertThatThrownBy(() -> send(client, LlmModel.builder().build(), List.of()))
                .hasRootCauseMessage("create-invoked");

        assertThat(warnings()).anyMatch(
                warning -> warning.contains("reasoningSummary") && warning.contains("not routed to the Responses API"));
    }

    @Test
    @DisplayName("the same key with the Responses API switched off names that switch instead of the model")
    void reasoningSummaryWithResponsesDisabledNamesTheSwitch() {
        // The remedies differ, so the two conditions get different sentences: this one is a switch the operator set,
        // the other needs a different model.
        lenient().when(mockOpenAIClient.chat()).thenReturn(mockChatService);
        lenient().when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        lenient()
                .when(mockChatCompletionService
                        .create(any(com.openai.models.chat.completions.ChatCompletionCreateParams.class)))
                .thenThrow(new RuntimeException("create-invoked"));
        final OpenAILlmClient client = new OpenAILlmClient(
                config().responsesApiEnabled(false).reasoningSummary(OpenAiReasoningSummary.AUTO).build(),
                mockOpenAIClient);

        assertThatThrownBy(() -> send(client, LlmModel.builder().build(), List.of()))
                .hasRootCauseMessage("create-invoked");
        // Said once, however many iterations run: a property of the configuration, not of the traffic.
        assertThatThrownBy(() -> send(client, LlmModel.builder().build(), List.of()))
                .hasRootCauseMessage("create-invoked");

        assertThat(warnings()).filteredOn(warning -> warning.contains("Responses API is disabled")).hasSize(1);
    }

    @Test
    @DisplayName("no reasoning summary configured means no such warning, on either branch")
    void noSummaryConfiguredIsSilent() {
        final OpenAILlmClient client = client(config().build());

        send(client, LlmModel.builder().build(), List.of());

        assertThat(warnings()).noneMatch(warning -> warning.contains("reasoningSummary"));
    }
}
