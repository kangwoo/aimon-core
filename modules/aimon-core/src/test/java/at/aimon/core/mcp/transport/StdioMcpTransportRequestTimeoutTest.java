package at.aimon.core.mcp.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
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

import at.aimon.core.mcp.exception.McpTransportException;

/**
 * {@code requestTimeout} bounds the whole of {@link StdioMcpTransport#sendRequest}, not only the part of it that waits
 * for the first byte of a response.
 *
 * <p>
 * Two waits used to fall outside it. A server that wrote the start of a line and stalled held the request in a
 * blocking {@code readLine()}, and a request that arrived while another one held the transport waited for it without
 * the clock running. Every request here is run on a worker thread and awaited with a bound of its own, so a regression
 * fails the assertion instead of hanging the build.
 */
@DisabledOnOs(OS.WINDOWS)
class StdioMcpTransportRequestTimeoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How long the test itself waits for a request that should have ended long before. */
    private static final long HANG_GUARD_SECONDS = 4;

    private final ExecutorService callers = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "stdio-timeout-test-caller");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void stopCallers() {
        callers.shutdownNow();
    }

    @Test
    void partialLineDoesNotHoldTheRequestPastRequestTimeout() throws Exception {
        // The server answers with the start of a frame and never writes the newline.
        final String script = "read -r line; printf '%s' '{\"jsonrpc\":\"2.0\",\"id\":1,'; exec sleep 30";

        try (StdioMcpTransport transport = transport(script, Duration.ofMillis(300))) {
            final Outcome outcome = await(callers.submit(() -> request(transport)));

            assertThat(outcome.failure).isInstanceOf(McpTransportException.class)
                    .hasMessageContaining("Request timeout");
            assertThat(outcome.elapsedMillis).isLessThan(2_000L);
        }
    }

    @Test
    void lateRemainderOfATimedOutLineIsNotReadAsTheNextResponse() throws Exception {
        // Request 1 gets half a frame and times out. The rest of that frame only arrives once request 2 is on the
        // wire, immediately ahead of request 2's own response: the remainder must complete the abandoned frame (id 1,
        // dropped as a stale reply) rather than be parsed as a line of its own.
        final String script = "read -r line; printf '%s' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":'; "
                + "read -r line; printf '%s\\n' '{\"stale\":true}}'; "
                + "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"fresh\":true}}'; exec sleep 30";

        try (StdioMcpTransport transport = transport(script, Duration.ofMillis(400))) {
            final Outcome first = await(callers.submit(() -> request(transport)));
            assertThat(first.failure).isInstanceOf(McpTransportException.class).hasMessageContaining("Request timeout");

            final Outcome second = await(callers.submit(() -> request(transport)));

            assertThat(second.failure).isNull();
            assertThat(second.result).isNotNull();
            assertThat(second.result.has("stale")).isFalse();
            assertThat(second.result.path("fresh").asBoolean()).isTrue();
        }
    }

    @Test
    void waitingForAnotherRequestCountsAgainstRequestTimeout() throws Exception {
        // The server never answers, so each request holds the transport for a full requestTimeout. Run one after the
        // other, four of them take four timeouts; the last one must not be allowed to.
        final int requests = 4;
        final long timeoutMillis = 600;
        final String script = "while read -r line; do :; done";

        try (StdioMcpTransport transport = transport(script, Duration.ofMillis(timeoutMillis))) {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<Outcome>> pending = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                pending.add(callers.submit(() -> {
                    start.await();
                    return request(transport);
                }));
            }
            start.countDown();

            for (Future<Outcome> future : pending) {
                final Outcome outcome = await(future);
                assertThat(outcome.failure).isInstanceOf(McpTransportException.class)
                        .hasMessageContaining("Request timeout");
                // Serialized, the slowest would take requests * timeoutMillis = 2400ms. The bound is two timeouts and
                // not one: a request that reaches the transport just inside its wait is sent, and then has a whole
                // requestTimeout to be answered in.
                assertThat(outcome.elapsedMillis).isLessThan(timeoutMillis * 2 + 400);
            }
        }
    }

    @Test
    void aRequestThatWaitedItsTurnStillHasAWholeRequestTimeoutToBeAnsweredIn() throws Exception {
        // A healthy server that takes 600ms a request, and three callers at once. The third waits 1200ms for the
        // transport and is answered 600ms after it is sent, 1800ms after it was called. Cutting it at 1500ms from the
        // call would report a failure for a request the server went on to run.
        final String script = "i=0; while read -r line; do i=$((i+1)); if [ $i -gt 1 ]; then sleep 0.6; fi; "
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{}}\\n' $i; done";

        try (StdioMcpTransport transport = transport(script, Duration.ofMillis(1_500))) {
            // The first request is answered at once: it is here so that the shell is up before anything is timed.
            assertThat(await(callers.submit(() -> request(transport))).failure).isNull();

            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<Outcome>> pending = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                pending.add(callers.submit(() -> {
                    start.await();
                    return request(transport);
                }));
            }
            start.countDown();

            for (Future<Outcome> future : pending) {
                assertThat(await(future).failure).isNull();
            }
        }
    }

    @Test
    void aRequestTimeoutTooLongToCountInNanosecondsIsNotAnError() throws Exception {
        final String script = "read -r line; printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}'; exec sleep 30";

        try (StdioMcpTransport transport = transport(script, Duration.ofDays(365_000))) {
            final Outcome outcome = await(callers.submit(() -> request(transport)));

            assertThat(outcome.failure).isNull();
        }
    }

    @Test
    void interruptEndsARequestStalledOnAPartialLine() throws Exception {
        final String script = "read -r line; printf '%s' '{\"jsonrpc\":\"2.0\",\"id\":1,'; exec sleep 30";

        try (StdioMcpTransport transport = transport(script, Duration.ofSeconds(20))) {
            final CountDownLatch running = new CountDownLatch(1);
            final AtomicBoolean interruptedOnReturn = new AtomicBoolean();
            final Thread[] caller = new Thread[1];
            final Future<Outcome> future = callers.submit(() -> {
                caller[0] = Thread.currentThread();
                running.countDown();
                final Outcome outcome = request(transport);
                interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                return outcome;
            });
            assertThat(running.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
            // Long enough for the request to be written and for the half frame to come back.
            Thread.sleep(300);
            caller[0].interrupt();

            final Outcome outcome = await(future);

            assertThat(outcome.failure).isInstanceOf(McpTransportException.class)
                    .hasMessageContaining("Request interrupted");
            assertThat(interruptedOnReturn).isTrue();
        }
    }

    @Test
    void interruptEndsARequestWaitingForAnotherRequest() throws Exception {
        final String script = "while read -r line; do :; done";

        try (StdioMcpTransport transport = transport(script, Duration.ofSeconds(3))) {
            final Future<Outcome> holder = callers.submit(() -> request(transport));
            // Let the first request take the transport before the second one asks for it.
            Thread.sleep(300);

            final CountDownLatch running = new CountDownLatch(1);
            final Thread[] caller = new Thread[1];
            final Future<Outcome> waiter = callers.submit(() -> {
                caller[0] = Thread.currentThread();
                running.countDown();
                return request(transport);
            });
            assertThat(running.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            caller[0].interrupt();

            // The holder is still inside its own three seconds, so only the interrupt can end the waiter this soon.
            final Outcome outcome = waiter.get(1_500, TimeUnit.MILLISECONDS);

            assertThat(outcome.failure).isInstanceOf(McpTransportException.class)
                    .hasMessageContaining("Request interrupted");
            holder.cancel(true);
        }
    }

    @Test
    void multiByteResponseSurvivesByteLevelReading() throws Exception {
        // U+D55C (ED 95 9C) written as octal escapes so the script itself stays ASCII whatever the platform encoding.
        final String script = "read -r line; "
                + "printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"text\":\"\\355\\225\\234\"}}\\r\\n'; exec sleep 30";

        try (StdioMcpTransport transport = transport(script, Duration.ofSeconds(2))) {
            final Outcome outcome = await(callers.submit(() -> request(transport)));

            assertThat(outcome.failure).isNull();
            assertThat(outcome.result.path("text").asText()).isEqualTo("한");
        }
    }

    private static StdioMcpTransport transport(String script, Duration requestTimeout) {
        return new StdioMcpTransport("/bin/sh", List.of("-c", script), Map.of(), requestTimeout);
    }

    private static Outcome request(StdioMcpTransport transport) {
        final long start = System.nanoTime();
        try {
            final JsonNode result = transport.sendRequest("tools/call", MAPPER.createObjectNode());
            return new Outcome(result, null, elapsedMillis(start));
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
