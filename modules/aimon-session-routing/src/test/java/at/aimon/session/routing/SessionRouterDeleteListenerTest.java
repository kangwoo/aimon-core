package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionDeletedEvent;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.SessionLifecycleListener;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.LeaseHolder;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * {@link SessionRouter#deleteSession} tells every {@link SessionLifecycleListener} once the record is gone — once,
 * under the lease, also when a later teardown step throws, and never when the record was not deleted.
 */
@DisplayName("SessionRouter deleteSession lifecycle listeners")
class SessionRouterDeleteListenerTest {

    private static final Principal ALICE = Principal.user("alice", "Alice");

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("each listener is told once, in order, with the recorded owner and binding, after the record is gone")
    void firesOnceWithRecordedOwner() {
        final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<SessionDeletedEvent> events = new CopyOnWriteArrayList<>();
        final List<Boolean> existedDuringCallback = new CopyOnWriteArrayList<>();
        harness = TestManagerHarness.builder().repository(repository).lifecycleListener(event -> {
            calls.add("first");
            events.add(event);
            existedDuringCallback.add(repository.exists(event.getSessionId()));
        }).lifecycleListener(event -> calls.add("second")).build();
        final SessionId id = SessionId.of("c-listen-1");
        repository.provision(id, "alpha", ALICE);

        harness.manager().deleteSession(id);

        assertThat(calls).containsExactly("first", "second");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getSessionId()).isEqualTo(id);
            assertThat(event.getOwner()).contains(ALICE);
            assertThat(event.getAgentRef()).contains("alpha");
            assertThat(event.getDeletedAt()).isNotNull();
        });
        assertThat(existedDuringCallback).containsExactly(false);
    }

    @Test
    @DisplayName("the listener runs while this node still holds the session's lease")
    void runsUnderTheLease() {
        final List<Optional<LeaseHolder>> holders = new CopyOnWriteArrayList<>();
        final TestManagerHarness[] self = new TestManagerHarness[1];
        harness = TestManagerHarness.builder().nodeId("node-A")
                .lifecycleListener(event -> holders.add(self[0].leaseStore().findHolder(event.getSessionId()))).build();
        self[0] = harness;
        final SessionId id = SessionId.of("c-listen-2");
        harness.repository().provision(id, "alpha");

        harness.manager().deleteSession(id);

        assertThat(holders).singleElement()
                .satisfies(holder -> assertThat(holder).map(LeaseHolder::getHolderId).contains("node-A"));
        assertThat(harness.leaseStore().findHolder(id)).as("returned once the listeners are done").isEmpty();
    }

    @Test
    @DisplayName("a throwing listener neither stops the next one nor fails the delete")
    void throwingListenerIsContained() {
        final AtomicInteger later = new AtomicInteger();
        harness = TestManagerHarness.builder().lifecycleListener(event -> {
            throw new IllegalStateException("listener boom");
        }).lifecycleListener(event -> later.incrementAndGet()).build();
        final SessionId id = SessionId.of("c-listen-3");
        harness.repository().provision(id, "alpha");

        harness.manager().deleteSession(id);

        assertThat(later).hasValue(1);
        assertThat(harness.repository().exists(id)).isFalse();
    }

    @Test
    @DisplayName("a listener throwing an Error propagates, and the delete still returns the lease")
    void errorFromListenerStillReturnsLease() {
        harness = TestManagerHarness.builder().nodeId("node-A").lifecycleListener(event -> {
            throw new AssertionError("listener error");
        }).build();
        final SessionId id = SessionId.of("c-listen-10");
        harness.repository().provision(id, "alpha");

        assertThatThrownBy(() -> harness.manager().deleteSession(id)).isInstanceOf(AssertionError.class)
                .hasMessage("listener error");

        assertThat(harness.repository().exists(id)).isFalse();
        // Before the fix the Error skipped returnLease: the lease was never released and its renewer kept it alive.
        assertThat(harness.leaseStore().findHolder(id)).as("the lease went back although the listener threw an Error")
                .isEmpty();
    }

    @Test
    @DisplayName("an EVICT publish that throws after the record is gone still fires the listeners, then propagates")
    void firesWhenTeardownThrows() {
        final List<SessionDeletedEvent> events = new CopyOnWriteArrayList<>();
        harness = TestManagerHarness.builder().signalBus(new FailingEvictBus()).lifecycleListener(events::add).build();
        final SessionId id = SessionId.of("c-listen-4");
        harness.repository().provision(id, "alpha", ALICE);

        assertThatThrownBy(() -> harness.manager().deleteSession(id)).hasMessage("bus outage");

        assertThat(harness.repository().exists(id)).isFalse();
        assertThat(events).singleElement().satisfies(event -> assertThat(event.getOwner()).contains(ALICE));
    }

    @Test
    @DisplayName("when a listener and a teardown step both throw, the teardown's exception reaches the caller")
    void teardownExceptionWins() {
        harness = TestManagerHarness.builder().signalBus(new FailingEvictBus()).lifecycleListener(event -> {
            throw new IllegalStateException("listener boom");
        }).build();
        final SessionId id = SessionId.of("c-listen-5");
        harness.repository().provision(id, "alpha");

        assertThatThrownBy(() -> harness.manager().deleteSession(id)).hasMessage("bus outage");
    }

    @Test
    @DisplayName("a delete that fails before the record goes fires nothing")
    void failedDeleteFiresNothing() {
        final List<SessionDeletedEvent> events = new CopyOnWriteArrayList<>();
        final InMemorySessionRecordStore failing = new InMemorySessionRecordStore() {
            @Override
            public void delete(SessionId sessionId) {
                throw new IllegalStateException("store down");
            }
        };
        harness = TestManagerHarness.builder().repository(failing).lifecycleListener(events::add).build();
        final SessionId id = SessionId.of("c-listen-6");
        failing.provision(id, "alpha");

        assertThatThrownBy(() -> harness.manager().deleteSession(id)).hasMessage("store down");

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("an unreadable record is still deleted, and the listeners hear of it with no owner")
    void unreadableRecordStillFires() {
        final List<SessionDeletedEvent> events = new CopyOnWriteArrayList<>();
        final InMemorySessionRecordStore unreadable = new InMemorySessionRecordStore() {
            @Override
            public Optional<SessionRecordView> load(SessionId sessionId) {
                throw new IllegalArgumentException("corrupt owner");
            }
        };
        harness = TestManagerHarness.builder().repository(unreadable).lifecycleListener(events::add).build();
        final SessionId id = SessionId.of("c-listen-7");
        unreadable.provision(id, "alpha", ALICE);

        harness.manager().deleteSession(id);

        assertThat(unreadable.exists(id)).isFalse();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getOwner()).isEmpty();
            assertThat(event.getAgentRef()).isEmpty();
        });
    }

    @Test
    @DisplayName("deleting an id that never existed still fires, with no owner")
    void neverExistedStillFires() {
        final List<SessionDeletedEvent> events = new CopyOnWriteArrayList<>();
        harness = TestManagerHarness.builder().lifecycleListener(events::add).build();

        harness.manager().deleteSession(SessionId.of("c-listen-8"));

        assertThat(events).singleElement().satisfies(event -> assertThat(event.getOwner()).isEmpty());
    }

    @Test
    @DisplayName("with no listeners the delete reads nothing extra")
    void noListenersNoLoad() {
        final AtomicInteger loads = new AtomicInteger();
        final InMemorySessionRecordStore counting = new InMemorySessionRecordStore() {
            @Override
            public Optional<SessionRecordView> load(SessionId sessionId) {
                loads.incrementAndGet();
                return super.load(sessionId);
            }
        };
        harness = TestManagerHarness.builder().repository(counting).build();
        final SessionId id = SessionId.of("c-listen-9");
        counting.provision(id, "alpha");

        harness.manager().deleteSession(id);

        assertThat(loads).hasValue(0);
    }

    /** An in-memory bus whose EVICT publish fails, as Redis and Postgres buses do during an outage. */
    private static final class FailingEvictBus implements SessionSignalBus {

        private final InMemorySignalBus delegate = new InMemorySignalBus();

        @Override
        public Subscription subscribe(SessionId id, Consumer<SessionSignal> handler) {
            return delegate.subscribe(id, handler);
        }

        @Override
        public void publish(SessionSignal signal) {
            if (signal.getKind() == SessionSignal.SignalKind.EVICT) {
                throw new IllegalStateException("bus outage");
            }
            delegate.publish(signal);
        }
    }
}
