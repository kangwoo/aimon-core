package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.openai.core.JsonMissing;
import com.openai.core.JsonNull;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests that a value this provider cannot put on the wire as given is reported at a level an operator sees, exactly
 * once per distinct value.
 *
 * <p>
 * The call <em>succeeds</em> with settings other than the ones configured — no error, no status code — so the log line
 * is the only thing standing between the operator and a silent behaviour change. Mirrors
 * {@code AnthropicLlmClientParameterDivergenceTest}, which reports the same class of divergence for the same reason.
 */
@DisplayName("OpenAILlmClient - request parameter divergence reporting")
@ExtendWith(MockitoExtension.class)
class OpenAILlmClientParameterDivergenceTest {

    /** Aborts the SDK call after buildRequest has run, so no valid SDK ChatCompletion has to be constructed. */
    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

    private static final ToolDefinition A_TOOL = ToolDefinition.of("Read", "Reads a file",
            Map.of("type", "object", "properties", Map.of()));

    @Mock
    private OpenAIClient mockOpenAIClient;

    @Mock
    private ChatService mockChatService;

    @Mock
    private ChatCompletionService mockChatCompletionService;

    private Logger clientLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        clientLogger = (Logger) LoggerFactory.getLogger(OpenAILlmClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    private OpenAILlmClient client(OpenAIConfig config) {
        lenient().when(mockOpenAIClient.chat()).thenReturn(mockChatService);
        lenient().when(mockChatService.completions()).thenReturn(mockChatCompletionService);
        lenient().when(mockChatCompletionService.create(any(ChatCompletionCreateParams.class))).thenThrow(SENTINEL);
        return new OpenAILlmClient(config, mockOpenAIClient);
    }

    private void send(OpenAILlmClient client, LlmModel model, List<ToolDefinition> tools) {
        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")), tools, model))
                .hasRootCause(SENTINEL);
    }

    /** {@link #send} plus the params the SDK was handed — this class's own helper returns nothing. */
    private ChatCompletionCreateParams sendAndCapture(OpenAILlmClient client, LlmModel model,
            List<ToolDefinition> tools) {
        send(client, model, tools);
        final ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor
                .forClass(ChatCompletionCreateParams.class);
        verify(mockChatCompletionService).create(captor.capture());
        return captor.getValue();
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("a temperature suppressed by capabilities is reported once at WARN")
    void suppressedTemperatureIsReportedOnce() {
        final OpenAILlmClient client = client(OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build());
        final LlmModel model = LlmModel.builder().temperature(0.7).build();

        send(client, model, List.of());
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("temperature").contains("0.7").contains("gpt-5.6-terra")
                .contains("does not accept sampling parameters");

        // Same value again: buildRequest runs on every ReAct iteration, so this must not repeat.
        send(client, model, List.of());
        assertThat(warnings()).hasSize(1);
    }

    @Test
    @DisplayName("the message does not claim an operator set the value")
    void messageDoesNotAssertOperatorIntent() {
        // A subagent turn carries a temperature from SubagentLlmDefaults, not from a human, and nothing here can tell
        // the two apart -- so the wording says only that the value is set on the request.
        final OpenAILlmClient client = client(OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build());

        send(client, LlmModel.builder().temperature(0.7).build(), List.of());

        assertThat(warnings().get(0)).contains("is set on this request").doesNotContain("configured")
                .doesNotContain("you ");
    }

    @Test
    @DisplayName("a temperature nobody configured is neither sent nor reported")
    void unconfiguredTemperatureIsNeitherSentNorReported() {
        // Round 2 reversal, by maintainer ruling: this test was `suppressedFallbackIsSilent`, and it asserted only
        // that suppressing OpenAIConfig.DEFAULT_TEMPERATURE stayed quiet. That fallback was removed deliberately --
        // issue #43's "sampling parameters are sent only when the caller explicitly set them" beats round 1's "an
        // unknown model sends exactly what it sends today", which round 1 had recorded as design O-1/A6. See
        // docs/design/llm/openai-model-capabilities.md section 9.
        //
        // With no fallback the old assertion would be vacuous, so this asserts both halves: still no log noise on
        // every gpt-5 deployment, AND nothing on the wire. Absence is read off the raw field because
        // params.temperature() is empty for a missing field and for an explicit JsonNull alike.
        final OpenAILlmClient client = client(OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build());

        final ChatCompletionCreateParams params = sendAndCapture(client, LlmModel.builder().build(), List.of(A_TOOL));

        assertThat(warnings()).isEmpty();
        assertThat(params._temperature()).isInstanceOf(JsonMissing.class).isNotInstanceOf(JsonNull.class);
    }

    @Test
    @DisplayName("a registry whose resolve() returns null is reported once and the request keeps its shape")
    void nullReturningRegistryIsReportedOnce() {
        // The adjacent throwing branch reports; a silent degradation beside a reported one would teach an operator
        // that capability lookups never fail.
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra")
                .modelCapabilityRegistry(new NullResolvingRegistry()).build();
        final OpenAILlmClient client = client(config);

        send(client, LlmModel.builder().build(), List.of());
        send(client, LlmModel.builder().build(), List.of());

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("Model capability lookup for gpt-5.6-terra returned null")
                .contains("treating the model as unknown");
    }

    /**
     * Overrides {@code resolve()} to break its never-null contract; a lambda can only supply {@code capabilitiesOf}.
     */
    private static final class NullResolvingRegistry implements ModelCapabilityRegistry {
        @Override
        public Optional<ModelCapabilities> capabilitiesOf(String modelName) {
            return Optional.empty();
        }

        @Override
        public ModelCapabilities resolve(String modelName) {
            return null;
        }
    }

    @Test
    @DisplayName("each distinct suppressed parameter is reported separately")
    void eachParameterIsReportedSeparately() {
        final OpenAILlmClient client = client(OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build());

        send(client, LlmModel.builder().temperature(0.7).topP(0.9).presencePenalty(1.0).frequencyPenalty(-1.0).build(),
                List.of());

        assertThat(warnings()).hasSize(4);
        assertThat(warnings()).anyMatch(w -> w.startsWith("topP")).anyMatch(w -> w.startsWith("presencePenalty"))
                .anyMatch(w -> w.startsWith("frequencyPenalty"));
    }

    @Test
    @DisplayName("a reasoning effort clamped to NONE because tools are present is reported")
    void clampedReasoningEffortIsReported() {
        final OpenAILlmClient client = client(OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build());

        send(client, LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of(A_TOOL));

        assertThat(warnings()).anyMatch(
                w -> w.contains("reasoningEffort HIGH") && w.contains("does not accept tools together with reasoning"));
    }

    @Test
    @DisplayName("clamping to NONE is silent when NONE is what was asked for")
    void clampToTheRequestedValueIsSilent() {
        final OpenAILlmClient client = client(OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra").build());

        send(client, LlmModel.builder().reasoningEffort(ReasoningEffort.NONE).build(), List.of(A_TOOL));

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a reasoning effort set for a model that takes none is reported")
    void effortForANonReasoningModelIsReported() {
        final OpenAILlmClient client = client(OpenAIConfig.builder().apiKey("test-key").model("gpt-4o").build());

        send(client, LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build(), List.of());

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("reasoningEffort HIGH").contains("gpt-4o")
                .contains("takes no reasoning-effort parameter");
    }

    @Test
    @DisplayName("a registry that throws is reported once and the request keeps its default shape")
    void brokenRegistryIsReported() {
        final OpenAIConfig config = OpenAIConfig.builder().apiKey("test-key").model("gpt-5.6-terra")
                .modelCapabilityRegistry(modelName -> {
                    throw new IllegalStateException("registry is broken");
                }).build();
        final OpenAILlmClient client = client(config);

        send(client, LlmModel.builder().build(), List.of());
        send(client, LlmModel.builder().build(), List.of());

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("Model capability lookup for gpt-5.6-terra failed")
                .contains("treating the model as unknown");
    }
}
