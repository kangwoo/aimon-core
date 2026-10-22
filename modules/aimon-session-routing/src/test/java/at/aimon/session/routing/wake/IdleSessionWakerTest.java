package at.aimon.session.routing.wake;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.TokenUsage;
import at.aimon.session.routing.ClusterSessionStatus;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.SubmitRequest;

@DisplayName("IdleSessionWaker — wakes an idle session for its background notifications, within its caps")
class IdleSessionWakerTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.fromName("ops", "acme");
    private static final SessionId SESSION = SessionId.of("session-1");
    private static final Principal ALICE = Principal.user("alice");
    private static final SessionId FENCE = SessionId.of("fence");

    private MessageQueueManager queue;
    private InMemorySessionRecordStore records;
    private FakeRouter router;
    private InMemoryWakeLedger ledger;
    private IdleSessionWaker waker;

    @BeforeEach
    void setUp() {
        queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        records = new InMemorySessionRecordStore();
        records.provision(SESSION, "ops", ALICE);
        records.provision(FENCE, "ops", ALICE);
        router = new FakeRouter();
        ledger = new InMemoryWakeLedger();
    }

    @AfterEach
    void tearDown() {
        if (waker != null) {
            waker.close();
        }
    }

    private IdleSessionWaker.Builder waker() {
        return IdleSessionWaker.builder(router, queue, records).ledger(ledger)
                .policy(WakePolicy.builder().debounce(Duration.ofMillis(50)).build());
    }

    private static QueuedInput monitorEvent(String text) {
        return QueuedInput.builder().agentRuntimeId(RUNTIME).sessionId(SESSION.value()).inputText(text)
                .metadata(Map.of("kind", "monitor-event", "monitorId", "mon_1")).build();
    }

    @Test
    @DisplayName("an idle owned session is woken with its notifications as input, as its owner")
    void wakesIdleSession() {
        waker = waker().build();

        queue.enqueue(monitorEvent("Monitor mon_1 (errors) — 1 event:\nERROR one"));
        queue.enqueue(monitorEvent("Monitor mon_1 (errors) — 1 event:\nERROR two"));

        await(() -> woken().size() == 1);
        final SubmitRequest request = woken().get(0);
        assertThat(request.getSessionId()).isEqualTo(SESSION);
        assertThat(request.getAgentRef()).isEqualTo("ops");
        assertThat(request.getContextDiscriminator()).contains("acme");
        assertThat(request.getSubmitOptions().getPrincipal()).contains(ALICE);
        assertThat(request.getSubmitOptions().getExecutionAttributes())
                .containsEntry(IdleSessionWaker.WAKE_REASON_ATTRIBUTE, "monitor-event");
        assertThat(request.getInitiator()).isEqualTo(Principal.system());
        assertThat(request.getUserInput().asText()).contains("<system-reminder key=\"background-wake\">")
                .contains("ERROR one").contains("ERROR two");
        assertThat(queue.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("kinds outside the policy, entries without a session and user follow-ups wake nothing")
    void onlyPolicyKindsWake() throws Exception {
        waker = waker().build();

        queue.enqueue(QueuedInput.builder().agentRuntimeId(RUNTIME).sessionId(SESSION.value()).inputText("done")
                .metadata(Map.of("kind", "subagent-task-completed", "taskId", "t1")).build());
        queue.enqueue(QueuedInput.builder().agentRuntimeId(RUNTIME).sessionId(SESSION.value())
                .inputText("typed while busy").build());
        queue.enqueue(QueuedInput.builder().agentRuntimeId(RUNTIME).inputText("runtime only")
                .metadata(Map.of("kind", "monitor-event")).build());
        fence();

        assertThat(woken()).isEmpty();
        assertThat(queue.snapshot()).hasSize(3);
    }

    @Test
    @DisplayName("a session without an owner is not woken, and its notifications stay queued")
    void ownerlessSessionIsNotWoken() throws Exception {
        final SessionId ownerless = SessionId.of("session-2");
        records.provision(ownerless, "ops");
        waker = waker().build();

        queue.enqueue(QueuedInput.builder().agentRuntimeId(RUNTIME).sessionId(ownerless.value()).inputText("x")
                .metadata(Map.of("kind", "monitor-event")).build());
        fence();

        assertThat(woken()).isEmpty();
        assertThat(queue.snapshot()).hasSize(1);
    }

    @Test
    @DisplayName("a session this node is running a turn for is left to that turn")
    void runningSessionIsLeftToItsTurn() throws Exception {
        router.status.set(ClusterSessionStatus.localHolder(
                LiveSessionStatus.builder().sessionId(SESSION).phase(LiveSessionStatus.Phase.RUNNING).build(),
                "node-a"));
        waker = waker().build();

        queue.enqueue(monitorEvent("while running"));
        fence();

        assertThat(woken()).isEmpty();
        assertThat(queue.snapshot()).hasSize(1);

        // The turn ended without reading them: the waker, still looking, wakes the session now that it is idle.
        router.status.set(ClusterSessionStatus.unknown(SESSION));
        await(() -> woken().size() == 1);
    }

    @Test
    @DisplayName("the hourly cap holds: past it, notifications wait for the next turn")
    void hourlyCap() throws Exception {
        waker = waker().policy(WakePolicy.builder().debounce(Duration.ofMillis(50)).maxPerHour(1).build()).build();

        queue.enqueue(monitorEvent("first"));
        await(() -> woken().size() == 1);
        bumpTurnCount(1);
        queue.enqueue(monitorEvent("second"));
        fence();

        assertThat(woken()).hasSize(1);
        assertThat(queue.snapshot()).extracting(QueuedInput::getInputText).containsExactly("second");
    }

    @Test
    @DisplayName("the consecutive cap resets once another turn has run on the session")
    void consecutiveCapResetsAfterAnotherTurn() throws Exception {
        waker = waker().policy(WakePolicy.builder().debounce(Duration.ofMillis(50)).maxConsecutive(1).build()).build();

        queue.enqueue(monitorEvent("first"));
        await(() -> woken().size() == 1);
        // The wake turn ran: one turn on the record, which is what the waker read when it settled.
        bumpTurnCount(1);
        router.complete(0);
        await(() -> !ledger.get(SESSION).isOutstanding());

        queue.enqueue(monitorEvent("second"));
        fence();
        assertThat(woken()).hasSize(1);

        // The user's turn: the count moves past what the waker recorded, so the streak is over.
        bumpTurnCount(2);
        queue.enqueue(monitorEvent("third"));
        await(() -> woken().size() == 2);
        assertThat(woken().get(1).getUserInput().asText()).contains("second").contains("third");
    }

    @Test
    @DisplayName("a refused submit puts the notifications back and retries later")
    void refusedSubmitIsRetried() {
        router.failures.set(1);
        waker = waker()
                .policy(WakePolicy.builder().debounce(Duration.ofMillis(50)).retryDelay(Duration.ofMillis(200)).build())
                .build();

        queue.enqueue(monitorEvent("keep me"));

        await(() -> router.attempts.get() == 2 && woken().size() == 1);
        assertThat(woken().get(0).getUserInput().asText()).contains("keep me");
        assertThat(queue.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("a forgotten session's pending wake is cancelled")
    void forgetCancelsPendingWake() throws Exception {
        waker = waker().policy(WakePolicy.builder().debounce(Duration.ofMillis(200)).build()).build();

        queue.enqueue(monitorEvent("x"));
        waker.forget(SESSION);
        fence();

        assertThat(woken()).isEmpty();
    }

    @Test
    @DisplayName("a retry carries a new idempotency key: the router would drop a reused one as a duplicate")
    void retryUsesANewIdempotencyKey() {
        router.failures.set(1);
        waker = waker()
                .policy(WakePolicy.builder().debounce(Duration.ofMillis(50)).retryDelay(Duration.ofMillis(200)).build())
                .build();

        queue.enqueue(monitorEvent("keep me"));

        await(() -> router.attempts.get() == 2 && woken().size() == 1);
        assertThat(router.keys).hasSize(2).doesNotHaveDuplicates();
        assertThat(router.keys).allMatch(key -> key.startsWith("wake:"));
    }

    @Test
    @DisplayName("while a turn runs here the waker looks again only as long as entries are left")
    void recheckStopsOnceTheTurnTookThem() throws Exception {
        router.status.set(ClusterSessionStatus.localHolder(
                LiveSessionStatus.builder().sessionId(SESSION).phase(LiveSessionStatus.Phase.RUNNING).build(), "n"));
        waker = waker().policy(WakePolicy.builder().debounce(Duration.ofMillis(20)).build()).build();
        queue.enqueue(monitorEvent("read by the running turn"));
        fence();

        // The running turn's own drain takes them; the session then goes idle with nothing left to deliver.
        queue.drainForInjection(q -> q.getSessionId().isPresent() && q.getSessionId().get().equals(SESSION.value()),
                QueuedInputPriority.LATER);
        router.status.set(ClusterSessionStatus.unknown(SESSION));
        Thread.sleep(1_500);
        fence();

        assertThat(woken()).isEmpty();
    }

    @Test
    @DisplayName("the request customizer supplies reopen options; one that throws leaves the notifications queued")
    void customizerAppliesOrSkips() throws Exception {
        final LiveSessionOptions options = LiveSessionOptions.defaults();
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        waker = waker().requestCustomizer((request, record) -> {
            // Once: the session under test is woken before the fence (one thread, schedule order).
            final RuntimeException fail = failure.getAndSet(null);
            if (fail != null) {
                throw fail;
            }
            request.options(options);
        }).build();

        queue.enqueue(monitorEvent("first"));
        await(() -> woken().size() == 1);
        assertThat(woken().get(0).getOptions()).isSameAs(options);

        failure.set(new IllegalStateException("tenant lookup down"));
        queue.enqueue(monitorEvent("second"));
        fence();
        assertThat(woken()).hasSize(1);
        assertThat(queue.snapshot()).extracting(QueuedInput::getInputText).contains("second");
    }

    /**
     * Waits until a wake of another session has been submitted. The waker runs on one thread in schedule order, so
     * every wake scheduled before this one has run by then — a negative check that does not depend on a sleep.
     */
    private void fence() {
        final int before = (int) router.submits.stream().filter(r -> r.getSessionId().equals(FENCE)).count();
        queue.enqueue(QueuedInput.builder().agentRuntimeId(RUNTIME).sessionId(FENCE.value()).inputText("fence")
                .metadata(Map.of("kind", "monitor-event")).build());
        await(() -> router.submits.stream().filter(r -> r.getSessionId().equals(FENCE)).count() > before);
        // Drop the fence's own ledger entry so the fence session never hits a cap across a long test.
        ledger.remove(FENCE);
    }

    /** The submits for the session under test, without the fence's. */
    private List<SubmitRequest> woken() {
        return router.submits.stream().filter(r -> r.getSessionId().equals(SESSION)).toList();
    }

    private void bumpTurnCount(int turnCount) {
        records.setTotalsAndBudgetOverride(SESSION, SessionTotals.of(turnCount, turnCount, TokenUsage.empty()), null);
    }

    private static void await(BooleanSupplier condition) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Records submits; the turns complete when the test says so. */
    private final class FakeRouter implements SessionRouter {

        final List<SubmitRequest> submits = new CopyOnWriteArrayList<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<at.aimon.core.agent.AgentExecutionResult>> turns = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger settled = new AtomicInteger();
        final AtomicReference<ClusterSessionStatus> status = new AtomicReference<>(
                ClusterSessionStatus.unknown(SESSION));

        @Override
        public SubmitDisposition submit(SubmitRequest request) {
            attempts.incrementAndGet();
            request.getIdempotencyKey().ifPresent(keys::add);
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("router shutting down");
            }
            submits.add(request);
            final CompletableFuture<at.aimon.core.agent.AgentExecutionResult> turn = new CompletableFuture<>();
            turn.whenComplete((r, e) -> settled.incrementAndGet());
            turns.add(turn);
            return SubmitDisposition.executedLocally(TurnId.generate(), turn);
        }

        void complete(int index) {
            turns.get(index).complete(null);
        }

        @Override
        public ClusterSessionStatus status(SessionId sessionId) {
            return sessionId.equals(SESSION) ? status.get() : ClusterSessionStatus.unknown(sessionId);
        }

        @Override
        public Flow.Publisher<AgentExecutionEvent> events(SessionId sessionId) {
            return new SubmissionPublisher<>();
        }

        @Override
        public void interrupt(SessionId sessionId, InterruptReason reason) {
        }

        @Override
        public void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason) {
        }

        @Override
        public void releaseSession(SessionId sessionId) {
        }

        @Override
        public void close() {
        }
    }
}
