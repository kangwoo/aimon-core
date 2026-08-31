package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests that a sampling parameter this provider cannot honour as given is reported at a level an operator sees, exactly
 * once per distinct value.
 *
 * <p>
 * {@link LlmModel} is provider-neutral and accepts the widest range any provider takes, so a value that is legal there
 * can be clamped (temperature) or dropped outright (the two penalties) on its way to Anthropic. Either way the call
 * <em>succeeds</em> — with different settings than were configured — so the log line is the only thing standing between
 * the operator and a silent behaviour change.
 */
@DisplayName("AnthropicLlmClient - provider parameter divergence reporting")
@ExtendWith(MockitoExtension.class)
class AnthropicLlmClientParameterDivergenceTest {

    /** Aborts the SDK call after buildRequest has run, so no valid SDK Message has to be constructed. */
    private static final RuntimeException SENTINEL = new RuntimeException("create-invoked");

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

    private AnthropicLlmClient client() {
        lenient().when(mockAnthropicClient.messages()).thenReturn(mockMessageService);
        AnthropicConfig config = AnthropicConfig.builder().apiKey("test-key").model("claude-sonnet-4-20250514").build();
        return new AnthropicLlmClient(config, mockAnthropicClient);
    }

    private void send(AnthropicLlmClient client, LlmModel model) {
        assertThatThrownBy(() -> client.sendMessage("You are helpful", List.of(Message.user("hi")), List.of(), model))
                .hasRootCause(SENTINEL);
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("a temperature above Anthropic's range is clamped, and the operator is told at WARN")
    void outOfRangeTemperatureIsReported() {
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        AnthropicLlmClient client = client();

        send(client, LlmModel.builder().temperature(1.5).build());

        assertThat(warnings()).singleElement().asString().contains("1.5").contains("[0.0, 1.0]");

        // The clamp itself, not just the warning about it: 1.5 must not reach the API.
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(mockMessageService).create(captor.capture());
        assertThat(captor.getValue().temperature()).contains(1.0);
    }

    @Test
    @DisplayName("the same divergent value is reported once, not once per iteration")
    void repeatedDivergenceIsReportedOnce() {
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        AnthropicLlmClient client = client();
        LlmModel model = LlmModel.builder().temperature(1.5).build();

        send(client, model);
        send(client, model);
        send(client, model);

        assertThat(warnings()).hasSize(1);
    }

    @Test
    @DisplayName("a second agent diverging differently is not silenced by the first")
    void distinctDivergentValuesAreEachReported() {
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        AnthropicLlmClient client = client();

        // One client serves every agent bound to this provider; keying the report on the parameter alone would let
        // whichever agent went first hide the other.
        send(client, LlmModel.builder().temperature(1.5).build());
        send(client, LlmModel.builder().temperature(1.8).build());

        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(1)).contains("1.8");
    }

    @Test
    @DisplayName("penalties Anthropic has no counterpart for are reported at WARN, not swallowed at DEBUG")
    void droppedPenaltiesAreReported() {
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        AnthropicLlmClient client = client();

        send(client, LlmModel.builder().presencePenalty(0.5).frequencyPenalty(-0.5).build());

        assertThat(warnings()).hasSize(2);
        assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("presencePenalty").contains("0.5"));
        assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("frequencyPenalty").contains("-0.5"));
    }

    @Test
    @DisplayName("a config this provider honours as given says nothing")
    void honouredConfigIsSilent() {
        when(mockMessageService.create(any(MessageCreateParams.class))).thenThrow(SENTINEL);
        AnthropicLlmClient client = client();

        send(client, LlmModel.builder().temperature(0.7).topP(0.9).build());

        assertThat(warnings()).isEmpty();
    }
}
