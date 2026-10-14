package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.llms.anthropic.CountingHttpServer.Step;

/**
 * {@link AnthropicConfig#getMaxRetries()} over the real transport: what the SDK underneath the client actually sends,
 * read as the number of requests a local server received.
 *
 * <p>
 * The default-policy cases pin the SDK version this module ships (two retries in 2.65.0). They are a regression check
 * for "unset leaves the SDK alone", not a promise about the count — when an SDK upgrade changes it, the expectation
 * here moves with it.
 */
@DisplayName("AnthropicLlmClient - SDK retries (maxRetries)")
class AnthropicLlmClientSdkRetryTest {

    /** The SDK's own default in the version this module ships. */
    private static final int SDK_DEFAULT_RETRIES = 2;

    private static final Step OVERLOADED = Step.json(529,
            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}");
    private static final Step RATE_LIMITED = Step.json(429,
            "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
    private static final Step BAD_REQUEST = Step.json(400,
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}");
    private static final Step SUCCESS = Step.json(200,
            "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-x\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
                    + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}");
    private static final String PARTIAL_STREAM = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{"
            + "\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-x\",\"content\":[],"
            + "\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n"
            + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
            + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\","
            + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}\n\n";

    @Test
    @DisplayName("maxRetries(0): a retryable 529 is sent once and its error propagates")
    void zeroSendsOnce() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            AnthropicLlmClient client = client(server, 0);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("maxRetries(0): a 429 is sent once")
    void zeroSendsRateLimitOnce() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(RATE_LIMITED)) {
            AnthropicLlmClient client = client(server, 0);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("maxRetries(N): a failure that keeps coming back is sent at most N + 1 times")
    void positiveCapsAttempts() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            AnthropicLlmClient client = client(server, 1);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("maxRetries(N): a failure followed by success is retried within the cap and the answer converted")
    void recoversWithinCap() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED, SUCCESS)) {
            AnthropicLlmClient client = client(server, 3);

            LlmResponse response = send(client);

            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(response.getTextContent()).isEqualTo("ok");
            assertThat(response.getTokenUsage().getPromptTokens()).isEqualTo(3);
            assertThat(response.getTokenUsage().getCompletionTokens()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("unset: the SDK's own policy applies unchanged")
    void unsetKeepsSdkDefault() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            AnthropicLlmClient client = client(server, null);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1 + SDK_DEFAULT_RETRIES);
        }
    }

    @Test
    @DisplayName("a non-retryable 400 is sent once whatever maxRetries says")
    void nonRetryableIsNeverRetried() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(BAD_REQUEST)) {
            AnthropicLlmClient client = client(server, 3);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("maxRetries(0): a dropped connection is sent once and fails the call")
    void zeroDoesNotRetryNetworkFailure() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(Step.drop())) {
            AnthropicLlmClient client = client(server, 0);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("streaming, maxRetries(0): an error before the stream opens is sent once")
    void streamingZeroSendsOnce() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            AnthropicLlmClient client = client(server, 0);

            assertThatThrownBy(() -> stream(client, chunk -> {
            })).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("streaming, maxRetries(N): an error before the stream opens is sent at most N + 1 times")
    void streamingPositiveCapsAttempts() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            AnthropicLlmClient client = client(server, 2);

            assertThatThrownBy(() -> stream(client, chunk -> {
            })).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("streaming: a stream reset after its first delta is not replayed, even with retries left")
    void streamResetAfterDeltaIsNotReplayed() throws Exception {
        CountDownLatch deltaSeen = new CountDownLatch(1);
        try (CountingHttpServer server = CountingHttpServer.answering(Step.sseThenReset(PARTIAL_STREAM, deltaSeen))) {
            AnthropicLlmClient client = client(server, 3);
            List<LlmStreamChunk> chunks = new CopyOnWriteArrayList<>();

            // Whether the reset surfaces as an error or as a short answer is the stream contract's business, not this
            // key's; what this key must not do is send the request again.
            catchThrowable(() -> stream(client, chunk -> {
                chunks.add(chunk);
                if (chunk.getTextDelta().filter("partial"::equals).isPresent()) {
                    deltaSeen.countDown();
                }
            }));

            assertThat(deltaSeen.getCount()).as("the delta reached the sink before the reset").isZero();
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(chunks.stream().filter(c -> c.getTextDelta().filter("partial"::equals).isPresent())).hasSize(1);
        }
    }

    @Test
    @DisplayName("maxRetries(N): a connection that keeps dropping is sent at most N + 1 times")
    void positiveCapsNetworkFailures() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(Step.drop())) {
            AnthropicLlmClient client = client(server, 1);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(2);
        }
    }

    private static AnthropicLlmClient client(CountingHttpServer server, Integer maxRetries) {
        return new AnthropicLlmClient(AnthropicConfig.builder().apiKey("test-key").model("claude-x")
                .baseUrl(server.url()).maxRetries(maxRetries).build());
    }

    private static LlmResponse send(AnthropicLlmClient client) {
        return client.sendMessage("You are helpful", List.of(Message.user("hi")), Collections.emptyList(),
                LlmModel.builder().build());
    }

    private static LlmResponse stream(AnthropicLlmClient client, LlmStreamSink sink) {
        return client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), sink, LlmCancellation.none());
    }
}
