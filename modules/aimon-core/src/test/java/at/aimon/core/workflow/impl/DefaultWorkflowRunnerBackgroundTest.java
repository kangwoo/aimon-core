package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.workflow.RunHandle;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.RunQuery;
import at.aimon.core.workflow.RunStore;
import at.aimon.core.workflow.WorkflowBackgroundConfig;
import at.aimon.core.workflow.WorkflowRun;
import at.aimon.core.workflow.WorkflowRunState;

@DisplayName("DefaultWorkflowRunner — background runs, control plane, cancellation")
class DefaultWorkflowRunnerBackgroundTest {

    /** How long {@link #awaitState} polls for a run to reach a state before failing. */
    private static final long AWAIT_STATE_BUDGET_MILLIS = 5_000;

    /**
     * How long a stubbed subagent keeps blocking when nothing cancels it. Deliberately far larger than
     * {@link #AWAIT_STATE_BUDGET_MILLIS} rather than equal to it; see {@link #stubBlockUntilCancelled()}.
     */
    private static final long BLOCKED_SUBAGENT_SAFETY_MILLIS = 60_000;

    private final AtomicInteger execCount = new AtomicInteger();
    private SubagentExecutionManager manager;
    private SubagentExecutionEnvironment env;
    private Subagent sub;
    private DefaultWorkflowRunner runner;

    @BeforeEach
    void setUp() {
        manager = mock(SubagentExecutionManager.class);
        env = env();
        sub = subagent("worker");
        runner = new DefaultWorkflowRunner(manager, env);
    }

    @AfterEach
    void tearDown() {
        runner.close();
    }

    private void stubImmediateSuccess() {
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    execCount.incrementAndGet();
                    return success("ans:" + invocation.getArgument(2, String.class));
                });
    }

    /**
     * Stubs the subagent to block until THIS run's cancellation signal trips, then unwinds by throwing — which is
     * the whole point, and was the defect. A real subagent that is cancelled mid-flight propagates; it does not
     * return a value. This stub used to return {@code emptyFailure(...)} on that path, giving it two exits where
     * the real thing has one, and only one of them satisfied the assertions built on it.
     *
     * <p>
     * That mattered because of a deliberate rule in {@code DefaultWorkflowRunner.finalizeRun}: <em>a normal
     * completion wins even over a concurrently arriving stop request</em>, since the handle observably delivered a
     * value. So whenever the returning exit won the race, a stopped run settled COMPLETED and the test failed with
     * <em>"did not reach KILLED (was COMPLETED)"</em> — a sentence about the assertion rather than the cause, and
     * green on the next run. The runner was never wrong here; the stub was. Throwing removes the race rather than
     * narrowing it: the body now always completes exceptionally, and {@code isStopRequested()} decides the state.
     *
     * <p>
     * The wall clock is a safety valve against a hung build, not a wait any passing run reaches, and it must
     * outlast every {@link #awaitState} budget in the same test — it was once the same 5 seconds as that budget,
     * which let it fire mid-assertion. It now throws as well, so an expiry says so instead of impersonating a run
     * that finished on its own.
     */
    private void stubBlockUntilCancelled() {
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    final SubagentExecutionEnvironment perRun = invocation.getArgument(0);
                    final long deadline = System.currentTimeMillis() + BLOCKED_SUBAGENT_SAFETY_MILLIS;
                    while (!perRun.getCancellationSignal().isCancelled()) {
                        if (System.currentTimeMillis() >= deadline) {
                            throw new IllegalStateException("stub safety valve fired after "
                                    + BLOCKED_SUBAGENT_SAFETY_MILLIS + "ms: nothing cancelled this run");
                        }
                        Thread.sleep(5);
                    }
                    throw new CancellationException("subagent unwound: run cancelled");
                });
    }

    @Test
    @DisplayName("a background run completes: await returns the result and status settles COMPLETED")
    void completesAndAwaits() throws Exception {
        stubImmediateSuccess();
        final RunId id = RunId.from("audit");

        final RunHandle<String> handle = runner.runInBackground(ctx -> ctx.agent(sub, "g").text(), id);

        assertThat(handle.runId()).isEqualTo(id);
        assertThat(handle.await(Duration.ofSeconds(5))).isEqualTo("ans:g");
        awaitState(id, WorkflowRunState.COMPLETED);
    }

    @Test
    @DisplayName("a background run may fan out (hosting pool + fan-out pool coexist)")
    void backgroundRunFansOut() throws Exception {
        stubImmediateSuccess();
        final RunHandle<Integer> handle = runner.runInBackground(
                ctx -> ctx.parallel(java.util.List.of(() -> ctx.agent(sub, "a"), () -> ctx.agent(sub, "b"))).size(),
                RunId.from("fanout"));

        assertThat(handle.await(Duration.ofSeconds(5))).isEqualTo(2);
    }

    @Test
    @DisplayName("concurrent background runs share the runner-owned fan-out pool without corruption")
    void concurrentRunsShareFanoutPool() throws Exception {
        stubImmediateSuccess();
        final RunHandle<Integer> a = runner.runInBackground(
                ctx -> ctx.parallel(java.util.List.of(() -> ctx.agent(sub, "a1"), () -> ctx.agent(sub, "a2"))).size(),
                RunId.from("A"));
        final RunHandle<Integer> b = runner.runInBackground(
                ctx -> ctx.parallel(java.util.List.of(() -> ctx.agent(sub, "b1"), () -> ctx.agent(sub, "b2"))).size(),
                RunId.from("B"));

        assertThat(a.await(Duration.ofSeconds(5))).isEqualTo(2);
        assertThat(b.await(Duration.ofSeconds(5))).isEqualTo(2);
    }

    @Test
    @DisplayName("stop(runId) trips the per-run signal so an in-flight subagent unwinds and the run settles KILLED")
    void stopCancelsInFlightRun() {
        // Cooperative stop: the subagent unwinds only when THIS run's signal is cancelled.
        stubBlockUntilCancelled();
        final RunId id = RunId.from("longrun");

        runner.runInBackground(ctx -> ctx.agent(sub, "g").text(), id);
        awaitState(id, WorkflowRunState.RUNNING); // the subagent is now blocking

        assertThat(runner.stop(id)).isTrue();
        awaitState(id, WorkflowRunState.KILLED);
        awaitNotStoppable(id); // already finalized -> no live run
    }

    @Test
    @DisplayName("re-submitting a still-live run id is idempotent: same handle, dispatched once")
    void idempotentResubmit() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    execCount.incrementAndGet();
                    release.await();
                    return success("ans");
                });
        final RunId id = RunId.from("dup");

        final RunHandle<String> first = runner.runInBackground(ctx -> ctx.agent(sub, "g").text(), id);
        awaitState(id, WorkflowRunState.RUNNING);
        final RunHandle<String> second = runner.runInBackground(ctx -> ctx.agent(sub, "g").text(), id);

        assertThat(second).isSameAs(first); // idempotent — no duplicate dispatch
        release.countDown();
        assertThat(first.await(Duration.ofSeconds(5))).isEqualTo("ans");
        assertThat(execCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-submitting a TERMINAL run id dispatches a fresh run, not the old handle")
    void terminalResubmitRunsFresh() throws Exception {
        stubImmediateSuccess();
        final RunId id = RunId.from("reuse");

        final RunHandle<String> first = runner.runInBackground(ctx -> ctx.agent(sub, "g1").text(), id);
        assertThat(first.await(Duration.ofSeconds(5))).isEqualTo("ans:g1");
        awaitState(id, WorkflowRunState.COMPLETED);
        final int afterFirst = execCount.get();

        final RunHandle<String> second = runner.runInBackground(ctx -> ctx.agent(sub, "g2").text(), id);

        assertThat(second).isNotSameAs(first); // a terminal id re-dispatches, does not return the stale handle
        assertThat(second.await(Duration.ofSeconds(5))).isEqualTo("ans:g2");
        assertThat(execCount.get()).isEqualTo(afterFirst + 1);
    }

    @Test
    @DisplayName("a run rejected by a saturated (here: shut-down) hosting pool settles FAILED, await throws")
    void rejectedRunSettlesFailed() {
        stubImmediateSuccess();
        runner.close(); // shut the hosting pool so execute() rejects deterministically
        final RunId id = RunId.from("rejected");

        final RunHandle<String> handle = runner.runInBackground(ctx -> ctx.agent(sub, "g").text(), id);

        awaitState(id, WorkflowRunState.FAILED);
        assertThatThrownBy(() -> handle.await(Duration.ofSeconds(2))).isInstanceOf(ExecutionException.class);
        assertThat(execCount.get()).isZero(); // never dispatched
    }

    @Test
    @DisplayName("list/status read the run store")
    void listAndStatus() throws Exception {
        stubImmediateSuccess();
        final RunId id = RunId.from("listed");
        runner.runInBackground(ctx -> ctx.agent(sub, "g").text(), id).await(Duration.ofSeconds(5));
        awaitState(id, WorkflowRunState.COMPLETED);

        assertThat(runner.status(id)).isPresent();
        assertThat(runner.status(RunId.from("ghost"))).isEmpty();
        assertThat(runner.list(RunQuery.all())).extracting(r -> r.getRunId().scriptName()).contains("listed");
    }

    @Test
    @DisplayName("handle.future() is a detached view: an external cancel() cannot corrupt the run's tracked state")
    void externalHandleCancelDoesNotCorruptRun() {
        // The run must stay observably RUNNING until the control plane — not the stub — ends it.
        stubBlockUntilCancelled();
        final RunId id = RunId.from("cancel-proof");

        final RunHandle<String> handle = runner.runInBackground(ctx -> ctx.agent(sub, "g").text(), id);
        awaitState(id, WorkflowRunState.RUNNING);

        assertThat(handle.future().cancel(true)).isTrue(); // cancels only the caller's view
        // The run itself is untouched: not finalized, still RUNNING, and still stoppable via the control plane.
        assertThat(runner.status(id).map(WorkflowRun::getState)).contains(WorkflowRunState.RUNNING);
        assertThat(runner.stop(id)).isTrue();
        awaitState(id, WorkflowRunState.KILLED);
    }

    @Test
    @DisplayName("stop() on a still-queued run settles PENDING → KILLED without ever starting the script body")
    void stopWhileQueuedNeverStartsScript() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    release.await(5, TimeUnit.SECONDS);
                    return success("first");
                });
        final DefaultWorkflowRunner small = DefaultWorkflowRunner.builder(manager, env)
                .backgroundConfig(WorkflowBackgroundConfig.of(1, 1)).build();
        try {
            final RunId occupierId = RunId.from("occupier");
            small.runInBackground(ctx -> ctx.agent(sub, "g").text(), occupierId);
            awaitState(small, occupierId, WorkflowRunState.RUNNING); // the single hosting worker is now busy

            final AtomicBoolean queuedBodyRan = new AtomicBoolean();
            final RunId queuedId = RunId.from("queued");
            final RunHandle<String> queued = small.runInBackground(ctx -> {
                queuedBodyRan.set(true);
                return ctx.agent(sub, "q").text();
            }, queuedId);

            assertThat(small.stop(queuedId)).isTrue(); // the stop lands while the run is still queued (PENDING)
            release.countDown(); // the worker frees up and picks the queued body — which must not start the script
            awaitState(small, queuedId, WorkflowRunState.KILLED);
            assertThat(queuedBodyRan).isFalse();
            assertThat(queued.isDone()).isTrue();
        } finally {
            small.close();
        }
    }

    @Test
    @DisplayName("close() settles dropped/interrupted runs instead of leaving their futures incomplete forever")
    void closeSettlesQueuedRuns() {
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(10_000); // parked until close()'s shutdownNow interrupts the hosting worker
                    return success("never");
                });
        final DefaultWorkflowRunner small = DefaultWorkflowRunner.builder(manager, env)
                .backgroundConfig(WorkflowBackgroundConfig.builder().maxConcurrentRuns(1).queueCapacity(1)
                        .shutdownDrain(Duration.ofMillis(50)).build())
                .build();
        final RunId runningId = RunId.from("running");
        final RunId queuedId = RunId.from("queued");
        small.runInBackground(ctx -> ctx.agent(sub, "g").text(), runningId);
        awaitState(small, runningId, WorkflowRunState.RUNNING);
        final RunHandle<String> queued = small.runInBackground(ctx -> ctx.agent(sub, "q").text(), queuedId);

        small.close(); // drains 50ms, then force-stops: the running body is interrupted, the queued one is dropped

        assertThatThrownBy(() -> queued.await(Duration.ofSeconds(2))).isInstanceOf(ExecutionException.class);
        awaitState(small, queuedId, WorkflowRunState.FAILED);
        awaitState(small, runningId, WorkflowRunState.FAILED);
    }

    @Test
    @DisplayName("re-running a terminal background id with a real step cache replays instead of re-executing")
    void backgroundRunResumesFromStepCache() throws Exception {
        stubImmediateSuccess();
        final DefaultWorkflowRunner caching = DefaultWorkflowRunner.builder(manager, env)
                .stepResultCache(new InMemoryStepResultCache()).build();
        try {
            final RunId id = RunId.from("bg-resume");
            final RunHandle<String> first = caching.runInBackground(ctx -> ctx.agent(sub, "g").text(), id);
            assertThat(first.await(Duration.ofSeconds(5))).isEqualTo("ans:g");
            awaitState(caching, id, WorkflowRunState.COMPLETED);
            final int afterFirst = execCount.get();

            // A background run binds a non-null agentRuntimeId, so this exercises the scoped cache-key path.
            final RunHandle<String> second = caching.runInBackground(ctx -> ctx.agent(sub, "g").text(), id);
            assertThat(second.await(Duration.ofSeconds(5))).isEqualTo("ans:g");
            assertThat(execCount.get()).isEqualTo(afterFirst); // replayed from cache, not re-executed
        } finally {
            caching.close();
        }
    }

    @Test
    @DisplayName("an injected RunStore (multi-instance seam) receives the run lifecycle writes")
    void customRunStoreReceivesLifecycle() throws Exception {
        stubImmediateSuccess();
        final RunStore store = spy(new InMemoryRunStore());
        final DefaultWorkflowRunner custom = DefaultWorkflowRunner.builder(manager, env).runStore(store).build();
        try {
            final RunId id = RunId.from("custom-store");
            custom.runInBackground(ctx -> ctx.agent(sub, "g").text(), id).await(Duration.ofSeconds(5));
            awaitState(custom, id, WorkflowRunState.COMPLETED);

            verify(store).putIfAbsentOrTerminal(any(WorkflowRun.class));
            verify(store).transition(id, WorkflowRunState.RUNNING);
            verify(store).transition(id, WorkflowRunState.COMPLETED);
        } finally {
            custom.close();
        }
    }

    @Test
    @DisplayName("a genuinely saturated hosting pool (pool + queue full) rejects the submit and settles it FAILED")
    void saturationRejectsAndSettlesFailed() {
        final CountDownLatch release = new CountDownLatch(1);
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    release.await(5, TimeUnit.SECONDS);
                    return success("slow");
                });
        final DefaultWorkflowRunner small = DefaultWorkflowRunner.builder(manager, env)
                .backgroundConfig(WorkflowBackgroundConfig.of(1, 1)).build();
        try {
            final RunId runningId = RunId.from("running-slot");
            small.runInBackground(ctx -> ctx.agent(sub, "a").text(), runningId);
            awaitState(small, runningId, WorkflowRunState.RUNNING); // worker busy -> next submit queues
            small.runInBackground(ctx -> ctx.agent(sub, "b").text(), RunId.from("queued-slot"));

            final RunId rejectedId = RunId.from("rejected-slot");
            final RunHandle<String> rejected = small.runInBackground(ctx -> ctx.agent(sub, "c").text(), rejectedId);

            awaitState(small, rejectedId, WorkflowRunState.FAILED);
            assertThatThrownBy(() -> rejected.await(Duration.ofSeconds(2))).isInstanceOf(ExecutionException.class);
            release.countDown();
        } finally {
            small.close();
        }
    }

    @Test
    @DisplayName("a foreground run() still works after close() (close shuts only the hosting pool)")
    void foregroundUsableAfterClose() {
        stubImmediateSuccess();
        runner.close();

        final String out = runner.run(ctx -> ctx.agent(sub, "g").text(), RunId.from("fg"));

        assertThat(out).isEqualTo("ans:g");
    }

    /** Polls the shared runner's store until {@code id} reaches {@code expected}. */
    private void awaitState(RunId id, WorkflowRunState expected) {
        awaitState(runner, id, expected);
    }

    /**
     * Polls until {@code stop} reports no live run, then checks it stays that way.
     *
     * <p>
     * Not an assertion on the spot, because the two facts land in a deliberate order: {@code finalizeRun} writes
     * the store's terminal state BEFORE removing the registry entry, so that a re-submit arriving in between sees
     * a terminal run rather than a missing one. {@link #awaitState} reads the store, so a test that has just
     * watched KILLED appear can still be inside that window, where {@code stop} correctly reports the live entry
     * it can still see. Asserting immediately raced that removal and failed roughly one run in five with
     * "Expecting value to be false but was true" — about the assertion, not the cause. What the test means is
     * that a finalized run stops being stoppable, which is eventual, so it waits for it and then re-checks.
     */
    private void awaitNotStoppable(RunId id) {
        final long deadline = System.currentTimeMillis() + AWAIT_STATE_BUDGET_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!runner.stop(id)) {
                assertThat(runner.stop(id)).isFalse();
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while awaiting run " + id + " to stop being stoppable");
            }
        }
        fail("run " + id + " was still stoppable well after it finalized");
    }

    /** Polls {@code target}'s run store until {@code id} reaches {@code expected}, failing if it does not in time. */
    private void awaitState(DefaultWorkflowRunner target, RunId id, WorkflowRunState expected) {
        final long deadline = System.currentTimeMillis() + AWAIT_STATE_BUDGET_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (target.status(id).map(WorkflowRun::getState).filter(expected::equals).isPresent()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while awaiting state " + expected);
            }
        }
        fail("run " + id + " did not reach " + expected + " (was "
                + target.status(id).map(WorkflowRun::getState).orElse(null) + ")");
    }

    private static Subagent subagent(String name) {
        return Subagent.builder().name(name).systemPrompt("(inline)").build();
    }

    private static SubagentExecutionResult success(String answer) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }

    private static SubagentExecutionEnvironment env() {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }
}
