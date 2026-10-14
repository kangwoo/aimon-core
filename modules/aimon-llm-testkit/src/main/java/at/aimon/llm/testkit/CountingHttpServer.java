package at.aimon.llm.testkit;

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
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 *
 * <p>
 * Both SDKs build OkHttp with {@code retryOnConnectionFailure(false)}, so a dropped connection is not retried below the
 * SDK either, and the count is the SDK's alone.
 *
 * <p>
 * It lives here rather than in each provider module's test sources because the OpenAI and Anthropic retry tests need
 * the same server, and two copies of a test server drift the way two copies of anything do.
 *
 * <p>
 * Limits, all deliberate for what it is: one connection is served at a time, on one daemon thread, so a step that
 * holds its connection open ({@link Step#sseThenReset}) holds back the next request until it lets go; request bodies
 * are read by {@code Content-Length} only, which is what both SDKs send, not chunked; and a request is counted once its
 * head has arrived, before its answer is written, so {@link #requestCount()} is final by the time the client sees that
 * answer. {@link #requestCount()} may be read from any thread.
 */
public final class CountingHttpServer implements AutoCloseable {

    /** One scripted answer. */
    public static final class Step {
        private static final long HANG_GUARD_SECONDS = 20;
        /** Longer than the hang guard, so only a client that never sends a request trips it. */
        private static final int READ_TIMEOUT_MILLIS = 30_000;

        private final String raw;
        private final CountDownLatch resetWhen;

        private Step(String raw, CountDownLatch resetWhen) {
            this.raw = raw;
            this.resetWhen = resetWhen;
        }

        /**
         * A complete response. Error statuses carry {@code Retry-After-Ms: 1}, which both SDKs obey, so a retry costs
         * a millisecond of backoff rather than the exponential default. It is {@code 1} rather than {@code 0} because
         * the Anthropic SDK ignores a delay that is not positive and falls back to the exponential one.
         */
        public static Step status(int status, String contentType, String body) {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(body, "body");
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            final String retryAfter = status >= 400 ? "Retry-After-Ms: 1\r\n" : "";
            return new Step("HTTP/1.1 " + status + " X\r\nContent-Type: " + contentType + "\r\nContent-Length: "
                    + bytes.length + "\r\n" + retryAfter + "Connection: close\r\n\r\n" + body, null);
        }

        /** A JSON response. */
        public static Step json(int status, String body) {
            return status(status, "application/json", body);
        }

        /**
         * The opening of an SSE stream, then a connection reset once {@code reached} opens — a stream that fails after
         * its first events, rather than one that merely ends early. The test opens the latch from its sink, so the
         * reset comes after the client has read those events, not in a race with them.
         */
        public static Step sseThenReset(String events, CountDownLatch reached) {
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(reached, "reached");
            return new Step("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + events,
                    reached);
        }

        /** Closes the connection after reading the request, without writing a byte of response. */
        public static Step drop() {
            return new Step(null, null);
        }
    }

    private final ServerSocket socket;
    private final List<Step> script;
    private final AtomicInteger requests = new AtomicInteger();
    private final ExecutorService acceptor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "counting-http-server");
        // A test that forgets close() must not keep the JVM alive.
        thread.setDaemon(true);
        return thread;
    });

    private CountingHttpServer(List<Step> script) throws IOException {
        // Copied before the socket opens, so a bad script cannot leak a bound socket.
        this.script = List.copyOf(script);
        if (this.script.isEmpty()) {
            throw new IllegalArgumentException("A script needs at least one step");
        }
        // 127.0.0.1 explicitly rather than getLoopbackAddress(), which is ::1 under preferIPv6Addresses and would
        // then disagree with url().
        this.socket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        acceptor.submit(this::serve);
    }

    /**
     * Starts a server that answers with {@code steps} in order, repeating the last.
     *
     * @param steps
     *            the script, at least one step
     * @return the running server; close it when done
     * @throws IOException
     *             if the listening socket cannot be opened
     */
    public static CountingHttpServer answering(Step... steps) throws IOException {
        return new CountingHttpServer(List.of(steps));
    }

    /** The server's base URL, without a trailing slash. */
    public String url() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    /** How many requests have reached the server so far. */
    public int requestCount() {
        return requests.get();
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket connection = socket.accept()) {
                connection.setSoTimeout(Step.READ_TIMEOUT_MILLIS);
                if (!readRequest(connection.getInputStream())) {
                    continue;
                }
                final int index = requests.getAndIncrement();
                final Step step = script.get(Math.min(index, script.size() - 1));
                if (step.raw != null) {
                    final OutputStream out = connection.getOutputStream();
                    out.write(step.raw.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if (step.resetWhen != null) {
                    step.resetWhen.await(Step.HANG_GUARD_SECONDS, TimeUnit.SECONDS);
                    // Linger zero turns the close below into a TCP reset.
                    connection.setSoLinger(true, 0);
                }
            } catch (IOException e) {
                // Closed by close(), or the client hung up; either way the next accept decides.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // One malformed request must not silently end the loop: a dead acceptor with the socket still open
                // turns every later attempt into a client-side timeout that hides this cause.
                System.err.println("CountingHttpServer: request handling failed: " + e);
            }
        }
    }

    /**
     * Reads one request's head and its {@code Content-Length} body, so the client is never cut off mid-send.
     *
     * @return whether a whole request head arrived — a connection that ends before one is not a request
     */
    private static boolean readRequest(InputStream raw) throws IOException {
        final InputStream in = new BufferedInputStream(raw);
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return false;
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
        return true;
    }

    @Override
    public void close() throws IOException {
        socket.close();
        acceptor.shutdownNow();
    }
}
