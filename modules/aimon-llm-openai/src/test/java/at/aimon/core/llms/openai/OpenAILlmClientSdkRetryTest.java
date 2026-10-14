package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
import at.aimon.core.llms.openai.CountingHttpServer.Step;

/**
 * {@link OpenAIConfig#getMaxRetries()} over the real transport: what the SDK underneath the client actually sends, read
 * as the number of requests a local server received.
 *
 * <p>
 * The default-policy cases pin the SDK version this module ships (two retries in 4.69.2). They are a regression check
 * for "unset leaves the SDK alone", not a promise about the count — when an SDK upgrade changes it, the expectation
 * here moves with it.
 */
@DisplayName("OpenAILlmClient - SDK retries (maxRetries)")
class OpenAILlmClientSdkRetryTest {

    /** The SDK's own default in the version this module ships. */
    private static final int SDK_DEFAULT_RETRIES = 2;

    private static final Step OVERLOADED = Step.json(503,
            "{\"error\":{\"message\":\"overloaded\",\"type\":\"server_error\"}}");
    private static final Step RATE_LIMITED = Step.json(429,
            "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\"}}");
    private static final Step BAD_REQUEST = Step.json(400,
            "{\"error\":{\"message\":\"bad\",\"type\":\"invalid_request_error\"}}");

    private static final String CHAT_SUCCESS = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,"
            + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"ok\",\"refusal\":null},\"finish_reason\":\"stop\",\"logprobs\":null}],"
            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}";
    private static final String CHAT_PARTIAL_STREAM = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
            + "\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
            + "\"content\":\"partial\"},\"finish_reason\":null}]}\n\n";
    private static final String RESPONSES_SUCCESS = "{\"id\":\"r\",\"object\":\"response\",\"created_at\":1,"
            + "\"model\":\"gpt-5-mini\",\"status\":\"completed\",\"parallel_tool_calls\":true,"
            + "\"tool_choice\":\"auto\",\"tools\":[],\"output\":[{\"type\":\"message\",\"id\":\"msg_1\","
            + "\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
            + "\"text\":\"ok\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":1,"
            + "\"total_tokens\":4,\"input_tokens_details\":{\"cached_tokens\":0},"
            + "\"output_tokens_details\":{\"reasoning_tokens\":0}}}";
    private static final String RESPONSES_PARTIAL_STREAM = "event: response.created\ndata: {\"type\":\"response.created\","
            + "\"sequence_number\":0,\"response\":{\"id\":\"r\",\"created_at\":1,\"model\":\"m\","
            + "\"object\":\"response\",\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
            + "\"status\":\"in_progress\",\"output\":[]}}\n\n"
            + "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\","
            + "\"content_index\":0,\"item_id\":\"msg_1\",\"output_index\":0,\"logprobs\":[],"
            + "\"delta\":\"partial\",\"sequence_number\":1}\n\n";

    /** The two endpoints, each with a model that routes to it and a successful answer from it. */
    enum Endpoint {
        CHAT_COMPLETIONS("gpt-4o", CHAT_SUCCESS, CHAT_PARTIAL_STREAM), RESPONSES("gpt-5-mini", RESPONSES_SUCCESS,
                RESPONSES_PARTIAL_STREAM);

        private final String model;
        private final String success;
        private final String partialStream;

        Endpoint(String model, String success, String partialStream) {
            this.model = model;
            this.success = success;
            this.partialStream = partialStream;
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("maxRetries(0): a retryable 503 is sent once and its error propagates")
    void zeroSendsOnce(Endpoint endpoint) throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            OpenAILlmClient client = client(server, endpoint, 0);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("maxRetries(0): a 429 is sent once")
    void zeroSendsRateLimitOnce(Endpoint endpoint) throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(RATE_LIMITED)) {
            OpenAILlmClient client = client(server, endpoint, 0);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("maxRetries(N): a failure that keeps coming back is sent at most N + 1 times")
    void positiveCapsAttempts(Endpoint endpoint) throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            OpenAILlmClient client = client(server, endpoint, 1);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("maxRetries(N): a failure followed by success is retried within the cap and the answer converted")
    void recoversWithinCap(Endpoint endpoint) throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED, Step.json(200, endpoint.success))) {
            OpenAILlmClient client = client(server, endpoint, 3);

            LlmResponse response = send(client);

            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(response.getTextContent()).isEqualTo("ok");
            assertThat(response.getTokenUsage().getPromptTokens()).isEqualTo(3);
            assertThat(response.getTokenUsage().getCompletionTokens()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("unset: the SDK's own policy applies unchanged")
    void unsetKeepsSdkDefault(Endpoint endpoint) throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            OpenAILlmClient client = client(server, endpoint, null);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1 + SDK_DEFAULT_RETRIES);
        }
    }

    @Test
    @DisplayName("a non-retryable 400 is sent once whatever maxRetries says")
    void nonRetryableIsNeverRetried() throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(BAD_REQUEST)) {
            OpenAILlmClient client = client(server, Endpoint.CHAT_COMPLETIONS, 3);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("maxRetries(0): a dropped connection is sent once and fails the call")
    void zeroDoesNotRetryNetworkFailure(Endpoint endpoint) throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(Step.drop())) {
            OpenAILlmClient client = client(server, endpoint, 0);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("streaming, maxRetries(0): an error before the stream opens is sent once")
    void streamingZeroSendsOnce(Endpoint endpoint) throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            OpenAILlmClient client = client(server, endpoint, 0);

            assertThatThrownBy(() -> stream(client, chunk -> {
            })).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("streaming, maxRetries(N): an error before the stream opens is sent at most N + 1 times")
    void streamingPositiveCapsAttempts(Endpoint endpoint) throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(OVERLOADED)) {
            OpenAILlmClient client = client(server, endpoint, 2);

            assertThatThrownBy(() -> stream(client, chunk -> {
            })).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("streaming: a stream reset after its first delta is not replayed, even with retries left")
    void streamResetAfterDeltaIsNotReplayed(Endpoint endpoint) throws Exception {
        CountDownLatch deltaSeen = new CountDownLatch(1);
        try (CountingHttpServer server = CountingHttpServer
                .answering(Step.sseThenReset(endpoint.partialStream, deltaSeen))) {
            OpenAILlmClient client = client(server, endpoint, 3);
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

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("maxRetries(N): a connection that keeps dropping is sent at most N + 1 times")
    void positiveCapsNetworkFailures(Endpoint endpoint) throws Exception {
        try (CountingHttpServer server = CountingHttpServer.answering(Step.drop())) {
            OpenAILlmClient client = client(server, endpoint, 1);

            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmClientException.class);

            assertThat(server.requestCount()).isEqualTo(2);
        }
    }

    private static OpenAILlmClient client(CountingHttpServer server, Endpoint endpoint, Integer maxRetries) {
        return new OpenAILlmClient(OpenAIConfig.builder().apiKey("test-key").model(endpoint.model)
                .baseUrl(server.url() + "/v1").maxRetries(maxRetries).build());
    }

    private static LlmResponse send(OpenAILlmClient client) {
        return client.sendMessage("You are helpful", List.of(Message.user("hi")), Collections.emptyList(),
                LlmModel.builder().build());
    }

    private static LlmResponse stream(OpenAILlmClient client, LlmStreamSink sink) {
        return client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                Collections.emptyList(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                LlmStreamingOptions.defaults(), sink, LlmCancellation.none());
    }
}
