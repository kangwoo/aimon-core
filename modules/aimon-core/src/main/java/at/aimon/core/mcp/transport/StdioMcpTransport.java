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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

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
 * This class is thread-safe. Concurrent requests are serialized by a lock over the process I/O streams, one request on
 * the wire at a time.
 *
 * <h2>Timeout</h2>
 * <p>
 * {@code requestTimeout} is measured from the call to {@link #sendRequest}, and covers both of the waits a request can
 * be held in: the wait for another request to release the transport, and the wait for a complete response line. A
 * request that runs out of time in the first was never written; one that runs out in the second was, so the server may
 * still be working on it, and whatever it writes later &mdash; the whole reply or the rest of a line it had started
 * &mdash; is read by the next request and dropped by its id. Both waits end early on an interrupt.
 *
 * <p>
 * What the timeout does <em>not</em> cover is the write of the request itself. That is a blocking pipe write, and it
 * stalls if the server has stopped reading its stdin and the frame is larger than the pipe's buffer.
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
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);

    /**
     * Serializes use of the process streams. A lock rather than {@code synchronized} because the wait for it has to be
     * timed and interruptible: a monitor can be neither, and a request queued behind a stalled one would otherwise wait
     * out that request's timeout before its own began.
     */
    private final ReentrantLock ioLock = new ReentrantLock();

    // Read state, guarded by ioLock. It belongs to the stream, not to a request: see pollLine.
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
        this.objectMapper = new ObjectMapper();

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
        // The clock starts here, before the wait for the transport: a caller is promised an answer or a timeout within
        // requestTimeout of calling, not within requestTimeout of reaching the head of the queue.
        final long timeoutNanos = requestTimeout.toNanos();
        final long startNanos = System.nanoTime();

        if (closed || !process.isAlive()) {
            throw new McpTransportException("MCP process is not running");
        }

        boolean locked = false;
        try {
            locked = ioLock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!locked) {
                // Nothing was written, so unlike the timeout below this request never reached the server.
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
            stdin.write(requestJson.getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            log.debug("Sent JSON-RPC request: method={}, id={}", method, requestId);

            // Read response (with timeout). pollLine never blocks, so the deadline is looked at every
            // RESPONSE_POLL_MILLIS however the server paces what it writes.
            while (System.nanoTime() - startNanos < timeoutNanos) {
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
            if (locked) {
                ioLock.unlock();
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

    @Override
    public void sendNotification(String method, JsonNode params) {
        if (closed || !process.isAlive()) {
            throw new McpTransportException("MCP process is not running");
        }

        boolean locked = false;
        try {
            // A notification waits for no reply, but it does wait its turn to write, and that wait is bounded the same
            // way a request's is.
            locked = ioLock.tryLock(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!locked) {
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
            stdin.write(notificationJson.getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            log.debug("Sent JSON-RPC notification: method={}", method);
        } catch (McpTransportException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("Notification '" + method + "' interrupted", e);
        } catch (Exception e) {
            throw new McpTransportException("Failed to send notification '" + method + "': " + e.getMessage(), e);
        } finally {
            if (locked) {
                ioLock.unlock();
            }
        }
    }

    @Override
    public boolean isConnected() {
        return !closed && process.isAlive();
    }

    @Override
    public void close() throws Exception {
        closed = true;

        try {
            stdin.close();
        } catch (IOException e) {
            log.debug("Error closing stdin: {}", e.getMessage());
        }

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

}
