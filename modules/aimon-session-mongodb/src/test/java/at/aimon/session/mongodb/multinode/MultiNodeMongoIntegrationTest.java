package at.aimon.session.mongodb.multinode;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.session.mongodb.MongoIdempotencyStore;
import at.aimon.session.mongodb.MongoSessionInbox;
import at.aimon.session.mongodb.MongoSessionLeaseStore;
import at.aimon.session.mongodb.MongoSessionSignalBus;
import at.aimon.session.mongodb.MongoTestSupport;
import at.aimon.session.mongodb.internal.DocumentKeys;
import at.aimon.session.testkit.AbstractMultiNodeSessionContractTest;
import at.aimon.session.testkit.SessionBackend;
import at.aimon.session.testkit.SessionBackendFactory;

/**
 * The multi-node contract over a shared MongoDB replica-set container.
 *
 * <p>
 * All three windows are widened. A change stream has to open a cursor before it delivers anything, and its delivery
 * latency is higher than pub/sub throughout — including across the several sweep ticks the holder-loss case waits on,
 * which is why that bound moves as well.
 */
@DisplayName("Multi-node SessionRouter over MongoDB")
@Tag("docker")
class MultiNodeMongoIntegrationTest extends AbstractMultiNodeSessionContractTest {

    private final SessionBackendFactory backend = new MongoBackend();

    @Override
    protected SessionBackendFactory backend() {
        return backend;
    }

    @Override
    protected Duration settle() {
        return Duration.ofMillis(500);
    }

    @Override
    protected Duration propagationTimeout() {
        return Duration.ofSeconds(5);
    }

    @Override
    protected Duration holderLossTimeout() {
        return Duration.ofSeconds(7);
    }

    /** Each node gets a client of its own, so its change-stream watcher is a genuinely separate reader. */
    private static final class MongoBackend implements SessionBackendFactory {

        @Override
        public void reset() {
            MongoTestSupport.dropAndApplyDdl();
        }

        @Override
        public SessionBackend createNode(String nodeId, Consumer<AutoCloseable> ownedResources) {
            final MongoDatabase db = database(ownedResources);

            final MongoSessionSignalBus bus = new MongoSessionSignalBus(db, DocumentKeys.COLL_SIGNALS, nodeId);
            ownedResources.accept(bus);

            return SessionBackend.of(new MongoSessionLeaseStore(db, DocumentKeys.COLL_LOCKS, Clock.systemUTC()), bus,
                    new MongoSessionInbox(db, DocumentKeys.COLL_INBOX), new MongoIdempotencyStore(db,
                            DocumentKeys.COLL_IDEMPOTENCY, Duration.ofHours(24), Clock.systemUTC()));
        }

        @Override
        public SessionLeaseStore createLeaseStore(Consumer<AutoCloseable> ownedResources) {
            return new MongoSessionLeaseStore(database(ownedResources), DocumentKeys.COLL_LOCKS, Clock.systemUTC());
        }

        @Override
        public SessionInbox createInbox(Consumer<AutoCloseable> ownedResources) {
            return new MongoSessionInbox(database(ownedResources), DocumentKeys.COLL_INBOX);
        }

        @Override
        public IdempotencyStore createIdempotencyStore(Consumer<AutoCloseable> ownedResources) {
            return new MongoIdempotencyStore(database(ownedResources), DocumentKeys.COLL_IDEMPOTENCY,
                    Duration.ofHours(24), Clock.systemUTC());
        }

        private static MongoDatabase database(Consumer<AutoCloseable> ownedResources) {
            final MongoClient client = MongoTestSupport.newClient();
            ownedResources.accept(client);
            return client.getDatabase(MongoTestSupport.DATABASE_NAME);
        }
    }
}
