package at.aimon.core.mcp.transport;

import java.io.BufferedReader;
import java.io.IOException;
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
 * This class is thread-safe. Concurrent requests are serialized via synchronized blocks on the process I/O streams.
 */
public class StdioMcpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpTransport.class);

    private final Process process;
    private final OutputStream stdin;
    private final BufferedReader stdout;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
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
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

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
    public synchronized JsonNode sendRequest(String method, JsonNode params) {
        if (closed || !process.isAlive()) {
            throw new McpTransportException("MCP process is not running");
        }

        try {
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

            // Read response (with timeout)
            long timeoutMillis = requestTimeout.toMillis();
            long deadline = System.currentTimeMillis() + timeoutMillis;

            while (System.currentTimeMillis() < deadline) {
                if (stdout.ready()) {
                    String line = stdout.readLine();
                    if (line == null) {
                        throw new McpTransportException("MCP process closed stdout unexpectedly");
                    }

                    JsonNode response = objectMapper.readTree(line);

                    // Check if this is a response to our request (not a notification)
                    if (response.has("id") && response.get("id").asInt() == requestId) {
                        // Check for JSON-RPC error
                        JsonNode error = response.get("error");
                        if (error != null) {
                            String errorMessage = error.has("message")
                                    ? error.get("message").asText()
                                    : "Unknown error";
                            throw new McpTransportException(
                                    "JSON-RPC error for method '" + method + "': " + errorMessage);
                        }

                        return response.get("result");
                    }
                    // Anything else — server-pushed notifications included — is dropped. Fanning
                    // notifications out to McpNotificationListener is not implemented; see
                    // docs/design/hook/async-rewake.md section 8.
                } else {
                    // Brief sleep to avoid busy-waiting
                    TimeUnit.MILLISECONDS.sleep(10);
                }
            }

            throw new McpTransportException(
                    "Request timeout for method '" + method + "' after " + timeoutMillis + "ms");

        } catch (McpTransportException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("Request interrupted for method '" + method + "'", e);
        } catch (Exception e) {
            throw new McpTransportException("Failed to communicate with MCP process: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void sendNotification(String method, JsonNode params) {
        if (closed || !process.isAlive()) {
            throw new McpTransportException("MCP process is not running");
        }

        try {
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
        } catch (Exception e) {
            throw new McpTransportException("Failed to send notification '" + method + "': " + e.getMessage(), e);
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
