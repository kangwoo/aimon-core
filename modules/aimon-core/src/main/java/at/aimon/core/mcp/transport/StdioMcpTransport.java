package at.aimon.core.mcp.transport;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.mcp.exception.McpTransportException;

/**
 * Stdio-based MCP transport for local process communication.
 *
 * <p>
 * Starts a local process and communicates via stdin/stdout using JSON-RPC over newline-delimited JSON.
 *
 * <h2>Connection Establishment</h2>
 * <p>
 * The process is started in the constructor. If the process fails to start, a {@link McpTransportException} is thrown.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is thread-safe. Concurrent requests are serialized by a permit over the process I/O streams, one request
 * on the wire at a time.
 *
 * <h2>Timeout</h2>
 * <p>
 * {@code requestTimeout} bounds each of the three waits a request can be held in: the wait for another request to
 * release the transport, the write of the request's frame, and, once that is written, the wait for a complete response
 * line. They are counted separately, so a request that was written always has the whole of it to be answered in. The
 * write takes no time worth counting unless the server is not reading its stdin, so a call returns within twice
 * {@code requestTimeout} however many requests are queued, and within three times it at the very most. All three waits
 * end early on an interrupt.
 *
 * <p>
 * What a request that ran out of time left behind depends on the wait it was in:
 * <ul>
 * <li><b>waiting for the transport</b> &mdash; nothing. It was never written, and says so.
 * <li><b>writing</b> &mdash; a frame that is partly in the pipe. It is not cut short there, which would hand the server
 * a line made of two requests: the write is left to finish on the writer thread and the transport stays taken until it
 * has, so the requests behind it fail as never sent. If the server reads again it gets the whole frame and may run it,
 * so this request does <em>not</em> say it was never sent. Nothing is killed to end the write; {@link #close()} is what
 * ends it for a server that never reads.
 * <li><b>waiting for the response</b> &mdash; a request the server may still be working on. Whatever it writes later
 * &mdash; the whole reply or the rest of a line it had started &mdash; is read by the next request and dropped by its
 * id.
 * </ul>
 */
public class StdioMcpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpTransport.class);

    /** How long a request sleeps between looks at the pipe when no complete line is waiting. */
    private static final long RESPONSE_POLL_MILLIS = 10;

    private static final int READ_CHUNK_BYTES = 8192;

    private final Process process;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    /** {@link #requestTimeout} in nanoseconds, saturated: a timeout too long to count is one that never ends. */
    private final long requestTimeoutNanos;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);

    /**
     * Serializes use of the process streams. Not {@code synchronized}, because the wait for it has to be timed and
     * interruptible: a monitor can be neither, and a request queued behind a stalled one would otherwise wait out that
     * request's timeout before its own began. And a semaphore rather than a lock, because the permit is not always
     * returned by the thread that took it: a request that gives up in the middle of its write leaves the permit with
     * that write. See {@link #writeFrame}.
     */
    private final Semaphore ioPermit = new Semaphore(1);

    /**
     * Runs the pipe writes. A pipe write blocks for as long as the server leaves its stdin unread, and nothing but the
     * end of the process ends one, so no request thread makes that call itself: it waits for this thread to have made
     * it. One thread is enough, since {@link #ioPermit} admits one frame at a time. Started on the first frame.
     */
    private final ExecutorService writer;

    /** The write on the wire now, if any: what {@link #close()} asks to learn whether the server is reading. */
    private volatile CompletableFuture<Void> writeInFlight;

    // Read state, guarded by ioPermit. It belongs to the stream, not to a request: see pollLine.
    private final byte[] chunk = new byte[READ_CHUNK_BYTES];
    private int chunkPosition;
    private int chunkLength;
    private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();

    private volatile boolean closed = false;

    /**
     * Creates a StdioMcpTransport and starts the local process.
     *
     * @param command
     *            the command to execute
     * @param args
     *            command arguments
     * @param env
     *            environment variables for the process
     * @param requestTimeout
     *            timeout for each request
     * @throws McpTransportException
     *             if the process fails to start
     */
    public StdioMcpTransport(String command, List<String> args, Map<String, String> env, Duration requestTimeout) {
        Objects.requireNonNull(command, "command cannot be null");
        Objects.requireNonNull(args, "args cannot be null");
        Objects.requireNonNull(env, "env cannot be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout cannot be null");
        this.requestTimeoutNanos = toNanosSaturating(requestTimeout);
        this.objectMapper = new ObjectMapper();
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "mcp-stdin-writer-" + command);
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<String> commandLine = new ArrayList<>();
            commandLine.add(command);
            commandLine.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(commandLine);
            pb.environment().putAll(env);
            pb.redirectErrorStream(false);

            this.process = pb.start();
            this.stdin = process.getOutputStream();
            // Raw bytes, not a Reader: a Reader can only say that some bytes are ready, and then blocks until the line
            // (or even the character) they begin is complete. See pollLine.
            this.stdout = process.getInputStream();

            // Stdio MCP servers use stdout for JSON-RPC and conventionally log to stderr. Because
            // redirectErrorStream is false (mixing log lines into stdout would corrupt the JSON-RPC stream), the
            // stderr pipe must be drained by a dedicated reader; otherwise a chatty server that writes past the OS
            // pipe buffer (~64KB) blocks on its next stderr write and hangs the whole session.
            startStderrDrainer(command);

            log.debug("Started MCP process: {}", String.join(" ", commandLine));
        } catch (IOException e) {
            writer.shutdownNow();
            throw new McpTransportException("Failed to start MCP process: " + command, e);
        }
    }

    private void startStderrDrainer(String command) {
        final Thread drainer = new Thread(() -> {
            try (BufferedReader stderr = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stderr.readLine()) != null) {
                    log.debug("[mcp-stderr] {}", line);
                }
            } catch (IOException e) {
                if (!closed) {
                    log.debug("MCP stderr drain ended for {}: {}", command, e.getMessage());
                }
            }
        }, "mcp-stderr-drain-" + command);
        drainer.setDaemon(true);
        drainer.start();
    }

    @Override
    public JsonNode sendRequest(String method, JsonNode params) {
        // Three waits, each bounded by requestTimeout: for the transport, for the write, for the answer. They do not
        // share one budget. A request that reached the transport with a sliver of time left would be written, run by
        // the server, and reported as a timeout the caller could not tell from one that was never sent.
        final long timeoutNanos = requestTimeoutNanos;

        if (closed || !process.isAlive()) {
            throw new McpTransportException("MCP process is not running");
        }

        final IoTurn turn = new IoTurn();
        try {
            if (!turn.take(timeoutNanos)) {
                // Nothing was written, so unlike the timeouts below this request never reached the server.
                throw new McpTransportException("Request timeout for method '" + method + "' after "
                        + requestTimeout.toMillis() + "ms (never sent: the transport was busy with another request)");
            }
            // Asked again: the process may have gone, or the transport been closed, while this request queued.
            if (closed || !process.isAlive()) {
                throw new McpTransportException("MCP process is not running");
            }

            int requestId = requestIdCounter.getAndIncrement();

            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", requestId);
            request.put("method", method);
            request.set("params", params);

            // Send request
            String requestJson = objectMapper.writeValueAsString(request) + "\n";
            if (!writeFrame(requestJson.getBytes(StandardCharsets.UTF_8), turn)) {
                throw new McpTransportException("Request timeout for method '" + method + "' after "
                        + requestTimeout.toMillis() + "ms (the server is not reading its input: the request is still"
                        + " being written, and the server will receive it if it reads again)");
            }
            final long sentNanos = System.nanoTime();

            log.debug("Sent JSON-RPC request: method={}, id={}", method, requestId);

            // Read response (with timeout). pollLine never blocks, so the deadline is looked at every
            // RESPONSE_POLL_MILLIS however the server paces what it writes.
            while (System.nanoTime() - sentNanos < timeoutNanos) {
                final String line = pollLine();
                if (line == null) {
                    // Brief sleep to avoid busy-waiting
                    TimeUnit.MILLISECONDS.sleep(RESPONSE_POLL_MILLIS);
                    continue;
                }

                JsonNode response = objectMapper.readTree(line);

                // Check if this is a response to our request (not a notification)
                if (response.has("id") && response.get("id").asInt() == requestId) {
                    // Check for JSON-RPC error
                    JsonNode error = response.get("error");
                    if (error != null) {
                        String errorMessage = error.has("message") ? error.get("message").asText() : "Unknown error";
                        throw new McpTransportException("JSON-RPC error for method '" + method + "': " + errorMessage);
                    }

                    return response.get("result");
                }
                // Anything else — server-pushed notifications included — is dropped. Fanning
                // notifications out to McpNotificationListener is not implemented; see
                // docs/design/hook/async-rewake.md section 8.
            }

            throw new McpTransportException(
                    "Request timeout for method '" + method + "' after " + requestTimeout.toMillis() + "ms");

        } catch (McpTransportException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("Request interrupted for method '" + method + "'", e);
        } catch (Exception e) {
            throw new McpTransportException("Failed to communicate with MCP process: " + e.getMessage(), e);
        } finally {
            turn.end();
        }
    }

    /**
     * Writes one frame to the server's stdin, waiting at most {@code requestTimeout} for the write to end.
     *
     * <p>
     * The write is made on {@link #writer} and awaited here, which is what lets the wait be timed and interruptible.
     * When the wait ends first &mdash; time is up, or the caller is interrupted &mdash; the write is <em>not</em>
     * abandoned: part of the frame is in the pipe, and the next frame written after it would be read by the server as
     * the rest of the same line. The frame belongs to the stream from then on, as an unfinished response line does
     * (see {@link #pollLine}): the write runs on, and {@code turn} is handed over to it, so the transport stays taken
     * until it has ended one way or the other.
     *
     * @return {@code true} when the frame was written, {@code false} when time ran out first
     * @throws InterruptedException
     *             if the caller was interrupted first
     * @throws IOException
     *             if the write failed
     */
    private boolean writeFrame(byte[] frame, IoTurn turn) throws InterruptedException, IOException {
        final CompletableFuture<Void> write = new CompletableFuture<>();
        writeInFlight = write;
        writer.execute(() -> {
            try {
                stdin.write(frame);
                stdin.flush();
                write.complete(null);
            } catch (Throwable t) {
                write.completeExceptionally(t);
            }
        });
        try {
            write.get(requestTimeoutNanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException e) {
            turn.handOverTo(write);
            return false;
        } catch (InterruptedException e) {
            turn.handOverTo(write);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        }
    }

    /**
     * One caller's hold on {@link #ioPermit}: taken once, given back exactly once, by the caller in its
     * {@code finally} or by the write it left behind.
     */
    private final class IoTurn {

        private boolean held;

        boolean take(long timeoutNanos) throws InterruptedException {
            held = ioPermit.tryAcquire(timeoutNanos, TimeUnit.NANOSECONDS);
            return held;
        }

        /** Leaves the permit with a write that outlives the caller's wait for it. */
        void handOverTo(CompletableFuture<Void> write) {
            held = false;
            // Runs here and now when the write ended between the wait giving up and this line.
            write.whenComplete((ignored, thrown) -> ioPermit.release());
        }

        void end() {
            if (held) {
                held = false;
                writeInFlight = null;
                ioPermit.release();
            }
        }
    }

    /**
     * Returns the next complete line the server has written, or {@code null} if there is none yet. Never blocks.
     *
     * <p>
     * Only bytes the pipe already holds are read, so a server that stops in the middle of a line costs the caller
     * nothing but another poll. The bytes of such an unfinished line stay in {@link #pendingLine} <em>across
     * requests</em>: when the request that was waiting for it times out and the rest arrives later, it completes the
     * frame it belongs to (which the next request then drops by its id) instead of being parsed as a line of its own.
     *
     * <p>
     * Lines are split on the byte {@code 0x0A} before decoding. That is safe in UTF-8, where no multi-byte sequence
     * contains it, and it is what keeps a half-written multi-byte character from blocking a decoder.
     */
    private String pollLine() throws IOException {
        while (true) {
            while (chunkPosition < chunkLength) {
                final byte b = chunk[chunkPosition++];
                if (b == '\n') {
                    String line = pendingLine.toString(StandardCharsets.UTF_8);
                    pendingLine.reset();
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    return line;
                }
                pendingLine.write(b);
            }

            final int available = stdout.available();
            if (available <= 0) {
                return null;
            }
            final int read = stdout.read(chunk, 0, Math.min(available, chunk.length));
            if (read < 0) {
                throw new McpTransportException("MCP process closed stdout unexpectedly");
            }
            chunkPosition = 0;
            chunkLength = read;
        }
    }

    private static long toNanosSaturating(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void sendNotification(String method, JsonNode params) {
        if (closed || !process.isAlive()) {
            throw new McpTransportException("MCP process is not running");
        }

        final IoTurn turn = new IoTurn();
        try {
            // A notification waits for no reply, but it does wait its turn to write and for the write itself, and both
            // waits are bounded the same way a request's are.
            if (!turn.take(requestTimeoutNanos)) {
                throw new McpTransportException("Timeout sending notification '" + method + "' after "
                        + requestTimeout.toMillis() + "ms (never sent: the transport was busy with another request)");
            }
            if (closed || !process.isAlive()) {
                throw new McpTransportException("MCP process is not running");
            }

            final ObjectNode notification = objectMapper.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            notification.set("params", params);
            // Deliberately no "id": this is a one-way notification. A compliant server sends no reply, so we must
            // not wait for one (that is the request/response path in sendRequest).

            final String notificationJson = objectMapper.writeValueAsString(notification) + "\n";
            if (!writeFrame(notificationJson.getBytes(StandardCharsets.UTF_8), turn)) {
                throw new McpTransportException("Timeout sending notification '" + method + "' after "
                        + requestTimeout.toMillis() + "ms (the server is not reading its input: the notification is"
                        + " still being written, and the server will receive it if it reads again)");
            }

            log.debug("Sent JSON-RPC notification: method={}", method);
        } catch (McpTransportException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("Notification '" + method + "' interrupted", e);
        } catch (Exception e) {
            throw new McpTransportException("Failed to send notification '" + method + "': " + e.getMessage(), e);
        } finally {
            turn.end();
        }
    }

    @Override
    public boolean isConnected() {
        return !closed && process.isAlive();
    }

    @Override
    public void close() throws Exception {
        closed = true;

        // Closing stdin is how a stdio server is asked to leave, but it cannot be asked while a write is stalled: the
        // server is not reading, so it would not see the end of input, and the close itself would queue behind that
        // write, which holds the stream's monitor. Ending the process is the one thing that ends such a write.
        final CompletableFuture<Void> write = writeInFlight;
        if (write != null && !write.isDone()) {
            process.destroyForcibly();
        }
        // Always on the writer thread, behind whatever write is there: this thread must not wait for a monitor it
        // cannot be sure will be freed. That holds for a write that starts after the look above too, and for one a
        // child of the server keeps open after the server is gone. The process is ended below either way.
        try {
            writer.execute(this::closeStdin);
        } catch (RejectedExecutionException alreadyClosed) {
            log.debug("StdioMcpTransport closed more than once");
        }
        writer.shutdown();

        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    log.warn("MCP process did not terminate gracefully, forced shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        log.debug("StdioMcpTransport closed");
    }

    private void closeStdin() {
        try {
            stdin.close();
        } catch (IOException e) {
            log.debug("Error closing stdin: {}", e.getMessage());
        }
    }

}
