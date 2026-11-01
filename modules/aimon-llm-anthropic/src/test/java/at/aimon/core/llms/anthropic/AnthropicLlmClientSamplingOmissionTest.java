package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * API version 2026-10-21 answers 400 to the sampling parameters on every model, so this client sends none and reports
 * what it dropped.
 *
 * <p>
 * <strong>Absence is asserted on the serialised request body.</strong> {@code "temperature": null} on the wire would be
 * a 400 too, and only {@code _body()} can tell it from a missing key.
 */
@DisplayName("AnthropicLlmClient - sampling parameters are never sent")
@ExtendWith(MockitoExtension.class)
class AnthropicLlmClientSamplingOmissionTest {

    /** Aborts the SDK call once buildRequest has run, so no valid SDK Message has to be constructed. */
    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

    /** In the built-in table as a model that refused sampling even under the previous API version. */
    private static final String REFUSING_MODEL = "claude-opus-5";

    /** Took all three parameters under the previous API version; the new one refuses them here too. */
    private static final String ACCEPTING_MODEL = "claude-opus-4-5-20251101";

    /** A gateway rename nothing describes. */
    private static final String UNDESCRIBED_MODEL = "prod-assistant";

    @Mock
    private AnthropicClient mockAnthropicClient;

    @Mock
    private MessageService mockMessageService;

    private Logger clientLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        clientLogger = (Logger) LoggerFactory.getLogger(AnthropicLlmClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    private AnthropicLlmClient client(AnthropicConfig config) {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        lenient().when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        return new AnthropicLlmClient(config, mockAnthropicClient);
    }

    private static AnthropicConfig.Builder config(String model) {
        return AnthropicConfig.builder().apiKey("test-key").model(model);
    }

    private JsonNode send(AnthropicLlmClient client, LlmModel model) {
        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")), List.of(), model))
                .hasRootCause(SENTINEL);
        final ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService, atLeastOnce()).create(captor.capture());
        return AnthropicFixtures.bodyTreeOf(captor.getValue());
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {REFUSING_MODEL, ACCEPTING_MODEL, UNDESCRIBED_MODEL})
    @DisplayName("no model gets a temperature or top_p key, and each dropped value is named")
    void noModelGetsSampling(String model) {
        final JsonNode body = send(client(config(model).build()),
                LlmModel.builder().temperature(0.7).topP(0.9).build());

        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(warnings()).hasSize(2);
        assertThat(warnings()).anySatisfy(
                w -> assertThat(w).startsWith("temperature 0.7").contains(model).contains("API version 2026-10-21"));
        assertThat(warnings()).anySatisfy(w -> assertThat(w).startsWith("topP 0.9").contains(model));
    }

    @Test
    @DisplayName("a capability row that accepts sampling does not bring the parameters back")
    void aRowCannotReopenSampling() {
        final ModelCapabilityRegistry declared = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .register(UNDESCRIBED_MODEL, ModelCapabilities.builder().supportsSamplingParameters(true).build())
                .build();

        final JsonNode body = send(client(config(UNDESCRIBED_MODEL).modelCapabilityRegistry(declared).build()),
                LlmModel.builder().temperature(0.7).build());

        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    @DisplayName("with thinking on, the omission is what is reported")
    void thinkingDoesNotChangeTheOmission() {
        final JsonNode body = send(
                client(config("claude-sonnet-5").thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().temperature(0.7).topP(0.98).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(warnings()).hasSize(2).allMatch(w -> w.contains("API version 2026-10-21"));
    }

    @Test
    @DisplayName("the request's own temperature wins the report over the client's configured one")
    void theReportNamesTheValueThatWouldHaveApplied() {
        // What `llm.anthropic.temperature` / `aimon.llm.anthropic.temperature` (L-2) still rests on.
        final AnthropicConfig deploymentDefault = config(UNDESCRIBED_MODEL).temperature(0.3).build();

        send(client(deploymentDefault), LlmModel.builder().temperature(0.9).build());
        assertThat(warnings()).singleElement().asString().startsWith("temperature 0.9");

        logAppender.list.clear();
        send(client(deploymentDefault), LlmModel.builder().build());
        assertThat(warnings()).singleElement().asString().startsWith("temperature 0.3");
    }

    @Test
    @DisplayName("a request nobody put a sampling value on is silent")
    void nothingConfiguredIsSilent() {
        final JsonNode body = send(client(config(REFUSING_MODEL).build()), LlmModel.builder().build());

        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a registry that throws degrades to unknown and says so")
    void aThrowingRegistryFailsOpenAndReports() {
        final ModelCapabilityRegistry throwing = modelName -> {
            throw new IllegalStateException("registry is down");
        };
        send(client(config(REFUSING_MODEL).modelCapabilityRegistry(throwing).build()), LlmModel.builder().build());

        assertThat(warnings()).anyMatch(w -> w.contains("failed") && w.contains("registry is down"));
    }

    @Test
    @DisplayName("a registry that returns null degrades to unknown and says so too")
    void aNullResolvingRegistryFailsOpenAndReports() {
        // Reported rather than absorbed: a silent degradation sitting beside a reported one teaches an operator that
        // capability look-ups never fail.
        send(client(config(REFUSING_MODEL).modelCapabilityRegistry(new NullResolvingRegistry()).build()),
                LlmModel.builder().build());

        assertThat(warnings()).anyMatch(w -> w.contains("returned null"));
    }

    @Test
    @DisplayName("the same omission across two sends is reported once")
    void omissionIsReportedOncePerSignature() {
        final AnthropicLlmClient client = client(config(REFUSING_MODEL).build());
        final LlmModel model = LlmModel.builder().temperature(0.7).build();

        send(client, model);
        send(client, model);

        assertThat(warnings()).hasSize(1);
    }

    @Test
    @DisplayName("the same value against two models is said twice, because the signature carries the model")
    void theSignatureCarriesTheModelName() {
        // One client has one config, but LlmModel can override the name, so "temperature 0.7 was dropped" is two
        // pieces of news rather than one repeated.
        final AnthropicLlmClient client = client(config(REFUSING_MODEL).build());

        send(client, LlmModel.builder().temperature(0.7).build());
        send(client, LlmModel.builder().name("claude-sonnet-5").temperature(0.7).build());

        assertThat(warnings()).hasSize(2);
        assertThat(warnings()).anyMatch(w -> w.contains(REFUSING_MODEL)).anyMatch(w -> w.contains("claude-sonnet-5"));
    }
}
