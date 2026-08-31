package at.aimon.session.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;
import at.aimon.core.agent.session.signal.SessionSignalBus;

/**
 * Integration tests for {@link PostgresSessionSignalBus} against a real Postgres container.
 *
 * <p>
 * Two bus instances on different node ids exchange signals through the LISTEN/NOTIFY doorbell pattern; tests verify
 * cross-node delivery for each {@link SignalKind}, multi-handler fan-out, unsubscribe lifecycle, and large-payload
 * (> 8 KB) round-trips that exercise the row-table fetch path rather than the (size-limited) NOTIFY payload.
 */
@DisplayName("PostgresSessionSignalBus integration")
@Tag("docker")
class PostgresSessionSignalBusIntegrationTest {

    private HikariDataSource publishPoolA;
    private HikariDataSource fetchPoolA;
    private HikariDataSource publishPoolB;
    private HikariDataSource fetchPoolB;

    private PostgresSessionSignalBus busA;
    private PostgresSessionSignalBus busB;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        publishPoolA = PostgresTestSupport.isolatedDataSource(4);
        fetchPoolA = PostgresTestSupport.isolatedDataSource(2);
        publishPoolB = PostgresTestSupport.isolatedDataSource(4);
        fetchPoolB = PostgresTestSupport.isolatedDataSource(2);
        busA = new PostgresSessionSignalBus(publishPoolA, fetchPoolA, PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.listenConnectionProps(), "node-A");
        busB = new PostgresSessionSignalBus(publishPoolB, fetchPoolB, PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.listenConnectionProps(), "node-B");
    }

    @AfterEach
    void tearDown() {
        if (busA != null) {
            busA.close();
        }
        if (busB != null) {
            busB.close();
        }
        if (fetchPoolA != null) {
            fetchPoolA.close();
        }
        if (publishPoolA != null) {
            publishPoolA.close();
        }
        if (fetchPoolB != null) {
            fetchPoolB.close();
        }
        if (publishPoolB != null) {
            publishPoolB.close();
        }
    }

    @Test
    @DisplayName("INTERRUPT signal published from A reaches B's subscriber")
    void controlChannelRoundTrip() throws Exception {
        final SessionId id = SessionId.of("c-bus-1");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            // LISTEN registration completes synchronously inside subscribe(), but give the dispatcher's poll loop a
            // tiny window to enter getNotifications() before we publish — keeps timings stable on slow CI.
            Thread.sleep(100);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.INTERRUPT).originNodeId("node-A")
                    .payload(Map.of("reason", "USER_REQUEST")).build());

            final SessionSignal got = received.poll(3, TimeUnit.SECONDS);
            assertThat(got).as("subscriber should receive published signal").isNotNull();
            assertThat(got.getKind()).isEqualTo(SignalKind.INTERRUPT);
            assertThat(got.getOriginNodeId()).isEqualTo("node-A");
            assertThat(got.getPayload()).containsEntry("reason", "USER_REQUEST");
        }
    }

    @Test
    @DisplayName("EVENT signal published from A reaches B's subscriber via row-table fetch")
    void eventsChannelRoundTrip() throws Exception {
        final SessionId id = SessionId.of("c-bus-2");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(100);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                    .payload(Map.of("type", "AssistantTextDelta", "delta", "hello", "chunkIndex", 0)).build());

            final SessionSignal got = received.poll(3, TimeUnit.SECONDS);
            assertThat(got).as("subscriber should receive EVENT").isNotNull();
            assertThat(got.getKind()).isEqualTo(SignalKind.EVENT);
            assertThat(got.getPayload()).containsEntry("type", "AssistantTextDelta").containsEntry("delta", "hello");
        }
    }

    @Test
    @DisplayName("self-broadcast (origin == nodeId) is dropped by default")
    void selfBroadcastDropped() throws Exception {
        final SessionId id = SessionId.of("c-bus-self");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busA.subscribe(id, received::offer)) {
            Thread.sleep(100);
            busA.publish(
                    SessionSignal.builder().sessionId(id).kind(SignalKind.INTERRUPT).originNodeId("node-A").build());
            assertThat(received.poll(500, TimeUnit.MILLISECONDS))
                    .as("self-broadcast must not be delivered to A's own subscriber").isNull();
        }
    }

    @Test
    @DisplayName("after subscription close, no further messages reach the handler")
    void unsubscribeStopsDelivery() throws Exception {
        final SessionId id = SessionId.of("c-bus-3");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        final SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer);
        Thread.sleep(100);
        busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-A").build());
        assertThat(received.poll(3, TimeUnit.SECONDS)).isNotNull();

        sub.close();
        Thread.sleep(100);
        busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-A").build());
        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).as("must not deliver after unsubscribe").isNull();
    }

    @Test
    @DisplayName("two handlers on the same conversation both receive the signal")
    void multipleHandlersOnSameConversation() throws Exception {
        final SessionId id = SessionId.of("c-bus-4");
        final LinkedBlockingQueue<SessionSignal> a = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<SessionSignal> b = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sa = busB.subscribe(id, a::offer);
                SessionSignalBus.Subscription sb = busB.subscribe(id, b::offer)) {
            Thread.sleep(100);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.MESSAGE_ENQUEUED).originNodeId("node-A")
                    .build());
            assertThat(a.poll(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(b.poll(3, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @Test
    @DisplayName("payloads larger than 8 KB transit safely (NOTIFY carries only the row id)")
    void largePayloadFlowsThroughRowTable() throws Exception {
        final SessionId id = SessionId.of("c-bus-large");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        // Build a ~12 KB payload — well above the Postgres 8 KB NOTIFY limit, so this only works because
        // we ferry the actual payload through the conversation_signal table and use NOTIFY as a doorbell.
        final char[] big = new char[12 * 1024];
        java.util.Arrays.fill(big, 'x');
        final Map<String, Object> payload = new HashMap<>();
        payload.put("blob", new String(big));
        payload.put("type", "AssistantTextDelta");

        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(100);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                    .payload(payload).build());

            final SessionSignal got = received.poll(5, TimeUnit.SECONDS);
            assertThat(got).as("large-payload signal should be delivered").isNotNull();
            assertThat((String) got.getPayload().get("blob")).hasSize(12 * 1024);
            assertThat(got.getPayload()).containsEntry("type", "AssistantTextDelta");
        }
    }

    @Test
    @DisplayName("sweepOlderThan reaps rows whose created_at is past the cutoff")
    void sweepOlderThanReapsOldRows() {
        final SessionId id = SessionId.of("c-bus-sweep");
        busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-A").build());
        // Cutoff far in the future — everything qualifies.
        final int deleted = busA.sweepOlderThan(java.time.Instant.now().plusSeconds(60));
        assertThat(deleted).isGreaterThanOrEqualTo(1);
    }
}
