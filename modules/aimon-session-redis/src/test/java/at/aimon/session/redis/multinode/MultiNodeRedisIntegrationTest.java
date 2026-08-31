package at.aimon.session.redis.multinode;

import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.session.redis.RedisIdempotencyStore;
import at.aimon.session.redis.RedisPubSubSignalBus;
import at.aimon.session.redis.RedisSessionInbox;
import at.aimon.session.redis.RedisSessionLeaseStore;
import at.aimon.session.redis.RedisTestSupport;
import at.aimon.session.testkit.AbstractMultiNodeSessionContractTest;
import at.aimon.session.testkit.SessionBackend;
import at.aimon.session.testkit.SessionBackendFactory;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * The multi-node contract over a shared Redis container.
 *
 * <p>
 * Redis sets the testkit's default wait windows, so this subclass overrides none of them: pub/sub delivers as soon as
 * the SUBSCRIBE lands, with no dispatcher poll or cursor in between.
 */
@DisplayName("Multi-node SessionRouter over Redis")
@Tag("docker")
class MultiNodeRedisIntegrationTest extends AbstractMultiNodeSessionContractTest {

    private final SessionBackendFactory backend = new RedisBackend();

    @Override
    protected SessionBackendFactory backend() {
        return backend;
    }

    /** Each node gets three connections of its own: data, publish, and a dedicated pub/sub subscriber. */
    private static final class RedisBackend implements SessionBackendFactory {

        @Override
        public void reset() {
            RedisTestSupport.flushAll();
        }

        @Override
        public SessionBackend createNode(String nodeId, Consumer<AutoCloseable> ownedResources) {
            final StatefulRedisConnection<String, String> dataConn = RedisTestSupport.connect();
            final StatefulRedisConnection<String, String> pubConn = RedisTestSupport.connect();
            final StatefulRedisPubSubConnection<String, String> subConn = RedisTestSupport.connectPubSub();
            ownedResources.accept(dataConn);
            ownedResources.accept(pubConn);
            ownedResources.accept(subConn);

            final RedisPubSubSignalBus bus = new RedisPubSubSignalBus(pubConn, subConn);
            ownedResources.accept(bus);

            return SessionBackend.of(new RedisSessionLeaseStore(dataConn), bus, new RedisSessionInbox(dataConn),
                    new RedisIdempotencyStore(dataConn));
        }

        @Override
        public SessionLeaseStore createLeaseStore(Consumer<AutoCloseable> ownedResources) {
            return new RedisSessionLeaseStore(connection(ownedResources));
        }

        @Override
        public SessionInbox createInbox(Consumer<AutoCloseable> ownedResources) {
            return new RedisSessionInbox(connection(ownedResources));
        }

        @Override
        public IdempotencyStore createIdempotencyStore(Consumer<AutoCloseable> ownedResources) {
            return new RedisIdempotencyStore(connection(ownedResources));
        }

        private static StatefulRedisConnection<String, String> connection(Consumer<AutoCloseable> ownedResources) {
            final StatefulRedisConnection<String, String> conn = RedisTestSupport.connect();
            ownedResources.accept(conn);
            return conn;
        }
    }
}
