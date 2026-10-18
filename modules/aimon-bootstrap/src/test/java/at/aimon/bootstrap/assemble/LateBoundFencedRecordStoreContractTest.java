package at.aimon.bootstrap.assemble;

import org.junit.jupiter.api.BeforeEach;

import at.aimon.core.agent.session.store.DefaultSessionStore;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionFence;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.session.testkit.AbstractSessionRecordStoreContractTest;

/**
 * Runs the record store contract through {@link LateBoundFencedRecordStore}, bound as the assembly binds it.
 *
 * <p>
 * The wrapper reads from the raw store and writes through the bound fenced view, so the two must sit over the
 * <em>same</em> raw instance — as they do in assembly, where the router's {@code DefaultSessionStore} wraps the store
 * this one was built over. Given a second, fresh store behind the view, every read here would miss every write. The
 * guard is {@link SessionFence#UNLESS_HELD_ELSEWHERE} with no lease held, so every write passes and what is checked is
 * that each method forwards; {@code clear()} is refused by the fenced view.
 */
class LateBoundFencedRecordStoreContractTest extends AbstractSessionRecordStoreContractTest {

    private SessionRecordStore store;

    @BeforeEach
    void setUp() {
        final InMemorySessionRecordStore raw = new InMemorySessionRecordStore();
        final LateBoundFencedRecordStore lateBound = new LateBoundFencedRecordStore(raw);
        lateBound.bind(new DefaultSessionStore(new InMemorySessionLeaseStore(), raw)
                .records(SessionFence.UNLESS_HELD_ELSEWHERE));
        store = lateBound;
    }

    @Override
    protected SessionRecordStore store() {
        return store;
    }

    @Override
    protected boolean supportsClear() {
        return false;
    }
}
