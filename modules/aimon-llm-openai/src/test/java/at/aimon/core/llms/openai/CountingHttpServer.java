package at.aimon.core.llms.openai;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local HTTP server that answers each request with the next step of a script and counts how many requests reached
 * it.
 *
 * <p>
 * The count is the point: it is the number of HTTP attempts the SDK actually made, observed on the server side, rather
 * than a builder call a mock saw. The last step repeats once the script runs out. It is a raw socket rather than
 * {@code com.sun.net.httpserver} so a step can also drop the connection without answering, which is what a network
 * failure looks like to the client. Every answer closes its connection, so each attempt is a fresh one.
 */
final class CountingHttpServer implements AutoCloseable {

    /** One scripted answer. */
    static final class Step {
        private final String raw;

        private Step(String raw) {
            this.raw = raw;
        }

        /**
         * A complete response. Error statuses carry {@code Retry-After-Ms: 0}, which the SDK obeys, so a retry costs
         * no backoff and the tests stay fast.
         */
        static Step status(int status, String contentType, String body) {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            final String retryAfter = status >= 400 ? "Retry-After-Ms: 0\r\n" : "";
            return new Step("HTTP/1.1 " + status + " X\r\nContent-Type: " + contentType + "\r\nContent-Length: "
                    + bytes.length + "\r\n" + retryAfter + "Connection: close\r\n\r\n" + body);
        }

        /** A JSON response. */
        static Step json(int status, String body) {
            return status(status, "application/json", body);
        }

        /** The opening of an SSE stream, after which the connection is closed with no terminating event. */
        static Step truncatedSse(String events) {
            return new Step("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + events);
        }

        /** Closes the connection after reading the request, without writing a byte of response. */
        static Step drop() {
            return new Step(null);
        }
    }

    private final ServerSocket socket;
    private final List<Step> script;
    private final AtomicInteger requests = new AtomicInteger();
    private final ExecutorService acceptor = Executors.newSingleThreadExecutor();

    private CountingHttpServer(List<Step> script) throws IOException {
        this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.script = List.copyOf(script);
        acceptor.submit(this::serve);
    }

    /** Starts a server that answers with {@code steps} in order, repeating the last. */
    static CountingHttpServer answering(Step... steps) throws IOException {
        return new CountingHttpServer(List.of(steps));
    }

    /** The server's base URL, without a trailing slash. */
    String url() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    /** How many requests have reached the server so far. */
    int requestCount() {
        return requests.get();
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket connection = socket.accept()) {
                readRequest(connection.getInputStream());
                final int index = requests.getAndIncrement();
                final Step step = script.get(Math.min(index, script.size() - 1));
                if (step.raw != null) {
                    final OutputStream out = connection.getOutputStream();
                    out.write(step.raw.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException e) {
                // Closed by close(), or the client hung up; either way the next accept decides.
            }
        }
    }

    /** Reads one request's head and its {@code Content-Length} body, so the client is never cut off mid-send. */
    private static void readRequest(InputStream raw) throws IOException {
        final InputStream in = new BufferedInputStream(raw);
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return;
            }
            head.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        int length = 0;
        for (String line : head.toString(StandardCharsets.ISO_8859_1).split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        in.readNBytes(length);
    }

    @Override
    public void close() throws IOException {
        socket.close();
        acceptor.shutdownNow();
    }
}
