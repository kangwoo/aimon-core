package at.aimon.core.tools.monitor;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.tools.monitor.exception.MonitorRequestException;
import at.aimon.core.tools.web.fetch.WebHttpClientFactory;
import at.aimon.core.tools.web.security.SsrfGuard;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * The {@code websocket} source: a WebSocket the monitor opens, each text message one event.
 *
 * <p>
 * Only {@code ws} and {@code wss} URLs are accepted, and the connection is made through the SSRF-hardened client of
 * {@link WebHttpClientFactory}: the URL is checked before anything is sent, and its network interceptor checks the
 * address the socket actually connected to — so a host name that resolves to a private address at connect time is
 * refused too, not only one that did when the URL was checked (design §4.1). A deployment that must not let the model
 * open sockets at all leaves this kind unregistered ({@link MonitorManager.Builder#sources(List)}).
 *
 * <p>
 * A binary message is reported as {@code [binary frame, N bytes]}, not passed through. The socket closing ends the
 * monitor as completed, with the close code; a failure to connect or a broken socket fails it. The client pings every
 * {@value #PING_SECONDS} seconds, so a connection that silently died is noticed rather than waited on until the
 * timeout.
 */
public final class WebSocketMonitorSourceFactory implements MonitorSourceFactory, AutoCloseable {

    /** The kind this factory serves. */
    public static final String KIND = "websocket";

    /** The parameter holding the URL. */
    public static final String URL_PARAM = "url";

    /** The parameter holding the subprotocols to offer. */
    public static final String PROTOCOLS_PARAM = "protocols";

    private static final Logger log = LoggerFactory.getLogger(WebSocketMonitorSourceFactory.class);

    private static final long PING_SECONDS = 30;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_URL_LENGTH = 4_096;

    private final SsrfGuard ssrfGuard;
    private final OkHttpClient client;

    /**
     * Creates the factory with the default SSRF policy: private, loopback and link-local addresses are refused.
     */
    public WebSocketMonitorSourceFactory() {
        this(new SsrfGuard());
    }

    /**
     * Creates the factory with an SSRF policy of the caller's — an allow-list, say.
     *
     * @param ssrfGuard
     *            the policy URLs and connected addresses are checked against (must not be null)
     */
    public WebSocketMonitorSourceFactory(SsrfGuard ssrfGuard) {
        this.ssrfGuard = Objects.requireNonNull(ssrfGuard, "ssrfGuard must not be null");
        // Read and call timeouts off: a monitor's socket is meant to sit idle between messages. The ping is what
        // notices a dead peer, and the manager's timeout is what bounds the whole.
        this.client = WebHttpClientFactory.newBuilder(ssrfGuard, CONNECT_TIMEOUT).readTimeout(Duration.ZERO)
                .callTimeout(Duration.ZERO).pingInterval(Duration.ofSeconds(PING_SECONDS)).build();
    }

    /**
     * Releases the client's threads and pooled connections. Called by the {@link MonitorManager} for the factory it
     * created; a factory the host registered is the host's to close. Sockets still open are cancelled.
     */
    @Override
    public void close() {
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String describe() {
        return "websocket — opens `ws.url` (ws:// or wss://, public hosts only); each text message is one event";
    }

    @Override
    public MonitorSource create(MonitorRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final String url = request.getStringParam(URL_PARAM).map(String::strip).filter(u -> !u.isEmpty())
                .orElseThrow(() -> new MonitorRequestException("The websocket source needs ws.url."));
        if (url.length() > MAX_URL_LENGTH) {
            throw new MonitorRequestException("ws.url exceeds " + MAX_URL_LENGTH + " characters.");
        }
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new MonitorRequestException("ws.url is not a valid URL: " + e.getMessage(), e);
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("ws") && !scheme.equals("wss")) {
            throw new MonitorRequestException("ws.url must start with ws:// or wss://.");
        }
        // SsrfGuard speaks http(s) only; a WebSocket handshake is an HTTP request to the same host and port.
        final String asHttp = (scheme.equals("ws") ? "http" : "https") + url.substring(scheme.length());
        if (!ssrfGuard.isSafe(asHttp)) {
            throw new MonitorRequestException("ws.url points at an address this application does not allow"
                    + " (private, loopback or otherwise blocked).");
        }
        return new WebSocketMonitorSource(asHttp, protocols(request.getParams().get(PROTOCOLS_PARAM)));
    }

    private static List<String> protocols(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new MonitorRequestException("ws.protocols must be a list of strings.");
        }
        final List<String> protocols = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String protocol) || protocol.isBlank()) {
                throw new MonitorRequestException("ws.protocols must be a list of strings.");
            }
            protocols.add(protocol.strip());
        }
        return List.copyOf(protocols);
    }

    /** One socket, not yet opened. */
    private final class WebSocketMonitorSource implements MonitorSource {

        private final String url;
        private final List<String> protocols;

        WebSocketMonitorSource(String url, List<String> protocols) {
            this.url = url;
            this.protocols = protocols;
        }

        @Override
        public MonitorHandle start(MonitorEventSink sink) {
            final Request.Builder request = new Request.Builder().url(url);
            if (!protocols.isEmpty()) {
                request.header("Sec-WebSocket-Protocol", String.join(", ", protocols));
            }
            final WebSocket socket = client.newWebSocket(request.build(), new Listener(sink));
            return () -> socket.cancel();
        }
    }

    /** Turns socket callbacks into sink calls. OkHttp delivers them one at a time, on its reader thread. */
    private static final class Listener extends WebSocketListener {

        private final MonitorEventSink sink;

        Listener(MonitorEventSink sink) {
            this.sink = sink;
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            sink.emit(text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            sink.emit("[binary frame, " + bytes.size() + " bytes]");
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            sink.complete("socket closed " + code + (reason == null || reason.isBlank() ? "" : " " + reason));
            // Echoed as a normal close, not with the peer's code: 1005 ("no status") and the other reserved codes
            // cannot be sent, and OkHttp would report the attempt as a failure.
            webSocket.close(1000, null);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            final String status = response != null ? " (HTTP " + response.code() + ")" : "";
            log.debug("Monitor socket failed{}: {}", status, t.toString());
            sink.fail("the socket failed" + status + ": " + (t.getMessage() != null ? t.getMessage() : t));
        }
    }
}
