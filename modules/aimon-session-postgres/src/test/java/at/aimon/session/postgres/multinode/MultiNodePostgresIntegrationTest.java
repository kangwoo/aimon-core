package at.aimon.session.postgres.multinode;

import java.time.Duration;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import com.zaxxer.hikari.HikariDataSource;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.session.postgres.PostgresIdempotencyStore;
import at.aimon.session.postgres.PostgresSessionInbox;
import at.aimon.session.postgres.PostgresSessionLeaseStore;
import at.aimon.session.postgres.PostgresSessionSignalBus;
import at.aimon.session.postgres.PostgresTestSupport;
import at.aimon.session.testkit.AbstractMultiNodeSessionContractTest;
import at.aimon.session.testkit.SessionBackend;
import at.aimon.session.testkit.SessionBackendFactory;

/**
 * The multi-node contract over a shared Postgres container.
 *
 * <p>
 * The two overridden windows are both about {@code LISTEN}/{@code NOTIFY}: a subscription is not live until the
 * dispatcher's poll loop has picked it up, which is a cycle later than a Redis SUBSCRIBE.
 */
@DisplayName("Multi-node SessionRouter over Postgres")
@Tag("docker")
class MultiNodePostgresIntegrationTest extends AbstractMultiNodeSessionContractTest {

    private final SessionBackendFactory backend = new PostgresBackend();

    @Override
    protected SessionBackendFactory backend() {
        return backend;
    }

    @Override
    protected Duration settle() {
        return Duration.ofMillis(300);
    }

    @Override
    protected Duration propagationTimeout() {
        return Duration.ofSeconds(5);
    }

    /** Each node gets two pools of its own: one for SQL, one held open by the LISTEN connection. */
    private static final class PostgresBackend implements SessionBackendFactory {

        @Override
        public void reset() {
            PostgresTestSupport.truncateAll();
        }

        @Override
        public SessionBackend createNode(String nodeId, Consumer<AutoCloseable> ownedResources) {
            final HikariDataSource mainPool = PostgresTestSupport.isolatedDataSource(8);
            final HikariDataSource signalPool = PostgresTestSupport.isolatedDataSource(2);
            ownedResources.accept(mainPool);
            ownedResources.accept(signalPool);

            final PostgresSessionSignalBus bus = new PostgresSessionSignalBus(mainPool, signalPool,
                    PostgresTestSupport.jdbcUrl(), PostgresTestSupport.listenConnectionProps(), nodeId);
            ownedResources.accept(bus);

            return SessionBackend.of(new PostgresSessionLeaseStore(mainPool), bus, new PostgresSessionInbox(mainPool),
                    new PostgresIdempotencyStore(mainPool));
        }

        @Override
        public SessionLeaseStore createLeaseStore(Consumer<AutoCloseable> ownedResources) {
            return new PostgresSessionLeaseStore(pool(ownedResources));
        }

        @Override
        public SessionInbox createInbox(Consumer<AutoCloseable> ownedResources) {
            return new PostgresSessionInbox(pool(ownedResources));
        }

        @Override
        public IdempotencyStore createIdempotencyStore(Consumer<AutoCloseable> ownedResources) {
            return new PostgresIdempotencyStore(pool(ownedResources));
        }

        private static HikariDataSource pool(Consumer<AutoCloseable> ownedResources) {
            final HikariDataSource ds = PostgresTestSupport.isolatedDataSource(2);
            ownedResources.accept(ds);
            return ds;
        }
    }
}
