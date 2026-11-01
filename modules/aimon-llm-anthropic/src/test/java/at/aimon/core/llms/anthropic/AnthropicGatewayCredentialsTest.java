package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmAuthException;
import at.aimon.core.llm.exception.LlmInvalidRequestException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * What a gateway behind {@code baseUrl} receives and what the client says when it refuses. Asserted on the headers a
 * real local server read, because the credential is applied in the SDK's auth layer, above anything a mock can see.
 */
@DisplayName("AnthropicLlmClient - the credential a gateway receives")
class AnthropicGatewayCredentialsTest {

    private static final String KEY = "test-key";
    private static final String SUCCESS = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-x\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
            + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}";
    private static final String UNAUTHORIZED = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
            + "\"message\":\"no\"}}";
    private static final String BAD_REQUEST = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
            + "\"message\":\"bad\"}}";

    private final List<Headers> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
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
        if (server != null) {
            server.stop(0);
        }
    }

    private String serving(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static AnthropicConfig.Builder config(String baseUrl) {
        return AnthropicConfig.builder().apiKey(KEY).baseUrl(baseUrl).maxRetries(0);
    }

    private static void send(AnthropicLlmClient client) {
        client.sendMessage("s", List.of(Message.user("hi")), List.of(), LlmModel.builder().build());
    }

    private List<String> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("by default the key goes as Authorization: Bearer, and X-Api-Key is not sent")
    void theDefaultIsBearer() throws Exception {
        try (AnthropicLlmClient client = new AnthropicLlmClient(config(serving(200, SUCCESS)).build())) {
            send(client);
        }

        assertThat(received).singleElement().satisfies(headers -> {
            assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer " + KEY);
            assertThat(headers.containsKey("X-Api-Key")).isFalse();
        });
    }

    @Test
    @DisplayName("x-api-key sends the key there alone, so a gateway never sees two credentials")
    void xApiKeySendsThatHeaderAlone() throws Exception {
        try (AnthropicLlmClient client = new AnthropicLlmClient(
                config(serving(200, SUCCESS)).apiKeyHeader(AnthropicApiKeyHeader.X_API_KEY).build())) {
            send(client);
        }

        assertThat(received).singleElement().satisfies(headers -> {
            assertThat(headers.getFirst("X-Api-Key")).isEqualTo(KEY);
            assertThat(headers.containsKey("Authorization")).isFalse();
        });
    }

    @Test
    @DisplayName("a gateway's 401 names the other header, once, and the error stays what it was")
    void aRefusedCredentialNamesTheRemedyOnce() throws Exception {
        try (AnthropicLlmClient client = new AnthropicLlmClient(config(serving(401, UNAUTHORIZED)).build())) {
            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmAuthException.class);
            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmAuthException.class);
        }

        assertThat(warnings()).singleElement().asString().contains("Authorization: Bearer").contains("X_API_KEY")
                .doesNotContain(KEY);
    }

    @Test
    @DisplayName("a 401 with x-api-key already chosen has no other header to suggest")
    void aRefusalOfTheChosenHeaderSaysNothing() throws Exception {
        try (AnthropicLlmClient client = new AnthropicLlmClient(
                config(serving(401, UNAUTHORIZED)).apiKeyHeader(AnthropicApiKeyHeader.X_API_KEY).build())) {
            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmAuthException.class);
        }

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("a gateway's 400 names the API version and Bedrock's limit, as a possibility")
    void aRefusedRequestNamesTheApiVersion() throws Exception {
        try (AnthropicLlmClient client = new AnthropicLlmClient(config(serving(400, BAD_REQUEST)).build())) {
            assertThatThrownBy(() -> send(client)).isInstanceOf(LlmInvalidRequestException.class);
        }

        assertThat(warnings()).singleElement().asString().contains("anthropic-version: 2026-10-21").contains("Bedrock");
    }

    @Test
    @DisplayName("the request carries the API version the hint names")
    void theHintMatchesTheWire() throws Exception {
        try (AnthropicLlmClient client = new AnthropicLlmClient(config(serving(200, SUCCESS)).build())) {
            send(client);
        }

        assertThat(received).singleElement()
                .satisfies(headers -> assertThat(headers.getFirst("anthropic-version")).isEqualTo("2026-10-21"));
    }

    @Test
    @DisplayName("spellings fold onto one constant, and an unknown one onto none")
    void configValuesFold() {
        assertThat(List.of("x-api-key", "X_API_KEY", " X-Api-Key "))
                .allSatisfy(written -> assertThat(AnthropicApiKeyHeader.fromConfigValue(written))
                        .contains(AnthropicApiKeyHeader.X_API_KEY));
        assertThat(AnthropicApiKeyHeader.fromConfigValue("bearer")).isEmpty();
        assertThat(AnthropicApiKeyHeader.acceptedConfigValues()).isEqualTo("authorization, x-api-key");
    }
}
