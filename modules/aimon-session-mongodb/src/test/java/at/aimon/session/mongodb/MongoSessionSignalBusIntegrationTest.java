package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.changestream.ChangeStreamDocument;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.mongodb.internal.ResumeTokenStore;

/**
 * Integration tests for {@link MongoSessionSignalBus} against a real MongoDB replica-set container.
 *
 * <p>
 * Two bus instances on different "node ids", each with its own {@link MongoClient}, exchange signals through the capped
 * {@code conversation_signals} collection via Change Streams. Tests verify both control-channel
 * (INTERRUPT/EVICT/MESSAGE_ENQUEUED) and EVENT round-trips, plus subscription lifecycle and self-broadcast dedup.
 *
 * <p>
 * Bounded waits are 5 seconds — Change Streams have higher propagation latency than Redis pub/sub due to oplog polling
 * (driver default ~1s) plus client-side {@code tryNext} cadence (50ms here). 5s gives generous headroom on a loaded CI.
 */
@DisplayName("MongoSessionSignalBus integration")
@Tag("docker")
class MongoSessionSignalBusIntegrationTest {

    private static final long WAIT_TIMEOUT_MS = 5_000L;
    private static final long INITIAL_SETTLE_MS = 500L;

    private MongoClient clientA;
    private MongoClient clientB;
    private MongoDatabase dbA;
    private MongoDatabase dbB;
    private MongoSessionSignalBus busA;
    private MongoSessionSignalBus busB;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        clientA = MongoTestSupport.newClient();
        clientB = MongoTestSupport.newClient();
        dbA = clientA.getDatabase(MongoTestSupport.DATABASE_NAME);
        dbB = clientB.getDatabase(MongoTestSupport.DATABASE_NAME);
        busA = new MongoSessionSignalBus(dbA, DocumentKeys.COLL_SIGNALS, "node-A");
        busB = new MongoSessionSignalBus(dbB, DocumentKeys.COLL_SIGNALS, "node-B");
    }

    @AfterEach
    void tearDown() {
        if (busA != null) {
            busA.close();
        }
        if (busB != null) {
            busB.close();
        }
        if (clientA != null) {
            clientA.close();
        }
        if (clientB != null) {
            clientB.close();
        }
    }

    @Test
    @DisplayName("control-channel signal published from A reaches B's subscriber")
    void controlChannelRoundTrip() throws Exception {
        final SessionId id = SessionId.of("c-bus-1");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            // Allow the watcher thread to start its cursor and the change-stream pipeline to settle.
            Thread.sleep(INITIAL_SETTLE_MS);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.INTERRUPT).originNodeId("node-A")
                    .payload(Map.of("reason", "USER_REQUEST")).build());

            final SessionSignal got = received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertThat(got).as("subscriber should receive published signal").isNotNull();
            assertThat(got.getKind()).isEqualTo(SignalKind.INTERRUPT);
            assertThat(got.getOriginNodeId()).isEqualTo("node-A");
            assertThat(got.getPayload()).containsEntry("reason", "USER_REQUEST");
        }
    }

    @Test
    @DisplayName("EVENT signal published from A reaches B's subscriber")
    void eventChannelRoundTrip() throws Exception {
        final SessionId id = SessionId.of("c-bus-2");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(INITIAL_SETTLE_MS);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                    .payload(Map.of("type", "AssistantTextDelta", "delta", "hello", "chunkIndex", 0)).build());

            final SessionSignal got = received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
        Thread.sleep(INITIAL_SETTLE_MS);
        busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-A").build());
        assertThat(received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

        sub.close();
        // Give the watcher a moment to register the unsubscribe before publishing again.
        Thread.sleep(100);
        busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-A").build());
        assertThat(received.poll(1_000L, TimeUnit.MILLISECONDS)).as("must not deliver after unsubscribe").isNull();
    }

    @Test
    @DisplayName("two handlers on the same conversation both receive the signal")
    void multipleHandlersOnSameConversation() throws Exception {
        final SessionId id = SessionId.of("c-bus-4");
        final LinkedBlockingQueue<SessionSignal> a = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<SessionSignal> b = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sa = busB.subscribe(id, a::offer);
                SessionSignalBus.Subscription sb = busB.subscribe(id, b::offer)) {
            Thread.sleep(INITIAL_SETTLE_MS);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.MESSAGE_ENQUEUED).originNodeId("node-A")
                    .build());
            assertThat(a.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();
            assertThat(b.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();
        }
    }

    @Test
    @DisplayName("self-broadcast dedup: A's own publish is filtered before reaching A's handler")
    void selfBroadcastIsFiltered() throws Exception {
        final SessionId id = SessionId.of("c-bus-5");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busA.subscribe(id, received::offer)) {
            Thread.sleep(INITIAL_SETTLE_MS);
            // A publishes; A also subscribes — without dedup A would observe its own publish.
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-A").build());
            // Cross-check: B publishing to the same id with a different originNodeId DOES get delivered to A.
            busB.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-B").build());

            // First (and only) delivered envelope must be from B; A's self-publish is filtered.
            final SessionSignal got = received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertThat(got).as("A should receive B's broadcast").isNotNull();
            assertThat(got.getOriginNodeId()).isEqualTo("node-B");
            assertThat(received.poll(500L, TimeUnit.MILLISECONDS)).as("must not deliver A's own publish").isNull();
        }
    }

    @Test
    @DisplayName("a watcher keeps delivering after the signal collection is dropped and created again")
    void watcherSurvivesTheCollectionBeingRecreated() throws Exception {
        // The server closes the cursor on the drop, the insert-only pipeline never shows the watcher the invalidate,
        // and tryNext() on the dead cursor returns null for ever. Every subscriber on the node goes deaf — interrupts,
        // evictions and the event relay all ride this channel — with nothing in the log.
        final SessionId id = SessionId.of("c-bus-recreate");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(INITIAL_SETTLE_MS);
            busA.publish(interrupt(id, "before"));
            assertThat(received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

            MongoTestSupport.dropAndApplyDdl();

            assertThat(deliveredEventually(id, received)).as("a signal after the recreate reaches node B").isTrue();
        }
    }

    @Test
    @DisplayName("a watcher whose resume token the server refuses starts over instead of retrying it for ever")
    void watcherRecoversFromAResumeTokenTheServerRefuses() throws Exception {
        // The aged-out token, stood in for by a token from another collection's stream — the server refuses both the
        // same way, with a MongoCommandException rather than the MongoChangeStreamException the watcher looked for.
        final ResumeTokenStore unusable = new ResumeTokenStore();
        unusable.update(tokenFromAnotherCollection());
        final MongoSessionSignalBus stale = new MongoSessionSignalBus(dbB, DocumentKeys.COLL_SIGNALS, "node-C",
                unusable);
        final SessionId id = SessionId.of("c-bus-stale-token");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = stale.subscribe(id, received::offer)) {
            assertThat(deliveredEventually(id, received)).as("a signal reaches the node once it has started over")
                    .isTrue();
        } finally {
            stale.close();
        }
    }

    @Test
    @DisplayName("a handler that throws an Error does not end the watcher")
    void handlerThrowingAnErrorDoesNotEndTheWatcher() throws Exception {
        final SessionId id = SessionId.of("c-bus-error");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription failing = busB.subscribe(id, signal -> {
            throw new AssertionError("an Error, not an Exception, from a handler");
        }); SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(INITIAL_SETTLE_MS);
            busA.publish(interrupt(id, "first"));
            busA.publish(interrupt(id, "second"));

            assertThat(received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();
            assertThat(received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();
            assertThat(busB.isWatcherAlive()).isTrue();
        }
    }

    private static SessionSignal interrupt(SessionId id, String marker) {
        return SessionSignal.builder().sessionId(id).kind(SignalKind.INTERRUPT).originNodeId("node-A")
                .payload(Map.of("reason", "USER_REQUEST", "marker", marker)).build();
    }

    /**
     * Publishes a fresh signal every 200 ms until one is heard. A watcher that is reopening has a window in which a
     * publish is legitimately missed, so one publish and one wait would test the timing rather than the recovery.
     */
    private boolean deliveredEventually(SessionId id, LinkedBlockingQueue<SessionSignal> received)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS * 2);
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            final String marker = "probe-" + attempt++;
            busA.publish(interrupt(id, marker));
            final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200L);
            while (System.nanoTime() < until) {
                final SessionSignal got = received.poll(20L, TimeUnit.MILLISECONDS);
                if (got != null && marker.equals(got.getPayload().get("marker"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BsonDocument tokenFromAnotherCollection() {
        final MongoCollection<Document> other = MongoTestSupport.sharedDatabase()
                .getCollection("signal_bus_test_other_stream");
        other.insertOne(new Document("seed", true));
        try (MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor = other.watch().cursor()) {
            other.insertOne(new Document("event", true));
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS);
            while (System.nanoTime() < deadline) {
                final ChangeStreamDocument<Document> change = cursor.tryNext();
                if (change != null) {
                    return change.getResumeToken();
                }
            }
            throw new AssertionError("no change event from the other collection");
        } finally {
            other.drop();
        }
    }
}
