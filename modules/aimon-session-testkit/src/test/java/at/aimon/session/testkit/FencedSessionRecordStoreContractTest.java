package at.aimon.session.testkit;

import org.junit.jupiter.api.BeforeEach;

import at.aimon.core.agent.session.store.DefaultSessionStore;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionFence;
import at.aimon.core.agent.session.store.SessionRecordStore;

/**
 * Runs the record store contract through {@link DefaultSessionStore}'s fenced record view.
 *
 * <p>
 * {@link SessionFence#UNLESS_HELD_ELSEWHERE} with no lease held lets every guard pass, so what is checked is that each
 * method forwards — the bug a decorator can have is inheriting an interface default (the owner-carrying
 * {@code provision} refuses an owner) instead of passing the call on. Holdership rejection is
 * {@code DefaultSessionStore}'s own tests' business. The view refuses {@code clear()} by design.
 */
class FencedSessionRecordStoreContractTest extends AbstractSessionRecordStoreContractTest {

    private SessionRecordStore store;

    @BeforeEach
    void setUp() {
        store = new DefaultSessionStore(new InMemorySessionLeaseStore(), new InMemorySessionRecordStore())
                .records(SessionFence.UNLESS_HELD_ELSEWHERE);
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
