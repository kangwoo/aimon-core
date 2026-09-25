package at.aimon.session.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.session.testkit.AbstractSessionLogSegmentStoreContractTest;

@DisplayName("PostgresSessionLogSegmentStore integration")
@Tag("docker")
class PostgresSessionLogSegmentStoreIntegrationTest extends AbstractSessionLogSegmentStoreContractTest {

    private SessionLogSegmentStore store;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        store = new PostgresSessionLogSegmentStore(PostgresTestSupport.dataSource());
    }

    @Override
    protected SessionLogSegmentStore store() {
        return store;
    }
}
