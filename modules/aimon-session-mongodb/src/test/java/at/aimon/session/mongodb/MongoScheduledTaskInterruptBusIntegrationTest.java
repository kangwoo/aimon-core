package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoClient;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;
import at.aimon.session.mongodb.internal.DocumentKeys;
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

    /**
     * How long a freshly started watcher is given to attach its cursor. The same figure, for the same reason, as
     * {@code MongoSessionSignalBusIntegrationTest}: {@code subscribe} returns before the change stream is open.
     */
    private static final long INITIAL_SETTLE_MS = 500L;

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

    @Override
    protected void awaitSubscriptionsLive() throws InterruptedException {
        Thread.sleep(INITIAL_SETTLE_MS);
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
}
