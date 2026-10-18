package at.aimon.session.testkit;

import org.junit.jupiter.api.BeforeEach;

import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordStore;

/**
 * Runs the record store contract against the reference implementation, so the contract itself is checked daemonless.
 * The two corruption cases are skipped here: an in-memory store keeps objects and has no encoded form to corrupt.
 */
class InMemorySessionRecordStoreContractTest extends AbstractSessionRecordStoreContractTest {

    private SessionRecordStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionRecordStore();
    }

    @Override
    protected SessionRecordStore store() {
        return store;
    }
}
