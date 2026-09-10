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
 * Tests that the sampling parameters this client sends are decided by the resolved {@code ModelCapabilities} and by
 * nothing else it knows about the model name.
 *
 * <p>
 * <strong>Absence is asserted on the serialised request body, never on {@code params.temperature().isEmpty()}.</strong>
 * The SDK's {@code getOptional} collapses "missing" and "explicit null", so an implementation that put
 * {@code "temperature": null} on the wire would earn exactly the HTTP 400 this branch removes — measured 2026-09-09,
 * on an accepting model as well as a refusing one — and still pass an {@code isEmpty()} assertion. {@code _body()} is
 * the surface that cannot lie.
 *
 * <p>
 * The model names are load-bearing. {@code claude-opus-5} and {@code claude-sonnet-5} are in the built-in table as
 * refusers; {@code claude-opus-4-5-20251101} is measured to accept all three parameters; {@code prod-assistant}
 * stands for a gateway rename nothing describes.
 *
 * <p>
 * <strong>The accepting model gained a row on 2026-09-10 and these assertions are what proves the row withholds
 * nothing.</strong> It used to be described here as "deliberately not in the table", because while a row could only
 * be a pair of facts, a row for it would have suppressed a parameter it takes. The dialect census gave it a
 * prefix that states a {@link at.aimon.core.llm.capability.ThinkingDialect} and <em>nothing else</em> — so
 * {@code supportsSamplingParameters()} stays at its fail-open {@code true} and every assertion below is unchanged.
 * If that ever stops being true, this class is where it shows.
 */
@DisplayName("AnthropicLlmClient - per-model sampling capabilities")
@ExtendWith(MockitoExtension.class)
class AnthropicLlmClientSamplingCapabilityTest {

    /** Aborts the SDK call once buildRequest has run, so no valid SDK Message has to be constructed. */
    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

    /** In the built-in table: refuses temperature at any non-default value and top_p at any value. */
    private static final String REFUSING_MODEL = "claude-opus-5";

    /**
     * Measured to accept all three parameters with thinking off. Described by a dialect-only row since 2026-09-10,
     * which is why these assertions still hold — see the class javadoc.
     */
    private static final String ACCEPTING_MODEL = "claude-opus-4-5-20251101";

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

    @Test
    @DisplayName("a refusing model gets no temperature key at all, and the dropped value is named")
    void refusingModelDropsTemperature() {
        final JsonNode body = send(client(config(REFUSING_MODEL).build()), LlmModel.builder().temperature(0.7).build());

        // Not "temperature is null" — the key is absent. A null is refused the same way a value is, and on this
        // vendor that was measured on an accepting model too, so it is a schema rule rather than a capability one.
        assertThat(body.has("temperature")).isFalse();
        assertThat(warnings()).singleElement().asString().contains("temperature").contains("0.7")
                .contains(REFUSING_MODEL).contains("does not accept sampling parameters");
    }

    @Test
    @DisplayName("a refusing model gets no top_p key either, at a value the older rule would have allowed")
    void refusingModelDropsTopP() {
        final JsonNode body = send(client(config(REFUSING_MODEL).build()), LlmModel.builder().topP(0.9).build());

        assertThat(body.has("top_p")).isFalse();
        assertThat(warnings()).singleElement().asString().contains("topP").contains("0.9").contains(REFUSING_MODEL);
    }

    @Test
    @DisplayName("the capability gate is outside the thinking window, so top_p inside [0.95, 1.0] is still dropped")
    void capabilityGateBeatsTheThinkingWindow() {
        // The one live 400 a temperature-only fix would leave behind. 0.98 is inside the window Anthropic accepts
        // alongside thinking, so the inner gate would send it — and this model refuses top_p at any value including
        // 1.0. Gate the window first and this request is a measured rejection.
        final JsonNode body = send(
                client(config("claude-sonnet-5").thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().topP(0.98).build());

        assertThat(body.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(body.has("top_p")).isFalse();
        assertThat(warnings()).singleElement().asString().contains("does not accept sampling parameters");
    }

    @Test
    @DisplayName("on a refusing model with thinking on, the suppression is what is reported")
    void suppressionBeatsTheThinkingWordingOnARefusingModel() {
        // Both sentences are true there. This one is the useful half: turning thinking off would not make the model
        // take the value, so the thinking wording would send an operator to a remedy that does not work.
        send(client(config("claude-sonnet-5").thinkingMode(AnthropicThinkingMode.ADAPTIVE).build()),
                LlmModel.builder().temperature(0.7).build());

        assertThat(warnings()).singleElement().asString().contains("does not accept sampling parameters");
        assertThat(warnings()).noneMatch(w -> w.contains("incompatible with Anthropic thinking"));
    }

    @Test
    @DisplayName("a measured-accepting model keeps its configured temperature and top_p, silently — no row needed")
    void acceptingModelKeepsItsTemperature() {
        // Fail-open, on the half of it that is a decision rather than an absence: this model was measured to accept
        // all three parameters and is deliberately left out of the table, so what carries it is the same unknown()
        // path unknownModelKeepsItsTemperature exercises. The name is here because "no row" is the right answer for
        // this model, and a future round that adds a row for it would have to argue with this case.
        final JsonNode body = send(client(config(ACCEPTING_MODEL).build()),
                LlmModel.builder().temperature(0.7).topP(0.9).build());

        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.get("top_p").asDouble()).isEqualTo(0.9);
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a model no registry describes keeps today's behaviour - fail open")
    void unknownModelKeepsItsTemperature() {
        // A gateway rename. The price of never guessing is that this deployment still hits whatever the built-in row
        // would have fixed, and the remedy is one declaration — not a guess made here.
        final JsonNode body = send(client(config("prod-assistant").build()),
                LlmModel.builder().temperature(0.7).build());

        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a declaration closes the fail-open gap for a renamed refusing deployment")
    void aDeclaredRenameIsSuppressed() {
        // The one line the package's own javadoc calls the whole remedy, exercised on this side of the seam.
        final ModelCapabilityRegistry declared = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .register("prod-assistant", ModelCapabilities.builder().supportsSamplingParameters(false).build())
                .build();

        final JsonNode body = send(client(config("prod-assistant").modelCapabilityRegistry(declared).build()),
                LlmModel.builder().temperature(0.7).build());

        assertThat(body.has("temperature")).isFalse();
        assertThat(warnings()).singleElement().asString().contains("prod-assistant");
    }

    @Test
    @DisplayName("the client's own configured temperature is suppressed too, and named as the client's")
    void aClientLevelTemperatureIsAlsoSuppressed() {
        final JsonNode body = send(client(config(REFUSING_MODEL).temperature(0.4).build()), LlmModel.builder().build());

        assertThat(body.has("temperature")).isFalse();
        assertThat(warnings()).singleElement().asString().contains("0.4").contains(REFUSING_MODEL);
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
        final JsonNode body = send(client(config(REFUSING_MODEL).modelCapabilityRegistry(throwing).build()),
                LlmModel.builder().temperature(0.7).build());

        // Fail open: the request keeps its default shape rather than the call failing on a third-party bug.
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(warnings()).anyMatch(w -> w.contains("failed") && w.contains("registry is down"));
    }

    @Test
    @DisplayName("a registry that returns null degrades to unknown and says so too")
    void aNullResolvingRegistryFailsOpenAndReports() {
        // Reported rather than absorbed: a silent degradation sitting beside a reported one teaches an operator that
        // capability look-ups never fail.
        final JsonNode body = send(
                client(config(REFUSING_MODEL).modelCapabilityRegistry(new NullResolvingRegistry()).build()),
                LlmModel.builder().temperature(0.7).build());

        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(warnings()).anyMatch(w -> w.contains("returned null"));
    }

    @Test
    @DisplayName("the same suppression across two sends is reported once")
    void suppressionIsReportedOncePerSignature() {
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
