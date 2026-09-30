package at.aimon.session.testkit;

import org.junit.jupiter.api.BeforeEach;

import at.aimon.core.agent.session.store.InMemorySessionLogSegmentStore;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;

/**
 * Runs the segment store contract against the reference implementation, so the contract itself is checked daemonless.
 */
class InMemorySessionLogSegmentStoreContractTest extends AbstractSessionLogSegmentStoreContractTest {

    private SessionLogSegmentStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionLogSegmentStore();
    }

    @Override
    protected SessionLogSegmentStore store() {
        return store;
    }
}
