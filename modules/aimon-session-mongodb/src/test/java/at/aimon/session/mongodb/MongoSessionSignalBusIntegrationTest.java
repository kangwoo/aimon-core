package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
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
import at.aimon.core.agent.session.exception.SessionSignalBusException;
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
    @DisplayName("publishAll delivers a batch to another node's subscriber in list order")
    void publishAllKeepsListOrder() throws Exception {
        final SessionId id = SessionId.of("c-bus-batch");
        final int count = 500;
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(INITIAL_SETTLE_MS);
            final List<SessionSignal> batch = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                batch.add(SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                        .payload(Map.of("type", "AssistantTextDelta", "delta", "d" + i, "chunkIndex", i)).build());
            }
            busA.publishAll(batch);
            busA.publishAll(List.of());

            final List<Object> chunks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final SessionSignal got = received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                assertThat(got).as("signal %d of the batch", i).isNotNull();
                chunks.add(got.getPayload().get("chunkIndex"));
            }
            final List<Object> expected = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                expected.add(i);
            }
            assertThat(chunks).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("publishAll resumes after a document the server refuses mid-batch")
    void publishAllResumesAfterAServerWriteError() throws Exception {
        final SessionId id = SessionId.of("c-bus-batch-refused");
        // A validator is how to get a write error out of the server for one chosen document of an ordered insert.
        dbA.runCommand(new Document("collMod", DocumentKeys.COLL_SIGNALS).append("validator",
                new Document(DocumentKeys.F_PAYLOAD + ".marker", new Document("$ne", "refused"))));
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(INITIAL_SETTLE_MS);
            final List<SessionSignal> batch = List.of(event(id, "a", "ok"), event(id, "refused", "ok"),
                    event(id, "b", "ok"), event(id, "refused", "ok"), event(id, "c", "ok"));

            assertThatThrownBy(() -> busA.publishAll(batch)).isInstanceOf(SessionSignalBusException.class);

            final List<Object> markers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final SessionSignal got = received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                assertThat(got).as("signal %d that the server accepted", i).isNotNull();
                markers.add(got.getPayload().get("marker"));
            }
            assertThat(markers).containsExactly("a", "b", "c");
            assertThat(received.poll(300L, TimeUnit.MILLISECONDS)).as("nothing is published twice").isNull();
        }
    }

    @Test
    @DisplayName("publishAll skips a document the driver refuses and still publishes the ones around it")
    void publishAllSkipsARefusedDocument() throws Exception {
        final SessionId id = SessionId.of("c-bus-batch-poison");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(INITIAL_SETTLE_MS);
            // Past the 16 MB a single document may be. The driver turns it away before anything is sent, and fails
            // the whole call without saying which document it was.
            final String oversized = "x".repeat(17 * 1024 * 1024);
            final List<SessionSignal> batch = List.of(event(id, "first", "ok"), event(id, "poison", oversized),
                    event(id, "last", "ok"));

            assertThatThrownBy(() -> busA.publishAll(batch)).isInstanceOf(SessionSignalBusException.class);

            final SessionSignal first = received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            final SessionSignal last = received.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertThat(first).isNotNull();
            assertThat(last).as("the signal after the refused one").isNotNull();
            assertThat(first.getPayload()).containsEntry("marker", "first");
            assertThat(last.getPayload()).containsEntry("marker", "last");
        }
    }

    private static SessionSignal event(SessionId id, String marker, String body) {
        return SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                .payload(Map.of("type", "ToolCallCompleted", "marker", marker, "body", body)).build();
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
        // The aged-out token, stood in for by a token from another collection's stream. The two are not refused
        // identically — a real one is ChangeStreamHistoryLost (286) from the opening aggregate, this is
        // ChangeStreamFatalError (280) from a later getMore — but both are a MongoCommandException labelled
        // NonResumableChangeStreamError, which is what the watcher decides on.
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

    @Test
    @DisplayName("the resume token advances while the channel is quiet")
    void resumeTokenAdvancesWhileIdle() throws Exception {
        // A token that only moved on delivery would be as old as the last signal, and on a quiet channel that is old
        // enough to have left the oplog by the time a reconnect needs it.
        final ResumeTokenStore tokens = new ResumeTokenStore();
        final MongoSessionSignalBus quiet = new MongoSessionSignalBus(dbB, DocumentKeys.COLL_SIGNALS, "node-quiet",
                tokens);
        try (SessionSignalBus.Subscription sub = quiet.subscribe(SessionId.of("c-bus-quiet"), signal -> {
        })) {
            final BsonDocument first = awaitToken(tokens, null);
            // Something has to move the oplog for the post-batch token to move; it need not be this collection.
            MongoTestSupport.sharedDatabase().getCollection("signal_bus_test_oplog_filler")
                    .insertOne(new Document("filler", true));

            assertThat(awaitToken(tokens, first)).as("a later token, with nothing delivered").isNotEqualTo(first);
        } finally {
            quiet.close();
            MongoTestSupport.sharedDatabase().getCollection("signal_bus_test_oplog_filler").drop();
        }
    }

    /** Waits for the store to hold a token other than {@code previous}. */
    private static BsonDocument awaitToken(ResumeTokenStore tokens, BsonDocument previous) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS * 2);
        while (System.nanoTime() < deadline) {
            final BsonDocument current = tokens.last().orElse(null);
            if (current != null && !current.equals(previous)) {
                return current;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("no new resume token within the timeout; last was " + previous);
    }

    @Test
    @DisplayName("a server error that has nothing to do with the resume token does not cost any signal")
    void anUnrelatedServerErrorLosesNoSignal() throws Exception {
        // The watcher's getMore is killed on the server while signals keep arriving. That is a command error like a
        // refused token is, but the token is fine: the watcher has to keep it, resume, and replay what it missed.
        // Dropping the token here — starting over from "now" — loses everything published during the backoff.
        final SessionId id = SessionId.of("c-bus-killop");
        final int total = 80;
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(INITIAL_SETTLE_MS);
            final Thread publisher = new Thread(() -> {
                for (int i = 0; i < total; i++) {
                    busA.publish(interrupt(id, "seq-" + i));
                    try {
                        Thread.sleep(20L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "signal-publisher");
            publisher.start();
            Thread.sleep(300L);

            assertThat(killChangeStreamGetMore()).as("a getMore of the watcher was found and killed").isTrue();
            publisher.join(TimeUnit.SECONDS.toMillis(30));

            final java.util.Set<String> markers = new java.util.TreeSet<>();
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS * 2);
            while (markers.size() < total && System.nanoTime() < deadline) {
                final SessionSignal got = received.poll(100L, TimeUnit.MILLISECONDS);
                if (got != null) {
                    markers.add(String.valueOf(got.getPayload().get("marker")));
                }
            }
            assertThat(markers).as("every published signal reached node B").hasSize(total);
        }
    }

    /** Kills the in-flight getMore of every change stream on the signal collection; returns whether it found one. */
    private boolean killChangeStreamGetMore() throws InterruptedException {
        final MongoDatabase admin = clientA.getDatabase("admin");
        final String namespace = MongoTestSupport.DATABASE_NAME + "." + DocumentKeys.COLL_SIGNALS;
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            final Document current = admin
                    .runCommand(new Document("currentOp", 1).append("op", "getmore").append("ns", namespace));
            boolean killed = false;
            for (Document op : current.getList("inprog", Document.class, java.util.List.of())) {
                admin.runCommand(new Document("killOp", 1).append("op", op.get("opid")));
                killed = true;
            }
            if (killed) {
                return true;
            }
            Thread.sleep(20L);
        }
        return false;
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
            busA.publish(interrupt(id, "probe-" + attempt++));
            final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200L);
            while (System.nanoTime() < until) {
                final SessionSignal got = received.poll(20L, TimeUnit.MILLISECONDS);
                // Any probe, not only this window's: one that arrives late is still a delivery after the recovery.
                if (got != null && String.valueOf(got.getPayload().get("marker")).startsWith("probe-")) {
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
