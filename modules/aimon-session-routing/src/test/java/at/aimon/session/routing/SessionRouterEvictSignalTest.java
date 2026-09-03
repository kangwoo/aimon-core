package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.InterruptedAt;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * Cross-node EVICT receiver behavior: the manager must emit {@link InterruptedAt} with the payload-reason before
 * {@code onComplete} so subscribers see the same terminal envelope as on the originating node.
 */
@DisplayName("SessionRouter EVICT signal handler")
class SessionRouterEvictSignalTest {

    private TestManagerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("EVICT from a peer node emits InterruptedAt(reason) before onComplete")
    void evictEmitsInterruptedAtThenComplete() throws Exception {
        harness = TestManagerHarness.builder().nodeId("node-A").build();
        final SessionId id = SessionId.of("c-evict-1");

        final CopyOnWriteArrayList<AgentExecutionEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch terminated = new CountDownLatch(1);

        final Flow.Publisher<AgentExecutionEvent> events = harness.manager().events(id);
        events.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentExecutionEvent item) {
                received.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                /* not expected */ }

            @Override
            public void onComplete() {
                terminated.countDown();
            }
        });

        // Simulate a peer node publishing EVICT for this session. originNodeId must differ from the local
        // node-A so the receiver does not short-circuit on the same-origin filter.
        harness.signalBus().publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.EVICT)
                .originNodeId("node-B").payload(Map.of("reason", InterruptReason.SESSION_RELEASED.name())).build());

        assertThat(terminated.await(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS))
                .as("subscriber must receive onComplete after EVICT").isTrue();
        assertThat(received).as("subscriber must observe the synthesized terminal InterruptedAt").hasSize(1);
        assertThat(received.get(0)).isInstanceOf(InterruptedAt.class);
        assertThat(((InterruptedAt) received.get(0)).getReason()).isEqualTo(InterruptReason.SESSION_RELEASED);
    }

    @Test
    @DisplayName("EVICT with unparseable payload reason falls back to USER_SIGINT but still emits InterruptedAt")
    void evictWithUnknownReasonFallsBack() throws Exception {
        harness = TestManagerHarness.builder().nodeId("node-A").build();
        final SessionId id = SessionId.of("c-evict-2");

        final CopyOnWriteArrayList<AgentExecutionEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch terminated = new CountDownLatch(1);

        final Flow.Publisher<AgentExecutionEvent> events = harness.manager().events(id);
        events.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentExecutionEvent item) {
                received.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                /* not expected */ }

            @Override
            public void onComplete() {
                terminated.countDown();
            }
        });

        harness.signalBus().publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.EVICT)
                .originNodeId("node-B").payload(Map.of("reason", "not_a_real_reason")).build());

        assertThat(terminated.await(TestLiveSession.DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(((InterruptedAt) received.get(0)).getReason()).isEqualTo(InterruptReason.USER_SIGINT);
    }

    @Test
    @DisplayName("local releaseSession publishes EVICT with InterruptReason.SESSION_RELEASED.name() (uppercase)")
    void localReleasePublishesUppercaseReason() throws Exception {
        harness = TestManagerHarness.builder().nodeId("node-A").build();
        final SessionId id = SessionId.of("c-evict-3");

        final CopyOnWriteArrayList<SessionSignal> evictSignals = new CopyOnWriteArrayList<>();
        harness.signalBus().subscribe(id, signal -> {
            if (signal.getKind() == SessionSignal.SignalKind.EVICT) {
                evictSignals.add(signal);
            }
        });

        harness.manager().releaseSession(id);

        assertThat(evictSignals).hasSize(1);
        final Object reasonPayload = evictSignals.get(0).getPayload().get("reason");
        // Must be uppercase enum name so peers' parseReason(InterruptReason.valueOf(...)) succeeds.
        assertThat(reasonPayload).isEqualTo(InterruptReason.SESSION_RELEASED.name());
    }

    @Test
    @DisplayName("EVICT from a peer node drops the cached agentRef binding so the conversation stays rebindable")
    void evictInvalidatesCachedBinding() throws Exception {
        harness = TestManagerHarness.builder().nodeId("node-A").build();
        final SessionId id = SessionId.of("c-evict-4");

        harness.repository().provision(id, "alpha");

        // The holder-side subscribe cannot arm the rail here: this node deliberately never wins the lock, so it stays
        // a pure observer and only events() puts it on the rail. Without this the node never sees the peer's EVICT.
        harness.manager().events(id);

        // Hold the lock as a peer so node-A's submit populates the binding cache from the repository and then bails
        // out to the inbox — no local session is opened, which keeps the eviction below from closing a session the
        // second submit would reuse.
        final Optional<SessionLease> peerLease = harness.leaseStore().tryAcquire(id, "node-B/holder",
                Duration.ofSeconds(30));
        assertThat(peerLease).isPresent();

        assertThat(harness.manager().submit(RequestFixtures.submit(id, "alpha", "hello")).getKind())
                .isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // Peer node-B deletes the session: the shared repository row disappears, the queued message goes with it, and
        // EVICT is broadcast. The purge is not decoration — a real deleteSession does it, and leaving it out stages a
        // state the cluster never reaches: an inbox holding an "alpha" message for a session that no longer exists.
        // Once the peer's lease is released below, a doorbell-driven drain can pick that message up and provision the
        // record straight back as "alpha", and the "beta" submit then fails on a binding conflict that has nothing to
        // do with the cached binding this test is about.
        harness.repository().delete(id);
        harness.inbox().purge(id);
        harness.signalBus().publish(SessionSignal.builder().sessionId(id).kind(SessionSignal.SignalKind.EVICT)
                .originNodeId("node-B").payload(Map.of("reason", InterruptReason.SESSION_RELEASED.name())).build());
        harness.leaseStore().release(peerLease.get());

        // The binding no longer exists anywhere, so a fresh agentRef must be accepted. A surviving cache entry would
        // reject it with ConflictingAgentException and leave the id permanently unusable on this node.
        //
        // Acceptance is the property, and the disposition is not part of it. precheckAgentBinding is the only thing
        // that can reject this submission and it runs at the very top of submit(), before the turn gate and before
        // any forward decision — so "was it accepted" is answered identically on every path out. Which path it then
        // takes is scheduling: the first submit's deliverToInbox rang this node's own doorbell, and the drain task
        // that answers it takes the node-local turn gate for the length of an empty pass. Land that pass here and the
        // gate is busy, which submit() reports as FORWARDED without ever reconsidering the binding. Asserting
        // EXECUTED_LOCALLY therefore asserted that a background task had already finished, which on a loaded runner
        // it has not.
        //
        // Nothing is given up by dropping it, because the turn below runs either way: a forwarded submission is
        // drained by the very doorbell its own delivery rings, and runDrainOnly provisions the record under the new
        // agentRef and opens the session. So the end-to-end claim — the fresh binding is taken and its turn runs —
        // is still made, and now without depending on which of this node's two paths got there first.
        assertThatCode(() -> harness.manager().submit(RequestFixtures.submit(id, "beta", "hi")))
                .doesNotThrowAnyException();

        final TestLiveSession session = waitForSession(id);
        assertThat(session.awaitTurnStarted()).isTrue();
        session.completeCurrentTurn(TestLiveSession.ok("done"));
    }

    private TestLiveSession waitForSession(SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("second submit must open a session under the new binding").isNotNull();
        return session;
    }
}
