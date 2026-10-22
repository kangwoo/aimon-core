package at.aimon.core.tools.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.tools.bash.BackgroundBashOwner;
import at.aimon.core.tools.monitor.exception.MonitorRequestException;
import at.aimon.core.tools.web.security.SsrfGuard;
import at.aimon.core.tools.web.security.SsrfGuardConfig;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;

@DisplayName("WebSocketMonitorSourceFactory — frames to events, endings, URL checks")
class WebSocketMonitorSourceFactoryTest {

    private static final BackgroundBashOwner OWNER = BackgroundBashOwner.of(AgentRuntimeId.of("agent:ws"),
            SessionId.of("s"), null);

    private MockWebServer server;
    private RecordingSink sink;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        sink = new RecordingSink();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MonitorRequest request(Map<String, ?> params) {
        return MonitorRequest.builder().owner(OWNER).kind(WebSocketMonitorSourceFactory.KIND).description("d")
                .params(params).build();
    }

    private String wsUrl() {
        return "ws://" + server.getHostName() + ":" + server.getPort() + "/events";
    }

    /** Local test server: the default policy would refuse loopback, which is the point of the next test. */
    private static WebSocketMonitorSourceFactory permissive() {
        return new WebSocketMonitorSourceFactory(new SsrfGuard(SsrfGuardConfig.disabled()));
    }

    @Test
    @DisplayName("text frames become events, a binary frame a placeholder, and the close completes it")
    void framesAndClose() {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send("alarm 1");
                webSocket.send(ByteString.of((byte) 1, (byte) 2, (byte) 3));
                webSocket.send("alarm\ntwo lines");
                webSocket.close(1000, "done");
            }
        }));

        permissive().create(request(Map.of("url", wsUrl()))).start(sink);

        await(() -> !sink.endings.isEmpty());
        assertThat(sink.events).containsExactly("alarm 1", "[binary frame, 3 bytes]", "alarm\ntwo lines");
        assertThat(sink.endings).containsExactly("complete:socket closed 1000 done");
    }

    @Test
    @DisplayName("a refused handshake fails the monitor with the HTTP status")
    void refusedHandshakeFails() {
        server.enqueue(new MockResponse().setResponseCode(403));

        permissive().create(request(Map.of("url", wsUrl()))).start(sink);

        await(() -> !sink.endings.isEmpty());
        assertThat(sink.endings).singleElement().asString().startsWith("fail:the socket failed (HTTP 403)");
    }

    @Test
    @DisplayName("stopping the handle closes the socket without a completion from it")
    void stopCancels() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
        }));
        final MonitorHandle handle = permissive().create(request(Map.of("url", wsUrl()))).start(sink);
        Thread.sleep(200);

        handle.stop();
        handle.stop();

        await(() -> !sink.endings.isEmpty());
        assertThat(sink.endings).singleElement().asString().startsWith("fail:");
    }

    @Test
    @DisplayName("the default policy refuses a loopback URL before anything is sent")
    void defaultPolicyRefusesLoopback() {
        assertThatThrownBy(() -> new WebSocketMonitorSourceFactory().create(request(Map.of("url", wsUrl()))))
                .isInstanceOf(MonitorRequestException.class).hasMessageContaining("does not allow");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("only ws and wss URLs, a present url, and string protocols are accepted")
    void urlAndProtocolChecks() {
        final WebSocketMonitorSourceFactory factory = permissive();
        assertThatThrownBy(() -> factory.create(request(Map.of("url", "https://example.com/"))))
                .isInstanceOf(MonitorRequestException.class).hasMessageContaining("ws:// or wss://");
        assertThatThrownBy(() -> factory.create(request(Map.of()))).isInstanceOf(MonitorRequestException.class)
                .hasMessageContaining("needs ws.url");
        assertThatThrownBy(() -> factory.create(request(Map.of("url", wsUrl(), "protocols", List.of(1)))))
                .isInstanceOf(MonitorRequestException.class).hasMessageContaining("list of strings");
    }

    @Test
    @DisplayName("offered subprotocols go out in the handshake")
    void protocolsAreOffered() throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.close(1000, null);
            }
        }));

        permissive().create(request(Map.of("url", wsUrl(), "protocols", List.of("v1", "v2")))).start(sink);

        assertThat(server.takeRequest().getHeader("Sec-WebSocket-Protocol")).isEqualTo("v1, v2");
    }

    private static void await(BooleanSupplier condition) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static final class RecordingSink implements MonitorEventSink {

        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> endings = new CopyOnWriteArrayList<>();

        @Override
        public void emit(String event) {
            events.add(event);
        }

        @Override
        public void complete(String detail) {
            endings.add("complete:" + detail);
        }

        @Override
        public void fail(String reason) {
            endings.add("fail:" + reason);
        }
    }
}
