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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local HTTP/1.1 server that answers every request with the same scripted opening of a chunked SSE stream and then
 * holds the rest until the test says otherwise.
 *
 * <p>
 * It is what a provider looks like to a client whose call is cut mid-stream. The transport underneath the client is
 * the real one — the SDK, OkHttp, a socket — which is the point: what a cancelled call does to its reader is the
 * SDK's and OkHttp's behaviour, and a mocked {@code StreamResponse} states an assumption about it rather than
 * observing it. A raw socket rather than {@code com.sun.net.httpserver} so the chunk terminator can be held back
 * deliberately, and so a connection can be reset rather than closed. {@code Transfer-Encoding: chunked} is written by
 * hand, one chunk per write.
 *
 * <p>
 * Three shapes, one per server:
 *
 * <ul>
 * <li>{@link #stallingBeforeHeaders()} — reads the request and sends nothing: a header wait that never ends until the
 * client hangs up or the server is closed.</li>
 * <li>{@link #sending(String, String)} — flushes the response headers and the {@code head} events, then holds the
 * {@code tail} (further events, if any, and the chunk terminator) until {@link #release()}, {@link #reset()} or the
 * client hangs up. With an empty tail this is a stream that stalls after its opening; with the terminal events in the
 * head it is a stream whose answer is whole but whose transport has not ended.</li>
 * <li>{@link #complete(String)} — the whole stream, terminator included, in one go.</li>
 * </ul>
 *
 * <p>
 * One thread per connection, so a connection that is being held does not hide a second request arriving on another:
 * the request count is what the retry assertions read. Request bodies are read by {@code Content-Length}, which is what
 * both SDKs send. Every connection carries {@code Connection: close}, so the client never pools a connection this
 * server is about to cut.
 *
 * <p>
 * <strong>JDK only.</strong> This module depends on nothing — not aimon-core, not a test framework — so the waits are
 * plain monitors with deadlines and every answer is a boolean the test asserts on.
 */
public final class StallingSseServer implements AutoCloseable {

    private enum Mode {
        STALL_BEFORE_HEADERS, SSE_THEN_HOLD, SSE_COMPLETE
    }

    /** How a held connection ends: decided once, server-wide, and applied to every connection held after it too. */
    private enum Ending {
        /** The tail and the chunk terminator are written, then the connection is closed. */
        RELEASE,
        /** The connection is closed with linger zero: a TCP reset, no bytes. */
        RESET,
        /** The server is shutting down: closed, no bytes. */
        CLOSE
    }

    private static final String RESPONSE_HEAD = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
            + "Cache-Control: no-cache\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";

    private final ServerSocket socket;
    private final Mode mode;
    private final String head;
    private final String tail;

    private final Object lock = new Object();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger answered = new AtomicInteger();
    private final AtomicInteger clientDisconnects = new AtomicInteger();
    private final List<Map<String, List<String>>> requestHeaders = new CopyOnWriteArrayList<>();
    private final List<Connection> held = new ArrayList<>();
    private Ending ending;

    private final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "stalling-sse-server");
        // A test that forgets close() must not keep the JVM alive.
        thread.setDaemon(true);
        return thread;
    });

    private StallingSseServer(Mode mode, String head, String tail) throws IOException {
        this.mode = mode;
        this.head = head;
        this.tail = tail;
        // 127.0.0.1 explicitly rather than getLoopbackAddress(), which is ::1 under preferIPv6Addresses and would
        // then disagree with url().
        this.socket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        threads.submit(this::accept);
    }

    /** Starts a server that reads each request and then sends nothing until the client hangs up or it is closed. */
    public static StallingSseServer stallingBeforeHeaders() throws IOException {
        return new StallingSseServer(Mode.STALL_BEFORE_HEADERS, "", "");
    }

    /**
     * Starts a server that sends {@code head} and then holds {@code tail} and the chunk terminator.
     *
     * @param head
     *            the SSE events flushed at once, after the response headers
     * @param tail
     *            the SSE events written on {@link #release()} before the chunk terminator; may be empty
     */
    public static StallingSseServer sending(String head, String tail) throws IOException {
        return new StallingSseServer(Mode.SSE_THEN_HOLD, Objects.requireNonNull(head, "head"),
                Objects.requireNonNull(tail, "tail"));
    }

    /** Starts a server that sends {@code events} and the chunk terminator, then closes the connection. */
    public static StallingSseServer complete(String events) throws IOException {
        return new StallingSseServer(Mode.SSE_COMPLETE, Objects.requireNonNull(events, "events"), "");
    }

    /** The server's base URL, without a trailing slash. */
    public String url() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    /** How many requests have reached the server so far. May be read from any thread. */
    public int requestCount() {
        return requests.get();
    }

    /**
     * The headers of every request received so far, in arrival order, names lower-cased. A snapshot.
     */
    public List<Map<String, List<String>>> requestHeaders() {
        return List.copyOf(requestHeaders);
    }

    /**
     * Waits until {@code n} requests have been answered: the head flushed for the SSE shapes, the request read for the
     * header-stalling one. Returns {@code false} if the timeout passes first — a hang guard, not a measurement.
     */
    public boolean awaitAnswered(int n, Duration timeout) throws InterruptedException {
        return awaitAtLeast(answered, n, timeout);
    }

    /**
     * Waits until {@code n} connections have been hung up on by the client — its side closed or reset while the server
     * was still holding the connection. A connection the server ended itself ({@link #release()}, {@link #reset()},
     * {@link #close()}) is not counted. Returns {@code false} if the timeout passes first.
     */
    public boolean awaitClientDisconnected(int n, Duration timeout) throws InterruptedException {
        return awaitAtLeast(clientDisconnects, n, timeout);
    }

    /**
     * Ends every held connection by writing its tail and the chunk terminator, then closing. Connections held after
     * this call are ended the same way as soon as their head is flushed. Idempotent.
     */
    public void release() {
        end(Ending.RELEASE);
    }

    /**
     * Ends every held connection with a TCP reset instead of a tail — what a transport failure after the answer looks
     * like. Connections held after this call are reset the same way. Idempotent.
     */
    public void reset() {
        end(Ending.RESET);
    }

    @Override
    public void close() throws IOException {
        end(Ending.CLOSE);
        socket.close();
        threads.shutdownNow();
    }

    private void end(Ending how) {
        final List<Connection> snapshot;
        synchronized (lock) {
            if (ending == null) {
                ending = how;
            }
            snapshot = new ArrayList<>(held);
        }
        snapshot.forEach(connection -> connection.end(how));
    }

    private boolean awaitAtLeast(AtomicInteger counter, int n, Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (counter.get() < n) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                lock.wait(Math.max(1, remaining / 1_000_000));
            }
            return true;
        }
    }

    private void bump(AtomicInteger counter) {
        synchronized (lock) {
            counter.incrementAndGet();
            lock.notifyAll();
        }
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                final Socket connection = socket.accept();
                threads.submit(() -> serve(connection));
            } catch (IOException e) {
                // Closed by close(); the loop condition ends it.
            }
        }
    }

    private void serve(Socket connection) {
        try (connection) {
            final InputStream in = new BufferedInputStream(connection.getInputStream());
            final Map<String, List<String>> headers = readRequest(in);
            if (headers == null) {
                // A connection that ends before a whole request head is not a request.
                return;
            }
            requestHeaders.add(headers);
            bump(requests);
            final OutputStream out = connection.getOutputStream();
            switch (mode) {
                case STALL_BEFORE_HEADERS -> {
                    bump(answered);
                    hold(new Connection(connection, out, false, ""), in);
                }
                case SSE_THEN_HOLD -> {
                    out.write(RESPONSE_HEAD.getBytes(StandardCharsets.US_ASCII));
                    writeChunk(out, head);
                    out.flush();
                    bump(answered);
                    hold(new Connection(connection, out, true, tail), in);
                }
                case SSE_COMPLETE -> {
                    out.write(RESPONSE_HEAD.getBytes(StandardCharsets.US_ASCII));
                    writeChunk(out, head);
                    writeTerminator(out);
                    out.flush();
                    bump(answered);
                    awaitClientGone(new Connection(connection, out, true, ""), in);
                }
                default -> throw new IllegalStateException("Unknown mode " + mode);
            }
        } catch (IOException e) {
            // The client hung up before or while the head was written, which the tests observe elsewhere.
        }
    }

    /** Registers the connection as held — ending it at once if the server has already decided how — then waits. */
    private void hold(Connection connection, InputStream in) {
        final Ending decided;
        synchronized (lock) {
            held.add(connection);
            decided = ending;
        }
        if (decided != null) {
            connection.end(decided);
        }
        awaitClientGone(connection, in);
    }

    /**
     * Blocks on the client's side of the socket. The client sends nothing more on an SSE request, so the read returns
     * only when the client hangs up (−1 or a reset) or when the server ends the connection itself, which makes the
     * read throw. Only the former is a client disconnect.
     */
    private void awaitClientGone(Connection connection, InputStream in) {
        try {
            while (in.read() >= 0) {
                // The client is not expected to write; drain anything it does.
            }
        } catch (IOException e) {
            // Reset by the client, or closed by this server — told apart below.
        }
        if (!connection.endedByServer()) {
            bump(clientDisconnects);
        }
    }

    private static void writeChunk(OutputStream out, String data) throws IOException {
        if (data.isEmpty()) {
            return;
        }
        final byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeTerminator(OutputStream out) throws IOException {
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Reads one request's head and its {@code Content-Length} body, so the client is never cut off mid-send.
     *
     * @return the request headers, names lower-cased, or {@code null} if the connection ended before a whole head
     */
    private static Map<String, List<String>> readRequest(InputStream in) throws IOException {
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            final int b = in.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        int length = 0;
        final String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            final int colon = lines[i].indexOf(':');
            if (colon < 0) {
                continue;
            }
            final String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            final String value = lines[i].substring(colon + 1).trim();
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
            if ("content-length".equals(name)) {
                length = Integer.parseInt(value);
            }
        }
        in.readNBytes(length);
        headers.replaceAll((name, values) -> Collections.unmodifiableList(values));
        return Collections.unmodifiableMap(headers);
    }

    /** One held connection and the one way it ends. */
    private static final class Connection {
        private final Socket socket;
        private final OutputStream out;
        private final boolean headersSent;
        private final String tail;
        private boolean ended;
        private volatile boolean endedByServer;

        private Connection(Socket socket, OutputStream out, boolean headersSent, String tail) {
            this.socket = socket;
            this.out = out;
            this.headersSent = headersSent;
            this.tail = tail;
        }

        boolean endedByServer() {
            return endedByServer;
        }

        synchronized void end(Ending how) {
            if (ended) {
                return;
            }
            ended = true;
            endedByServer = true;
            try {
                if (how == Ending.RELEASE && headersSent) {
                    writeChunk(out, tail);
                    writeTerminator(out);
                    out.flush();
                } else if (how == Ending.RESET) {
                    // Linger zero turns the close below into a TCP reset.
                    socket.setSoLinger(true, 0);
                }
            } catch (IOException e) {
                // The client is already gone; the close below is all that is left to do.
            }
            try {
                socket.close();
            } catch (IOException e) {
                // Nothing left to release.
            }
        }
    }
}
