package at.aimon.session.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore;
import at.aimon.core.agent.session.inbox.CollectedBatch;
import at.aimon.core.agent.session.inbox.InMemorySessionInbox;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.inbox.UnreadableEntry;
import at.aimon.core.agent.session.signal.InMemorySignalBus;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionLeaseStore;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.fixture.RequestFixtures;
import at.aimon.session.routing.fixture.TestLiveSession;
import at.aimon.session.routing.fixture.TestManagerHarness;

/**
 * What the router owes the submitter of a message the holder took out of the inbox and could not read.
 *
 * <p>
 * The backends now report such an entry instead of throwing the batch away
 * (<a href="../../../../../../../docs/design/session/inbox-collect-durability.md">inbox-collect-durability.md</a>),
 * but a report nobody acts on is the same silence in a different place. The half that matters to a caller is here:
 * the address that survived the failure is turned back into a turn failure on the rail this router already uses for
 * a message it refused, so the submitter learns in one poll rather than at the five-minute forward deadline.
 *
 * <p>
 * <b>The batch that carries nothing readable is the point.</b> One queued message that cannot be decoded produces
 * exactly that shape, and every one of the three {@code collectPending} call sites either returns early on an empty
 * message list or has nothing else to fail — so a disposal written at a call site rather than inside
 * {@code collectPending} would drop the report on the commonest case of all.
 *
 * <p>
 * No backend container is involved. The subject is the router's disposal, not any codec's decoding, so a fake inbox
 * that hands back an {@link UnreadableEntry} says everything a planted document would — the same reason
 * {@code SessionRouterOrphanedForwardTest} fakes an inbox that cannot answer {@code isEmpty}.
 */
@DisplayName("SessionRouter — an entry the inbox could not decode")
class SessionRouterUnreadableEntryTest {

    private SessionLeaseStore leaseStore;
    private SessionSignalBus bus;
    private UnreadableOnCollectInbox inbox;
    private SessionRecordStore repository;
    private IdempotencyStore idempotency;

    private final List<TestManagerHarness> nodes = new ArrayList<>();

    @BeforeEach
    void wireSharedBackend() {
        leaseStore = new InMemorySessionLeaseStore();
        bus = new InMemorySignalBus();
        inbox = new UnreadableOnCollectInbox(new InMemorySessionInbox());
        repository = new InMemorySessionRecordStore();
        idempotency = new InMemoryIdempotencyStore();
    }

    @AfterEach
    void closeNodes() {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).close();
        }
    }

    @Test
    @DisplayName("a batch with nothing readable still fails its submitter, and long before the forward deadline")
    void anUndecodableQueuedMessageFailsTheWaitingCaller() throws Exception {
        final TestManagerHarness node = node("node-A", b -> b.idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-unreadable-1");

        // A lease the submitting node cannot take, so the submission can only be forwarded — and one that lapses
        // shortly, so this node's own forward-retry drain is what ends up collecting.
        leaseStore.tryAcquire(id, "dead-node", Duration.ofMillis(400)).orElseThrow();

        final SubmitDisposition forwarded = node.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // The inbox turns that one queued message into an unreadable entry carrying its turn id, which is the state
        // a corrupt-but-structurally-intact document produces.
        inbox.makeNextCollectUnreadable(forwarded.getTurnId().value());

        final Throwable failure = failureOf(forwarded.getFuture());
        assertThat(failure).hasMessageContaining("UNREADABLE");
        assertThat(node.session(id)).as("there was nothing readable to run, so no session should have opened").isNull();
    }

    @Test
    @DisplayName("an entry with no address left is dropped without taking the readable ones with it")
    void anUnaddressableEntryIsNotFatal() throws Exception {
        final TestManagerHarness node = node("node-A", b -> b.idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-unreadable-2");

        leaseStore.tryAcquire(id, "dead-node", Duration.ofMillis(400)).orElseThrow();
        final SubmitDisposition forwarded = node.manager().submit(RequestFixtures.submit(id, "alpha", "hello"));
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // The mixed batch, which is the one the title is about: one message the codec rebuilt, and beside it an
        // entry with no turn id and no key, so nothing on the rail can find its caller. The pass has to run the
        // first and stay silent about the second — the earlier version of this test reported zero readable
        // messages, which made "the readable ones" a claim nothing checked.
        inbox.addUnaddressableEntryToNextCollect();

        final TestLiveSession session = awaitSession(node, id);
        assertThat(session.awaitTurnStarted()).isTrue();
        assertThat(session.submittedInputs()).containsExactly("hello");
        session.completeCurrentTurn(TestLiveSession.ok("done"));

        assertThat(forwarded.getFuture().toCompletableFuture().get(10, TimeUnit.SECONDS).getFinalAnswer())
                .as("the readable message ran, and its caller got that answer rather than an UNREADABLE failure")
                .isEqualTo("done");
    }

    @Test
    @DisplayName("a turn id that will not rebuild does not take a usable idempotency key down with it")
    void anUnusableTurnIdFallsBackToTheKey() throws Exception {
        final TestManagerHarness node = node("node-A", b -> b.idempotencySecondaryTtl(Duration.ofSeconds(1)));
        final SessionId id = SessionId.of("c-unreadable-3");

        leaseStore.tryAcquire(id, "dead-node", Duration.ofMillis(400)).orElseThrow();
        final SubmitDisposition forwarded = node.manager()
                .submit(SubmitRequest.builder().sessionId(id).agentRef("alpha").userInput("hello")
                        .idempotencyKey("key-abc").initiator(Principal.user("tester")).build());
        assertThat(forwarded.getKind()).isEqualTo(SubmitDisposition.Kind.FORWARDED);

        // A blank turn id is what a damaged document yields: TurnId refuses it. The announcement only goes quiet
        // when *both* addresses are absent, so swallowing the conversion failure with an early return here would
        // strand a caller the key could have reached — which is the whole distance between this and a timeout.
        inbox.makeNextCollectUnreadable("   ", "key-abc");

        assertThat(failureOf(forwarded.getFuture())).hasMessageContaining("UNREADABLE");
    }

    private TestManagerHarness node(String nodeId, Consumer<TestManagerHarness.Builder> customizer) {
        final TestManagerHarness.Builder builder = TestManagerHarness.builder().nodeId(nodeId).leaseStore(leaseStore)
                .signalBus(bus).inbox(inbox).repository(repository).idempotencyStore(idempotency);
        customizer.accept(builder);
        final TestManagerHarness harness = builder.build();
        nodes.add(harness);
        return harness;
    }

    private static TestLiveSession awaitSession(TestManagerHarness harness, SessionId id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TestLiveSession.DEFAULT_AWAIT_MS;
        while (harness.session(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        final TestLiveSession session = harness.session(id);
        assertThat(session).as("a session for %s should have been opened", id).isNotNull();
        return session;
    }

    private static Throwable failureOf(CompletionStage<?> stage) throws InterruptedException {
        try {
            stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
            throw new AssertionError("expected the stage to fail");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("the caller was not answered — the five-minute deadline is the only thing left",
                    e);
        }
    }

    /**
     * An inbox that, once armed, reports whatever it holds as undecodable instead of returning it — the shape a
     * backend produces when a document survives storage but not this build's codec.
     */
    private static final class UnreadableOnCollectInbox implements SessionInbox {

        private final SessionInbox delegate;
        private volatile boolean armed;
        private volatile String turnId;
        private volatile String idempotencyKey;
        private volatile boolean keepReadable;

        UnreadableOnCollectInbox(SessionInbox delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        }

        void makeNextCollectUnreadable(String turnId) {
            makeNextCollectUnreadable(turnId, null);
        }

        void makeNextCollectUnreadable(String turnId, String idempotencyKey) {
            this.turnId = turnId;
            this.idempotencyKey = idempotencyKey;
            this.armed = true;
        }

        /**
         * Report an unaddressable entry <em>beside</em> whatever is readable, rather than in place of it — the
         * mixed batch a backend produces when only some of what it removed failed to decode.
         */
        void addUnaddressableEntryToNextCollect() {
            this.keepReadable = true;
            this.turnId = null;
            this.idempotencyKey = null;
            this.armed = true;
        }

        @Override
        public InboundMessageId deliver(InboundMessage message) {
            return delegate.deliver(message);
        }

        @Override
        public CollectedBatch collect(SessionId id, QueuedInputPriority maxPriority) {
            final CollectedBatch batch = delegate.collect(id, maxPriority);
            if (!armed || batch.getMessages().isEmpty()) {
                return batch;
            }
            final UnreadableEntry entry = UnreadableEntry.builder()
                    .id(batch.getMessages().get(0).getId().orElse(InboundMessageId.of("entry-1"))).turnId(turnId)
                    .idempotencyKey(idempotencyKey)
                    .reason("java.lang.IllegalArgumentException: No enum constant Principal.Type.ROBOT").build();
            if (keepReadable) {
                return CollectedBatch.of(batch.getMessages(), List.of(entry));
            }
            final List<UnreadableEntry> unreadable = new ArrayList<>();
            for (int i = 0; i < batch.getMessages().size(); i++) {
                unreadable.add(entry);
            }
            return CollectedBatch.of(List.of(), unreadable);
        }

        @Override
        public boolean isEmpty(SessionId id) {
            return delegate.isEmpty(id);
        }

        @Override
        public void purge(SessionId id) {
            delegate.purge(id);
        }
    }
}
