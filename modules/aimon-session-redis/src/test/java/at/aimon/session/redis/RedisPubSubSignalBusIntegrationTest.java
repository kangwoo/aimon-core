package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * Integration tests for {@link RedisPubSubSignalBus} against a real Redis container.
 *
 * <p>
 * Two bus instances on different "node ids" exchange signals through Redis pub/sub; tests verify both control-channel
 * (INTERRUPT/EVICT/MESSAGE_ENQUEUED) and events-channel (EVENT) round-trips, plus subscription lifecycle.
 */
@DisplayName("RedisPubSubSignalBus integration")
@Tag("docker")
class RedisPubSubSignalBusIntegrationTest {

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
    @DisplayName("control-channel signal published from A reaches B's subscriber")
    void controlChannelRoundTrip() throws Exception {
        final SessionId id = SessionId.of("c-bus-1");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            // Lettuce SUBSCRIBE has a small server-side propagation delay; give it a moment to settle.
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
    @DisplayName("events-channel signal published from A reaches B's subscriber")
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
}
