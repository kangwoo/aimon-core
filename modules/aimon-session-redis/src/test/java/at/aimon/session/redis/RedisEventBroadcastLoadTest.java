package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Load measurement for cross-node EVENT broadcast over the Redis pub/sub signal bus — produces decision-support
 * data for design §13 #8 (lock affinity / cluster-wide broadcast cost).
 *
 * <p>
 * Two {@link RedisPubSubSignalBus} instances are wired through one Testcontainers Redis. One bus publishes synthetic
 * EVENT signals carrying an embedded {@code System.nanoTime()} stamp; the other bus subscribes and records the
 * receive-time delta. We compute p50 / p95 / p99 / max latency and effective throughput, then write a markdown
 * summary under the module's build reports directory for inclusion in §13 #8.
 *
 * <p>
 * The tests are sized to complete in a few seconds; they do not stress Redis to saturation. They are deterministic
 * enough to assert sane upper bounds (so a regression in the broadcast path fails the build) but the primary signal
 * is the captured numbers, not the assertions.
 */
@DisplayName("Redis EVENT broadcast load measurement")
@Tag("docker")
class RedisEventBroadcastLoadTest {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBroadcastLoadTest.class);

    private static final Path REPORT_DIR = Path.of("build", "reports", "loadtest");
    private static final Path REPORT_FILE = REPORT_DIR.resolve("event-broadcast.md");

    private StatefulRedisConnection<String, String> publishA;
    private StatefulRedisPubSubConnection<String, String> subscribeA;
    private StatefulRedisConnection<String, String> publishB;
    private StatefulRedisPubSubConnection<String, String> subscribeB;

    private RedisPubSubSignalBus busA;
    private RedisPubSubSignalBus busB;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        publishA = RedisTestSupport.connect();
        subscribeA = RedisTestSupport.connectPubSub();
        publishB = RedisTestSupport.connect();
        subscribeB = RedisTestSupport.connectPubSub();
        busA = new RedisPubSubSignalBus(publishA, subscribeA);
        busB = new RedisPubSubSignalBus(publishB, subscribeB);
    }

    @AfterEach
    void tearDown() {
        if (busA != null) {
            busA.close();
        }
        if (busB != null) {
            busB.close();
        }
        if (publishA != null) {
            publishA.close();
        }
        if (subscribeA != null) {
            subscribeA.close();
        }
        if (publishB != null) {
            publishB.close();
        }
        if (subscribeB != null) {
            subscribeB.close();
        }
    }

    @Test
    @DisplayName("paced 5 ms inter-arrival: cross-node latency p50/p95/p99 stays well below 100 ms over 1000 events")
    void pacedLatency() throws Exception {
        final SessionId id = SessionId.of("c-load-paced");
        final int eventCount = 1000;
        final long pacingNanos = TimeUnit.MILLISECONDS.toNanos(5);

        final long[] latencies = runMeasurement(id, eventCount, pacingNanos, "paced-5ms");

        assertThat(latencies).hasSize(eventCount);
        final Stats s = stats(latencies);
        // Sanity guard: cross-node EVENT delivery on a single-host Testcontainers Redis should be far under 100 ms p99.
        assertThat(s.p99Ms).as("p99 latency must stay under 100 ms").isLessThan(100.0);
    }

    @Test
    @DisplayName("burst (no pacing): sustained throughput and tail latency over 5000 events")
    void burstThroughput() throws Exception {
        final SessionId id = SessionId.of("c-load-burst");
        final int eventCount = 5000;

        final long[] latencies = runMeasurement(id, eventCount, 0L, "burst");

        // We don't assert latency here — bursts are about throughput. We do require zero loss.
        assertThat(latencies).hasSize(eventCount);
    }

    private long[] runMeasurement(SessionId id, int eventCount, long pacingNanos, String label) throws Exception {
        // Latencies are recorded inside the subscriber callback so that receive-time = handler-entry time, not
        // queue-drain time. Otherwise we'd be measuring how fast the test thread can drain a backlog instead of
        // the bus's actual end-to-end delivery latency.
        final long[] latencies = new long[eventCount];
        final AtomicInteger nextSlot = new AtomicInteger();
        final CountDownLatch allReceived = new CountDownLatch(eventCount);

        try (SessionSignalBus.Subscription sub = busB.subscribe(id, signal -> {
            final long recvNanos = System.nanoTime();
            final String sendStr = signal.getPayload().get("sendNanos").toString();
            final long sendNanos = Long.parseLong(sendStr);
            final int slot = nextSlot.getAndIncrement();
            if (slot < latencies.length) {
                latencies[slot] = recvNanos - sendNanos;
            }
            allReceived.countDown();
        })) {
            // Lettuce SUBSCRIBE has a small server-side propagation delay; let it settle.
            Thread.sleep(150);

            final long publishStart = System.nanoTime();
            for (int i = 0; i < eventCount; i++) {
                final long sendNanos = System.nanoTime();
                busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                        .payload(Map.of("seq", String.valueOf(i), "sendNanos", String.valueOf(sendNanos))).build());
                if (pacingNanos > 0L) {
                    parkUntil(sendNanos + pacingNanos);
                }
            }
            final long publishEnd = System.nanoTime();

            assertThat(allReceived.await(10, TimeUnit.SECONDS)).as("zero loss is required for a meaningful measurement")
                    .isTrue();

            final long publishWallNanos = publishEnd - publishStart;
            final Stats s = stats(latencies);
            final double throughput = eventCount / (publishWallNanos / 1_000_000_000.0);

            log.info(
                    "EVENT broadcast load [{}] count={} publishWall={}ms throughput={} ev/s "
                            + "p50={}ms p95={}ms p99={}ms max={}ms",
                    label, eventCount, publishWallNanos / 1_000_000L, String.format("%.0f", throughput),
                    String.format("%.2f", s.p50Ms), String.format("%.2f", s.p95Ms), String.format("%.2f", s.p99Ms),
                    String.format("%.2f", s.maxMs));
            appendReport(label, eventCount, publishWallNanos, throughput, s);
            return latencies;
        }
    }

    private static void parkUntil(long deadlineNanos) {
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0L) {
            java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }
    }

    private static Stats stats(long[] latencies) {
        final long[] sorted = Arrays.copyOf(latencies, latencies.length);
        Arrays.sort(sorted);
        final Stats s = new Stats();
        s.p50Ms = sorted[(int) (sorted.length * 0.50)] / 1_000_000.0;
        s.p95Ms = sorted[(int) (sorted.length * 0.95)] / 1_000_000.0;
        s.p99Ms = sorted[(int) (sorted.length * 0.99)] / 1_000_000.0;
        s.maxMs = sorted[sorted.length - 1] / 1_000_000.0;
        return s;
    }

    private static void appendReport(String label, int count, long publishWallNanos, double throughput, Stats s) {
        try {
            Files.createDirectories(REPORT_DIR);
            final String header = Files.exists(REPORT_FILE)
                    ? ""
                    : "# EVENT broadcast load measurements\n\n"
                            + "Cross-node EVENT signal delivery over RedisPubSubSignalBus, single-host Testcontainers.\n\n"
                            + "| label | count | publish wall (ms) | throughput (ev/s) | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |\n"
                            + "|---|---:|---:|---:|---:|---:|---:|---:|\n";
            final String row = String.format("| %s | %d | %d | %.0f | %.2f | %.2f | %.2f | %.2f |%n", label, count,
                    publishWallNanos / 1_000_000L, throughput, s.p50Ms, s.p95Ms, s.p99Ms, s.maxMs);
            Files.writeString(REPORT_FILE, header + row, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ioe) {
            log.warn("Failed to append load report: {}", ioe.toString());
        }
    }

    private static final class Stats {
        double p50Ms;
        double p95Ms;
        double p99Ms;
        double maxMs;
    }
}
