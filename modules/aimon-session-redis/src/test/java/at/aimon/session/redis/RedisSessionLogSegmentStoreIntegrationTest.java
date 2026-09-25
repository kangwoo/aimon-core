package at.aimon.session.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.session.testkit.AbstractSessionLogSegmentStoreContractTest;
import io.lettuce.core.api.StatefulRedisConnection;

@DisplayName("RedisSessionLogSegmentStore integration")
@Tag("docker")
class RedisSessionLogSegmentStoreIntegrationTest extends AbstractSessionLogSegmentStoreContractTest {

    private StatefulRedisConnection<String, String> connection;
    private SessionLogSegmentStore store;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        connection = RedisTestSupport.connect();
        store = new RedisSessionLogSegmentStore(connection);
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    @Override
    protected SessionLogSegmentStore store() {
        return store;
    }
}
