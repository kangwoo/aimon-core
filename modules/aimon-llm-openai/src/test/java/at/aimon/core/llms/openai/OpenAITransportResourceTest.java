package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.llms.openai.OpenAICancellationTransportTest.Endpoint;
import at.aimon.core.llms.openai.OpenAICancellationTransportTest.RecordingCancellation;
import at.aimon.core.llms.openai.OpenAICancellationTransportTest.RecordingSink;
import at.aimon.llm.testkit.StallingSseServer;

/**
 * The transport-side half of plan §5 row 6: once a cancelled call has returned, the module's own transport has no
 * call in flight. Separate from {@link OpenAICancellationTransportTest} because the counter it reads exists only on
 * the fixed transport; the server-side half of the row lives there.
 */
@DisplayName("OpenAI - the transport holds no call after a cancellation")
class OpenAITransportResourceTest {

    private static final Duration HANG_GUARD = Duration.ofSeconds(20);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("after a cancelled call returns, inFlightCalls() is 0")
    void noCallInFlightAfterACancellation(Endpoint endpoint) throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(endpoint.afterSomeText(), "");
                OpenAILlmClient client = new OpenAILlmClient(
                        OpenAIConfig.builder().apiKey("test-key").model(endpoint.model()).baseUrl(server.url() + "/v1")
                                .timeout(Duration.ofSeconds(15)).build())) {
            final OpenAIAbortableHttpClient transport = client.transport().orElseThrow();
            final RecordingCancellation cancellation = new RecordingCancellation();
            final RecordingSink sink = new RecordingSink(1);

            final Future<LlmResponse> running = worker
                    .submit(() -> client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                            List.of(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                            LlmStreamingOptions.defaults(), sink, cancellation));
            assertThat(sink.awaitAllText(HANG_GUARD)).isTrue();
            assertThat(transport.inFlightCalls()).as("the call is in flight while it streams").isEqualTo(1);
            cancellation.trip();

            assertThatThrownBy(() -> running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(LlmCallCancelledException.class);
            assertThat(transport.inFlightCalls()).as("the consumer's close released the call").isZero();
            assertThat(server.awaitClientDisconnected(1, Duration.ofSeconds(5))).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("after a whole answer, inFlightCalls() is 0 too")
    void noCallInFlightAfterAWholeAnswer(Endpoint endpoint) throws Exception {
        try (StallingSseServer server = StallingSseServer.complete(endpoint.whole());
                OpenAILlmClient client = new OpenAILlmClient(
                        OpenAIConfig.builder().apiKey("test-key").model(endpoint.model()).baseUrl(server.url() + "/v1")
                                .timeout(Duration.ofSeconds(15)).build())) {

            final LlmResponse response = client.sendMessageStreaming(SystemPromptParts.empty(),
                    List.of(Message.user("hi")), List.of(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                    LlmStreamingOptions.defaults(), new RecordingSink(2), new RecordingCancellation());

            assertThat(response.getTextContent()).isNotEmpty();
            assertThat(client.transport().orElseThrow().inFlightCalls()).isZero();
        }
    }
}
