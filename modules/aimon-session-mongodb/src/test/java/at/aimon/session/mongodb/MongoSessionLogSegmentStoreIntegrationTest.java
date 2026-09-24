package at.aimon.session.mongodb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.session.testkit.AbstractSessionLogSegmentStoreContractTest;

@DisplayName("MongoSessionLogSegmentStore integration")
@Tag("docker")
class MongoSessionLogSegmentStoreIntegrationTest extends AbstractSessionLogSegmentStoreContractTest {

    private SessionLogSegmentStore store;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        store = new MongoSessionLogSegmentStore(MongoTestSupport.sharedDatabase());
    }

    @Override
    protected SessionLogSegmentStore store() {
        return store;
    }
}
