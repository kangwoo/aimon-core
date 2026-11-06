package at.aimon.core.tools.code.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.shell.ShellLineListener;

/**
 * One program's run on the shell engine, host side: the listener of the runtime's channel and the writer of its
 * replies ({@code docs/design/tool/programmatic-tool-calling-shell.md} §7).
 *
 * <p>
 * The listener runs on the shell's thread. It only verifies, accumulates and hands calls to the bridge, whose
 * {@code callAsync} queues them without blocking; replies are written on the engine's executor. A violation is not
 * thrown — the shell would swallow the exception and drop the line — but recorded, and the run is stopped through
 * {@code stop}. Once {@link #close() closed} no reply is written: no runtime is left to read it, and a host result must
 * not be left in the sandbox (§8).
 *
 * <p>
 * Thread-safe.
 */
final class ShellProgramRun implements ShellLineListener {

    private static final Logger log = LoggerFactory.getLogger(ShellProgramRun.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /**
     * Lines of the shell's own standard error kept for diagnostics (python3 not found, a crash before the redirect).
     */
    private static final int STDERR_TAIL_LINES = 20;
    private static final int STDERR_TAIL_LINE_CHARS = 500;
    private static final int REPLY_WRITE_ATTEMPTS = 3;

    /** How the runtime said the program ended ({@code DONE}). */
    static final class Done {
        private final String status;
        private final String error;

        Done(String status, String error) {
            this.status = status;
            this.error = error;
        }

        String status() {
            return status;
        }

        Optional<String> error() {
            return Optional.ofNullable(error);
        }
    }

    private final PtcProtocol protocol;
    private final ToolCallBridge bridge;
    private final RuntimeFileSystem files;
    private final String replyDirectory;
    private final long maxOutputBytes;
    private final int maxLogChars;
    private final int maxReplyBytes;
    private final Executor executor;
    private final Runnable stop;

    private final Object lock = new Object();
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final StringBuilder logs = new StringBuilder();
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final AtomicReference<String> violation = new AtomicReference<>();
    private final AtomicReference<String> hostFailure = new AtomicReference<>();
    private long lastSeq;
    private Done done;
    private boolean outputLimitExceeded;
    private volatile boolean closed;

    ShellProgramRun(byte[] key, ToolCallBridge bridge, RuntimeFileSystem files, String replyDirectory,
            CodeExecutionLimits limits, Executor executor, Runnable stop) {
        this.protocol = new PtcProtocol(key);
        this.bridge = Objects.requireNonNull(bridge, "bridge cannot be null");
        this.files = Objects.requireNonNull(files, "files cannot be null");
        this.replyDirectory = Objects.requireNonNull(replyDirectory, "replyDirectory cannot be null");
        this.maxOutputBytes = limits.getMaxScriptOutputBytes();
        this.maxLogChars = limits.getMaxLogChars();
        this.maxReplyBytes = ShellCodeExecutionEngine.MAX_REPLY_BYTES;
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.stop = Objects.requireNonNull(stop, "stop cannot be null");
    }

    @Override
    public void onStdoutLine(String line) {
        if (closed || violation.get() != null) {
            return;
        }
        final Optional<PtcProtocol.Line> verified = protocol.verify(line);
        if (verified.isEmpty()) {
            violate("a channel line failed authentication or is malformed");
            return;
        }
        final PtcProtocol.Line parsed = verified.get();
        synchronized (lock) {
            if (done != null) {
                violate("a channel line arrived after DONE");
                return;
            }
            if (parsed.seq() != lastSeq + 1) {
                violate("channel line seq " + parsed.seq() + " after " + lastSeq
                        + " (duplicate, reordered or skipped)");
                return;
            }
            lastSeq = parsed.seq();
        }
        switch (parsed.type()) {
            case "CALL" -> call(parsed.seq(), parsed.payload());
            case "OUT" -> output(parsed.payload());
            case "ERR" -> log(parsed.payload());
            case "DONE" -> finish(parsed.payload());
            default -> violate("unknown channel line type");
        }
    }

    @Override
    public void onStderrLine(String line) {
        synchronized (lock) {
            stderrTail
                    .addLast(line.length() > STDERR_TAIL_LINE_CHARS ? line.substring(0, STDERR_TAIL_LINE_CHARS) : line);
            while (stderrTail.size() > STDERR_TAIL_LINES) {
                stderrTail.removeFirst();
            }
        }
    }

    private void call(long seq, String payload) {
        final Map<String, Object> request;
        try {
            request = JSON.readValue(payload, MAP);
        } catch (JsonProcessingException e) {
            violate("a CALL line does not carry a request");
            return;
        }
        if (!(request.get("tool") instanceof String tool) || !(request.get("args") instanceof Map<?, ?> rawArgs)) {
            violate("a CALL line does not carry a request");
            return;
        }
        final Map<String, Object> args = new LinkedHashMap<>();
        rawArgs.forEach((k, v) -> args.put(String.valueOf(k), v));
        bridge.callAsync(tool, args).whenCompleteAsync((result, failure) -> {
            final ToolDispatchResult answer = failure == null
                    ? result
                    : ToolDispatchResult.builder().toolUseId("ptc")
                            .text("Tool execution error: " + failure.getMessage()).error(true).build();
            writeReply(seq, answer);
        }, executor);
    }

    private void output(String payload) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            violate("an OUT line is not base64");
            return;
        }
        boolean stopNow = false;
        synchronized (lock) {
            final long room = maxOutputBytes - stdout.size();
            if (bytes.length > room) {
                stdout.write(bytes, 0, (int) Math.max(0, room));
                if (!outputLimitExceeded) {
                    outputLimitExceeded = true;
                    stopNow = true;
                }
            } else {
                stdout.write(bytes, 0, bytes.length);
            }
        }
        if (stopNow) {
            stop.run();
        }
    }

    private void log(String payload) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            violate("an ERR line is not base64");
            return;
        }
        synchronized (lock) {
            logs.append(new String(bytes, StandardCharsets.UTF_8));
            if (logs.length() > maxLogChars) {
                logs.delete(0, logs.length() - maxLogChars);
            }
        }
    }

    private void finish(String payload) {
        final Map<String, Object> fields;
        try {
            fields = JSON.readValue(payload, MAP);
        } catch (JsonProcessingException e) {
            violate("a DONE line does not carry a status");
            return;
        }
        if (!(fields.get("status") instanceof String status)) {
            violate("a DONE line does not carry a status");
            return;
        }
        final Object error = fields.get("error");
        synchronized (lock) {
            done = new Done(status, error instanceof String text ? text : null);
        }
    }

    private void writeReply(long seq, ToolDispatchResult result) {
        if (closed) {
            return;
        }
        final byte[] payload = replyPayload(result);
        final String path = replyDirectory + "/" + seq;
        final byte[] file = protocol.reply(seq, payload);
        IOException last = null;
        for (int attempt = 1; attempt <= REPLY_WRITE_ATTEMPTS; attempt++) {
            if (closed) {
                return;
            }
            try {
                files.writeNew(path, file);
                return;
            } catch (FileAlreadyExistsException e) {
                violate("reply " + seq + " already existed: someone else wrote it first");
                return;
            } catch (IOException e) {
                last = e;
                sleepQuietly(50L * attempt);
            }
        }
        log.warn("ExecuteCode: could not write reply {} ({}); ending the program", seq, last.toString());
        if (hostFailure.compareAndSet(null, "the reply to tool call " + seq + " could not be written: " + last)) {
            stop.run();
        }
    }

    /** {"text", "is_error", "retryable"}, the text cut so the payload stays within the reply limit (§7). */
    private byte[] replyPayload(ToolDispatchResult result) {
        String text = result.getText();
        while (true) {
            final Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("text", text);
            reply.put("is_error", result.isError());
            reply.put("retryable", result.isRetryable());
            final byte[] bytes;
            try {
                bytes = JSON.writeValueAsBytes(reply);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("A reply could not be encoded", e);
            }
            if (bytes.length <= maxReplyBytes) {
                return bytes;
            }
            final int over = bytes.length - maxReplyBytes;
            final int keep = Math.max(0, text.length() - over - 64);
            final int dropped = result.getText().length() - keep;
            text = result.getText().substring(0, keep) + "\n[truncated " + dropped + " chars]";
        }
    }

    private void violate(String message) {
        if (violation.compareAndSet(null, message)) {
            stop.run();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops writing replies: the run is over (§8). */
    void close() {
        closed = true;
    }

    Optional<String> violation() {
        return Optional.ofNullable(violation.get());
    }

    Optional<String> hostFailure() {
        return Optional.ofNullable(hostFailure.get());
    }

    Optional<Done> done() {
        synchronized (lock) {
            return Optional.ofNullable(done);
        }
    }

    boolean outputLimitExceeded() {
        synchronized (lock) {
            return outputLimitExceeded;
        }
    }

    String stdout() {
        synchronized (lock) {
            return stdout.toString(StandardCharsets.UTF_8);
        }
    }

    String logs() {
        synchronized (lock) {
            return logs.toString();
        }
    }

    String shellStderr() {
        synchronized (lock) {
            return String.join("\n", stderrTail);
        }
    }
}
