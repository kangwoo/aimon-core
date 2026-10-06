package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
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
import com.mongodb.client.model.changestream.ChangeStreamDocument;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.mongodb.internal.ResumeTokenStore;
import at.aimon.session.testkit.AbstractScheduledTaskInterruptBusContractTest;

/**
 * Runs the shared interrupt bus contract against {@link MongoScheduledTaskInterruptBus} on a real replica set, plus
 * what is specific to this backend.
 *
 * <p>
 * Each node gets its own {@link MongoClient}, so the two buses share nothing but the collection — a pair built on one
 * client would pass with a bug that only shows when the connections differ.
 */
@DisplayName("MongoScheduledTaskInterruptBus integration")
@Tag("docker")
class MongoScheduledTaskInterruptBusIntegrationTest extends AbstractScheduledTaskInterruptBusContractTest {

    private static final long WAIT_TIMEOUT_MS = 5_000L;

    /** Long enough for a delivery that was going to happen to have happened; used only to assert absence. */
    private static final long QUIET_PERIOD_MS = 2_000L;

    private final Map<String, MongoClient> clients = new HashMap<>();
    private final Map<String, MongoScheduledTaskInterruptBus> buses = new HashMap<>();

    @BeforeEach
    void resetSchema() {
        MongoTestSupport.dropAndApplyDdl();
    }

    @AfterEach
    void closeBuses() {
        buses.values().forEach(MongoScheduledTaskInterruptBus::close);
        clients.values().forEach(MongoClient::close);
    }

    @Override
    protected ScheduledTaskInterruptBus busFor(String nodeName) {
        return buses.computeIfAbsent(nodeName, name -> {
            final MongoClient client = clients.computeIfAbsent(name, n -> MongoTestSupport.newClient());
            return new MongoScheduledTaskInterruptBus(client.getDatabase(MongoTestSupport.DATABASE_NAME), name);
        });
    }

    /**
     * Waits for the cursor itself rather than for a fixed interval: {@code subscribe} returns before the change stream
     * is open, and a sleep that is too short on a slow host shows up as a lost publish, not as a clear failure.
     */
    @Override
    protected void awaitSubscriptionsLive() throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS * 2);
        while (!buses.values().stream().allMatch(MongoScheduledTaskInterruptBus::isWatching)) {
            assertThat(System.nanoTime()).as("watchers attached within the timeout").isLessThan(deadline);
            Thread.sleep(10L);
        }
    }

    @Test
    @DisplayName("the publishing node does not hear its own request, and the other node does")
    void ownRequestIsFilteredByOrigin() throws Exception {
        final ScheduledTaskInterruptBus nodeA = busFor("node-A");
        final ScheduledTaskInterruptBus nodeB = busFor("node-B");
        final BlockingQueue<ScheduledTaskId> heardOnA = new LinkedBlockingQueue<>();
        final BlockingQueue<ScheduledTaskId> heardOnB = new LinkedBlockingQueue<>();
        nodeA.subscribe((id, reason) -> heardOnA.add(id));
        nodeB.subscribe((id, reason) -> heardOnB.add(id));
        awaitSubscriptionsLive();

        final ScheduledTaskId taskId = ScheduledTaskId.generate();
        nodeA.publish(taskId, InterruptReason.TASK_CANCELLED);

        // B hearing it is what makes A's silence meaningful: the request did go out.
        assertThat(heardOnB.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo(taskId);
        assertThat(heardOnA.poll(QUIET_PERIOD_MS, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("a request carrying a reason only a newer node knows still stops the task here")
    void requestFromANewerNodeIsHonoured() throws Exception {
        final ScheduledTaskInterruptBus nodeB = busFor("node-B");
        final BlockingQueue<Map.Entry<ScheduledTaskId, InterruptReason>> heardOnB = new LinkedBlockingQueue<>();
        nodeB.subscribe((id, reason) -> heardOnB.add(Map.entry(id, reason)));
        awaitSubscriptionsLive();

        // Written the way a node on the next release would write it; no build of this codec can produce it. The keys
        // are literals for the reason the codec test gives.
        MongoTestSupport.sharedDatabase().getCollection(DocumentKeys.COLL_SCHEDULED_TASK_INTERRUPTS)
                .insertOne(new Document("taskId", "task-from-the-future").append("reason", "REASON_ADDED_NEXT_RELEASE")
                        .append("originNodeId", "node-next"));

        assertThat(heardOnB.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isEqualTo(Map.entry(ScheduledTaskId.of("task-from-the-future"), InterruptReason.TASK_CANCELLED));
    }

    @Test
    @DisplayName("an unreadable document does not stop the watcher from delivering the next request")
    void unreadableDocumentDoesNotStallTheWatcher() throws Exception {
        final ScheduledTaskInterruptBus nodeA = busFor("node-A");
        final ScheduledTaskInterruptBus nodeB = busFor("node-B");
        final BlockingQueue<ScheduledTaskId> heardOnB = new LinkedBlockingQueue<>();
        nodeB.subscribe((id, reason) -> heardOnB.add(id));
        awaitSubscriptionsLive();

        MongoTestSupport.sharedDatabase().getCollection(DocumentKeys.COLL_SCHEDULED_TASK_INTERRUPTS)
                .insertOne(new Document("reason", "TASK_CANCELLED"));
        final ScheduledTaskId taskId = ScheduledTaskId.generate();
        nodeA.publish(taskId, InterruptReason.TASK_CANCELLED);

        assertThat(heardOnB.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo(taskId);
    }

    @Test
    @DisplayName("a watcher keeps delivering after the collection is dropped and created again")
    void watcherSurvivesTheCollectionBeingRecreated() throws Exception {
        // What an operator does to turn an accidentally uncapped collection into a capped one, with nodes running.
        // The server closes the cursor on the drop and the insert-only pipeline never shows the watcher why.
        final ScheduledTaskInterruptBus nodeA = busFor("node-A");
        final ScheduledTaskInterruptBus nodeB = busFor("node-B");
        final BlockingQueue<ScheduledTaskId> heardOnB = new LinkedBlockingQueue<>();
        nodeB.subscribe((id, reason) -> heardOnB.add(id));
        awaitSubscriptionsLive();
        final ScheduledTaskId before = ScheduledTaskId.generate();
        nodeA.publish(before, InterruptReason.TASK_CANCELLED);
        assertThat(heardOnB.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo(before);

        MongoTestSupport.dropAndApplyDdl();

        assertThat(deliveredEventually(nodeA, heardOnB)).as("a publish after the recreate reaches node B").isTrue();
    }

    @Test
    @DisplayName("a watcher whose resume token the server refuses starts over instead of retrying it for ever")
    void watcherRecoversFromAResumeTokenTheServerRefuses() throws Exception {
        // The aged-out token: stop requests are rare, so the last token a node holds can be older than the oplog.
        // Stood in for by a token from another collection's stream, which this stream cannot resume from either.
        final ResumeTokenStore unusable = new ResumeTokenStore();
        unusable.update(tokenFromAnotherCollection());
        final MongoClient clientB = clients.computeIfAbsent("node-B", n -> MongoTestSupport.newClient());
        final MongoScheduledTaskInterruptBus nodeB = new MongoScheduledTaskInterruptBus(
                clientB.getDatabase(MongoTestSupport.DATABASE_NAME), DocumentKeys.COLL_SCHEDULED_TASK_INTERRUPTS,
                "node-B", unusable);
        buses.put("node-B", nodeB);
        final ScheduledTaskInterruptBus nodeA = busFor("node-A");
        final BlockingQueue<ScheduledTaskId> heardOnB = new LinkedBlockingQueue<>();
        nodeB.subscribe((id, reason) -> heardOnB.add(id));

        assertThat(deliveredEventually(nodeA, heardOnB)).as("a publish reaches node B once it has started over")
                .isTrue();
    }

    @Test
    @DisplayName("a listener that throws an Error does not end the watcher")
    void listenerThrowingAnErrorDoesNotEndTheWatcher() throws Exception {
        final ScheduledTaskInterruptBus nodeA = busFor("node-A");
        final MongoScheduledTaskInterruptBus nodeB = (MongoScheduledTaskInterruptBus) busFor("node-B");
        final BlockingQueue<ScheduledTaskId> heardOnB = new LinkedBlockingQueue<>();
        nodeB.subscribe((id, reason) -> {
            throw new AssertionError("an Error, not an Exception, from a listener");
        });
        nodeB.subscribe((id, reason) -> heardOnB.add(id));
        awaitSubscriptionsLive();

        final ScheduledTaskId first = ScheduledTaskId.generate();
        final ScheduledTaskId second = ScheduledTaskId.generate();
        nodeA.publish(first, InterruptReason.TASK_CANCELLED);
        nodeA.publish(second, InterruptReason.TASK_CANCELLED);

        assertThat(heardOnB.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo(first);
        assertThat(heardOnB.poll(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isEqualTo(second);
        assertThat(nodeB.isWatcherAlive()).isTrue();
    }

    @Test
    @DisplayName("close stops the watcher thread, and the bus refuses further use")
    void closeStopsTheWatcherAndRefusesFurtherUse() throws Exception {
        final MongoScheduledTaskInterruptBus nodeB = (MongoScheduledTaskInterruptBus) busFor("node-B");
        nodeB.subscribe((id, reason) -> {
        });
        awaitSubscriptionsLive();
        assertThat(nodeB.isWatcherAlive()).isTrue();

        nodeB.close();

        assertThat(nodeB.isWatcherAlive()).isFalse();
        assertThatThrownBy(() -> nodeB.publish(ScheduledTaskId.generate(), InterruptReason.TASK_CANCELLED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> nodeB.subscribe((id, reason) -> {
        })).isInstanceOf(IllegalStateException.class);
        // Idempotent: the engine's teardown and the application's may both reach it.
        nodeB.close();
    }

    /**
     * Publishes a fresh request every 200 ms until one is heard. A watcher that is reopening has a window in which a
     * publish is legitimately missed, so one publish and one wait would test the timing rather than the recovery.
     */
    private static boolean deliveredEventually(ScheduledTaskInterruptBus publisher,
            BlockingQueue<ScheduledTaskId> heard) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS * 2);
        while (System.nanoTime() < deadline) {
            final ScheduledTaskId probe = ScheduledTaskId.generate();
            publisher.publish(probe, InterruptReason.TASK_CANCELLED);
            final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200L);
            while (System.nanoTime() < until) {
                final ScheduledTaskId got = heard.poll(20L, TimeUnit.MILLISECONDS);
                if (probe.equals(got)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BsonDocument tokenFromAnotherCollection() {
        final MongoCollection<Document> other = MongoTestSupport.sharedDatabase()
                .getCollection("interrupt_bus_test_other_stream");
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
