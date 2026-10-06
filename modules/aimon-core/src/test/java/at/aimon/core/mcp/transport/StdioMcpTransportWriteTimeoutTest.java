package at.aimon.core.mcp.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.mcp.exception.McpTransportException;

/**
 * {@code requestTimeout} bounds the <em>write</em> of a frame, the third wait of {@link StdioMcpTransport}.
 *
 * <p>
 * A frame larger than the pipe's buffer, sent to a server that is not reading its stdin, used to hold the caller in a
 * blocking pipe write that neither the timeout nor an interrupt could end. Every frame here is larger than any pipe
 * buffer the supported platforms use (64 KB), and every call is run on a worker thread and awaited with a bound of its
 * own, so a regression fails the assertion instead of hanging the build.
 */
@DisabledOnOs(OS.WINDOWS)
class StdioMcpTransportWriteTimeoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How long the test itself waits for a call that should have ended long before. */
    private static final long HANG_GUARD_SECONDS = 4;

    /** Well past the pipe buffer, small enough for a shell's byte-at-a-time {@code read} to take in quickly. */
    private static final int LARGE_FRAME_CHARS = 256 * 1024;

    /** A server that answers every line it reads with the next id, as a healthy one would. */
    private static final String ANSWER_EVERY_LINE = "i=0; while IFS= read -r line; do i=$((i+1)); "
            + "printf '{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"n\":%d}}\\n' $i $i; done";

    private final ExecutorService callers = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "stdio-write-test-caller");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void stopCallers() {
        callers.shutdownNow();
    }

    @Test
    void aFrameTheServerDoesNotReadDoesNotHoldTheRequestPastRequestTimeout() throws Exception {
        final StdioMcpTransport transport = transport("exec sleep 30", Duration.ofMillis(300));
        try {
            final Outcome outcome = await(callers.submit(() -> request(transport, LARGE_FRAME_CHARS)));

            assertThat(outcome.failure).isInstanceOf(McpTransportException.class)
                    .hasMessageContaining("Request timeout");
            assertThat(outcome.elapsedMillis).isLessThan(2_000L);
        } finally {
            closeWithinTheGuard(transport);
        }
    }

    @Test
    void aNotificationTheServerDoesNotReadDoesNotHoldTheCallerPastRequestTimeout() throws Exception {
        final StdioMcpTransport transport = transport("exec sleep 30", Duration.ofMillis(300));
        try {
            final Outcome outcome = await(callers.submit(() -> notification(transport, LARGE_FRAME_CHARS)));

            assertThat(outcome.failure).isInstanceOf(McpTransportException.class).hasMessageContaining("Timeout");
            assertThat(outcome.elapsedMillis).isLessThan(2_000L);
        } finally {
            closeWithinTheGuard(transport);
        }
    }

    @Test
    void interruptEndsARequestStalledInItsWrite() throws Exception {
        final StdioMcpTransport transport = transport("exec sleep 30", Duration.ofSeconds(20));
        try {
            final CountDownLatch running = new CountDownLatch(1);
            final AtomicBoolean interruptedOnReturn = new AtomicBoolean();
            final Thread[] caller = new Thread[1];
            final Future<Outcome> future = callers.submit(() -> {
                caller[0] = Thread.currentThread();
                running.countDown();
                final Outcome outcome = request(transport, LARGE_FRAME_CHARS);
                interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                return outcome;
            });
            assertThat(running.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
            // Long enough for the write to have filled the pipe.
            Thread.sleep(300);
            caller[0].interrupt();

            final Outcome outcome = await(future);

            assertThat(outcome.failure).isInstanceOf(McpTransportException.class)
                    .hasMessageContaining("Request interrupted");
            assertThat(interruptedOnReturn).isTrue();
        } finally {
            closeWithinTheGuard(transport);
        }
    }

    @Test
    void aRequestBehindAStalledWriteIsNeverSentAndSaysSo() throws Exception {
        final long timeoutMillis = 300;
        final StdioMcpTransport transport = transport("exec sleep 30", Duration.ofMillis(timeoutMillis));
        try {
            assertThat(await(callers.submit(() -> request(transport, LARGE_FRAME_CHARS))).failure)
                    .hasMessageContaining("Request timeout");

            // The first frame is still half in the pipe. Writing a second one into the middle of it would hand the
            // server one line made of two requests, so the transport stays taken until that write has ended.
            final Outcome second = await(callers.submit(() -> request(transport, 0)));

            assertThat(second.failure).isInstanceOf(McpTransportException.class).hasMessageContaining("never sent");
            assertThat(second.elapsedMillis).isLessThan(timeoutMillis + 1_500);
        } finally {
            closeWithinTheGuard(transport);
        }
    }

    @Test
    void aFrameTheServerReadsLateIsFinishedAndTheTransportIsUsableAgain() throws Exception {
        // The server is busy for a second before it reads anything. The first request gives up in its write; the frame
        // is not cut short, so once the server reads it is whole, is answered, and that answer is dropped by its id.
        final StdioMcpTransport transport = transport("sleep 1; " + ANSWER_EVERY_LINE, Duration.ofMillis(400));
        try {
            final Outcome first = await(callers.submit(() -> request(transport, LARGE_FRAME_CHARS)));
            assertThat(first.failure).isInstanceOf(McpTransportException.class).hasMessageContaining("Request timeout");

            Outcome later = null;
            final long giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < giveUpAt) {
                later = await(callers.submit(() -> request(transport, 0)));
                if (later.failure == null) {
                    break;
                }
                assertThat(later.failure).hasMessageContaining("never sent");
            }

            assertThat(later).isNotNull();
            assertThat(later.failure).isNull();
            // The server counts lines: had the first frame been cut short or run into the second, this would be 1.
            assertThat(later.result.path("n").asInt()).isEqualTo(2);
        } finally {
            closeWithinTheGuard(transport);
        }
    }

    @Test
    void aFrameLargerThanThePipeStillReachesAServerThatReads() throws Exception {
        // The input that worked before the write was bounded: the write blocks until the server has read the pipe
        // empty a few times over, and ends well inside the timeout.
        final StdioMcpTransport transport = transport(ANSWER_EVERY_LINE, Duration.ofSeconds(20));
        try {
            final Future<Outcome> pending = callers.submit(() -> request(transport, LARGE_FRAME_CHARS));
            final Outcome outcome = pending.get(20, TimeUnit.SECONDS);

            assertThat(outcome.failure).isNull();
            assertThat(outcome.result.path("n").asInt()).isEqualTo(1);
        } finally {
            closeWithinTheGuard(transport);
        }
    }

    @Test
    void closeEndsAStalledWriteInsteadOfQueueingBehindIt() throws Exception {
        final StdioMcpTransport transport = transport("exec sleep 30", Duration.ofSeconds(20));
        final Future<Outcome> stalled = callers.submit(() -> request(transport, LARGE_FRAME_CHARS));
        Thread.sleep(300);

        closeWithinTheGuard(transport);

        assertThat(transport.isConnected()).isFalse();
        // Closing killed the process, so the write failed and the request came back long before its twenty seconds.
        assertThat(await(stalled).failure).isInstanceOf(McpTransportException.class);
    }

    private void closeWithinTheGuard(StdioMcpTransport transport) throws Exception {
        final Future<?> closing = callers.submit(() -> {
            transport.close();
            return null;
        });
        closing.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);
    }

    private static StdioMcpTransport transport(String script, Duration requestTimeout) {
        return new StdioMcpTransport("/bin/sh", List.of("-c", script), Map.of(), requestTimeout);
    }

    private static ObjectNode params(int paddingChars) {
        final ObjectNode params = MAPPER.createObjectNode();
        params.put("padding", "x".repeat(paddingChars));
        return params;
    }

    private static Outcome request(StdioMcpTransport transport, int paddingChars) {
        final long start = System.nanoTime();
        try {
            final JsonNode result = transport.sendRequest("tools/call", params(paddingChars));
            return new Outcome(result, null, elapsedMillis(start));
        } catch (RuntimeException e) {
            return new Outcome(null, e, elapsedMillis(start));
        }
    }

    private static Outcome notification(StdioMcpTransport transport, int paddingChars) {
        final long start = System.nanoTime();
        try {
            transport.sendNotification("notifications/progress", params(paddingChars));
            return new Outcome(null, null, elapsedMillis(start));
        } catch (RuntimeException e) {
            return new Outcome(null, e, elapsedMillis(start));
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static Outcome await(Future<Outcome> future) throws Exception {
        return future.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS);
    }

    private static final class Outcome {

        private final JsonNode result;
        private final RuntimeException failure;
        private final long elapsedMillis;

        private Outcome(JsonNode result, RuntimeException failure, long elapsedMillis) {
            this.result = result;
            this.failure = failure;
            this.elapsedMillis = elapsedMillis;
        }
    }
}
