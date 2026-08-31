package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
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
import at.aimon.core.workflow.AgentStepResult;
import at.aimon.core.workflow.AgentTask;
import at.aimon.core.workflow.RunHandle;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.StepKey;
import at.aimon.core.workflow.StepOutcome;
import at.aimon.core.workflow.StepResultCache;
import at.aimon.core.workflow.WorkflowConcurrencyConfig;
import at.aimon.core.workflow.WorkflowRun;
import at.aimon.core.workflow.WorkflowRunState;
import at.aimon.core.workflow.WorktreeEnvironmentFactory;
import at.aimon.core.workflow.exception.WorkflowException;

@DisplayName("Workflow Phase 4 — nested parallel, worktree isolation, non-cacheable, structure guard")
class WorkflowPhase4Test {

    private final List<DefaultWorkflowRunner> runners = new ArrayList<>();
    private SubagentExecutionManager manager;
    private SubagentExecutionEnvironment env;
    private Subagent sub;
    private final AtomicInteger executeCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        manager = mock(SubagentExecutionManager.class);
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    executeCount.incrementAndGet();
                    return success("ans:" + invocation.getArgument(2, String.class));
                });
        env = env();
        sub = subagent("worker");
    }

    @AfterEach
    void tearDown() {
        runners.forEach(DefaultWorkflowRunner::close);
    }

    @Test
    @DisplayName("Isolate=true resolves a scoped env even under NO_OP (default run)")
    void isolateResolvesScopedEnvUnderNoOp() {
        final RecordingFactory factory = new RecordingFactory();
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).worktreeFactory(factory)
                .build();
        runners.add(runner);

        // run() uses DEFAULT_RUN_ID → NO_OP cache. Isolation must still route through the factory (env resolution is
        // independent of cache state).
        runner.run(ctx -> ctx.agent(AgentTask.builder().subagent(sub).goal("g").isolate(true).build()));

        assertThat(factory.branchKeys).hasSize(1);
    }

    @Test
    @DisplayName("C30: isolate=true with no factory is run-fatal, even under NO_OP")
    void isolateWithoutFactoryIsRunFatal() {
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).build();
        runners.add(runner);

        assertThatThrownBy(
                () -> runner.run(ctx -> ctx.agent(AgentTask.builder().subagent(sub).goal("g").isolate(true).build())))
                .isInstanceOf(WorkflowException.class);
    }

    @Test
    @DisplayName("A non-cacheable step is never replayed — it re-executes on every resume")
    void nonCacheableStepNeverReplayed() {
        final StepResultCache cache = new InMemoryStepResultCache();
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).stepResultCache(cache).build();
        runners.add(runner);
        final RunId id = RunId.of("run:nc");

        runner.run(ctx -> ctx.agent(AgentTask.builder().subagent(sub).goal("g").nonCacheable(true).build()), id);
        runner.run(ctx -> ctx.agent(AgentTask.builder().subagent(sub).goal("g").nonCacheable(true).build()), id);

        assertThat(executeCount.get()).isEqualTo(2); // both runs executed — no replay
    }

    @Test
    @DisplayName("a plain cacheable step replays on the second run under the same id")
    void cacheableStepReplays() {
        final StepResultCache cache = new InMemoryStepResultCache();
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).stepResultCache(cache).build();
        runners.add(runner);
        final RunId id = RunId.of("run:c");

        runner.run(ctx -> ctx.agent(sub, "g"), id);
        runner.run(ctx -> ctx.agent(sub, "g"), id);

        assertThat(executeCount.get()).isEqualTo(1); // second run replayed the memoized outcome
    }

    @Test
    @DisplayName("A homogeneous same-definition sibling shift re-executes (no mis-replay) under resume")
    void structureGuardRejectsSiblingShift() {
        final StepResultCache cache = new InMemoryStepResultCache();
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).stepResultCache(cache).build();
        runners.add(runner);
        final RunId id = RunId.of("run:shift");

        // Run 1: [X, T, T] — cache a0=X, a1=T, a2=T (3 executes).
        runner.run(ctx -> {
            ctx.agent(sub, "X");
            ctx.agent(sub, "T");
            return ctx.agent(sub, "T");
        }, id);
        // Run 2 (X removed): [T, T]. a0(T) misses on inputHash (was X). a1(T) matches inputHash of cached a1=T, but its
        // structure fingerprint differs (preceded by X vs by T), so the guard forces a re-execute rather than replaying
        // a DIFFERENT logical step's outcome. => 2 more executes, total 5.
        runner.run(ctx -> {
            ctx.agent(sub, "T");
            return ctx.agent(sub, "T");
        }, id);

        assertThat(executeCount.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("an identical re-run replays every step (deterministic resume)")
    void identicalReRunFullyReplays() {
        final StepResultCache cache = new InMemoryStepResultCache();
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).stepResultCache(cache).build();
        runners.add(runner);
        final RunId id = RunId.of("run:same");

        runner.run(ctx -> {
            ctx.agent(sub, "T");
            return ctx.agent(sub, "T");
        }, id);
        runner.run(ctx -> {
            ctx.agent(sub, "T");
            return ctx.agent(sub, "T");
        }, id);

        assertThat(executeCount.get()).isEqualTo(2); // run 2 fully replayed
    }

    @Test
    @DisplayName("With maxNestingDepth>=2 a nested parallel runs its thunks concurrently (no deadlock)")
    void nestedParallelIsConcurrent() {
        // The fake manager blocks each execute until all three inner leaves are in flight — if the inner fan-out were
        // serialized (depth cap), the latch would never release and the timeout would fire.
        final CountDownLatch latch = new CountDownLatch(3);
        final ConcurrentLinkedQueue<Boolean> allInFlight = new ConcurrentLinkedQueue<>();
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    latch.countDown();
                    allInFlight.add(latch.await(5, TimeUnit.SECONDS));
                    return success("ans:" + invocation.getArgument(2, String.class));
                });
        // perBatchMax(3) explicitly below maxConcurrency so forSharedPool leaves it untouched (a whole-pool
        // perBatchMax==maxConcurrency would be tamed to a fair share and throttle the inner fan-out).
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env)
                .concurrency(WorkflowConcurrencyConfig.builder().enabled(true).maxConcurrency(4).perBatchMax(3)
                        .maxNestingDepth(2).build())
                .build();
        runners.add(runner);

        assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> runner.run(ctx -> ctx.parallel(List.<Supplier<List<AgentStepResult>>>of(() -> ctx.parallel(
                        List.of(() -> ctx.agent(sub, "a"), () -> ctx.agent(sub, "b"), () -> ctx.agent(sub, "c")))))));

        assertThat(allInFlight).hasSize(3).allMatch(Boolean::booleanValue);
    }

    @Test
    @DisplayName("Load-bypass: a nonCacheable step never replays an entry a cacheable run seeded at its position")
    void nonCacheableBypassesLoadOfSeededEntry() {
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env)
                .stepResultCache(new InMemoryStepResultCache()).build();
        runners.add(runner);
        final RunId id = RunId.of("run:nc-load");

        // Seed position a0 with a CACHEABLE entry, then re-run the same goal at the same position flagged
        // nonCacheable(true). A save-bypass-only implementation would consult the cache here and find the seeded
        // entry; the load-bypass (with the flag-folded inputHash as second line of defense) must force a fresh
        // execution instead of a transcript-free replay that would drop the step's file writes.
        runner.run(ctx -> ctx.agent(sub, "g"), id);
        runner.run(ctx -> ctx.agent(AgentTask.builder().subagent(sub).goal("g").nonCacheable(true).build()), id);

        assertThat(executeCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Save-bypass: a nonCacheable step stores nothing — and never consults the cache at all")
    void nonCacheableBypassesSave() {
        final RecordingStepCache cache = new RecordingStepCache();
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).stepResultCache(cache).build();
        runners.add(runner);

        runner.run(ctx -> ctx.agent(AgentTask.builder().subagent(sub).goal("g").nonCacheable(true).build()),
                RunId.of("run:nc-save"));

        assertThat(executeCount.get()).isEqualTo(1);
        // Both halves of the whole-bypass, observed at the cache seam: nothing was saved for the step (a
        // load-bypass-only implementation would have memoized it) and no load was even attempted.
        assertThat(cache.savedKeys).isEmpty();
        assertThat(cache.loadedKeys).isEmpty();
    }

    @Test
    @DisplayName("An isolate=true step is never replayed — it re-executes and re-derives its worktree")
    void isolateNeverReplayedEndToEnd() {
        final RecordingFactory factory = new RecordingFactory();
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).worktreeFactory(factory)
                .stepResultCache(new InMemoryStepResultCache()).build();
        runners.add(runner);
        final RunId id = RunId.of("run:iso-resume");

        runner.run(ctx -> ctx.agent(AgentTask.builder().subagent(sub).goal("g").isolate(true).build()), id);
        runner.run(ctx -> ctx.agent(AgentTask.builder().subagent(sub).goal("g").isolate(true).build()), id);

        // isolate implies nonCacheable: even under an ACTIVE cache and the same run id, both runs execute and
        // both derive a scoped env — a replay would skip the subagent entirely, silently dropping its worktree writes.
        assertThat(executeCount.get()).isEqualTo(2);
        assertThat(factory.branchKeys).hasSize(2);
    }

    @Test
    @DisplayName("Flipping an upstream sibling's isolate flag diverges the downstream sibling's fingerprint")
    void structureGuardDivergesOnIsolateFlagFlip() {
        final RecordingFactory factory = new RecordingFactory();
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).worktreeFactory(factory)
                .stepResultCache(new InMemoryStepResultCache()).build();
        runners.add(runner);
        final RunId id = RunId.of("run:flip");

        // Run 1: A (isolate=true) then sibling B (cacheable). A bypasses the cache; B is memoized at a1 with a
        // fingerprint chaining A's inputHash.
        runner.run(ctx -> {
            ctx.agent(AgentTask.builder().subagent(sub).goal("A").isolate(true).build());
            return ctx.agent(sub, "B");
        }, id);
        // Run 2 (same id): A with isolate=false, otherwise identical; B unchanged. A misses (it was never cached) and
        // re-executes. B's own inputHash still matches its cached entry, but A's inputHash changed (the
        // isolate/nonCacheable flags are folded in), so B's left-context structure fingerprint diverged — the guard
        // must re-execute B instead of replaying it against a different upstream world.
        runner.run(ctx -> {
            ctx.agent(sub, "A");
            return ctx.agent(sub, "B");
        }, id);

        assertThat(executeCount.get()).isEqualTo(4); // a fingerprint-blind cache would replay B (count 3)
        assertThat(factory.branchKeys).hasSize(1); // only run 1's A derived a worktree
    }

    @Test
    @DisplayName("A foreground run-fatal abort trips the per-run signal, never the borrowed base env's signal")
    void runFatalTripsPerRunSignal() {
        final AtomicReference<CancellationSignal> perRunSignal = new AtomicReference<>();
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    executeCount.incrementAndGet();
                    perRunSignal
                            .set(invocation.getArgument(0, SubagentExecutionEnvironment.class).getCancellationSignal());
                    return success("ok");
                });
        // A REAL (trippable) base signal, so the negative assertion below is falsifiable — the shared env() helper's
        // default is NoopCancellationSignal, whose isCancelled() is constant false and can never catch a regression.
        final SubagentExecutionEnvironment realSignalBase = env.toBuilder()
                .cancellationSignal(new DefaultInterruptCoordinator().getSignal()).build();
        // No worktree factory wired, so the second step below is run-fatal (C30).
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, realSignalBase).build();
        runners.add(runner);

        // The first step captures the signal handed to in-flight leaves; the second aborts the run.
        assertThatThrownBy(() -> runner.run(ctx -> {
            ctx.agent(sub, "seed");
            return ctx.agent(AgentTask.builder().subagent(sub).goal("iso").isolate(true).build());
        })).isInstanceOf(WorkflowException.class);

        // The abort tripped the run's OWN signal (a per-run coordinator, not the borrowed base signal), so
        // orphaned in-flight branches stop at their next checkpoint while the app-wide signal stays untouched.
        assertThat(perRunSignal.get()).isNotNull().isNotSameAs(realSignalBase.getCancellationSignal());
        assertThat(perRunSignal.get().isCancelled()).isTrue();
        assertThat(realSignalBase.getCancellationSignal().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("A background run-fatal abort trips the per-run signal and settles FAILED, not KILLED")
    void backgroundRunFatalTripsSignalAndStaysFailed() {
        final AtomicReference<CancellationSignal> perRunSignal = new AtomicReference<>();
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    executeCount.incrementAndGet();
                    perRunSignal
                            .set(invocation.getArgument(0, SubagentExecutionEnvironment.class).getCancellationSignal());
                    return success("ok");
                });
        // No worktree factory wired, so the isolate step below is run-fatal (C30).
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, env).build();
        runners.add(runner);
        final RunId id = RunId.of("run:bg-fatal");

        final RunHandle<String> handle = runner.runInBackground(ctx -> {
            ctx.agent(sub, "seed");
            return ctx.agent(AgentTask.builder().subagent(sub).goal("iso").isolate(true).build()).text();
        }, id);

        assertThatThrownBy(() -> handle.await(Duration.ofSeconds(5))).isInstanceOf(ExecutionException.class);
        // The failure-trip also cancels the per-run signal (orphan cleanup), but the run failed on its own — the
        // store must record FAILED, never KILLED.
        awaitState(runner, id, WorkflowRunState.FAILED);
        assertThat(perRunSignal.get()).isNotNull();
        assertThat(perRunSignal.get().isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Cascade: tripping the base env's signal mid-run cancels the foreground run's per-run signal")
    void baseEnvTripCascadesToPerRunSignal() throws Exception {
        final DefaultInterruptCoordinator baseCoordinator = new DefaultInterruptCoordinator();
        final SubagentExecutionEnvironment cancellableBase = env.toBuilder()
                .cancellationSignal(baseCoordinator.getSignal()).build();
        final CountDownLatch leafStarted = new CountDownLatch(1);
        final CountDownLatch perRunTripped = new CountDownLatch(1);
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    final SubagentExecutionEnvironment perRun = invocation.getArgument(0,
                            SubagentExecutionEnvironment.class);
                    perRun.getCancellationSignal().onCancel(perRunTripped::countDown);
                    leafStarted.countDown();
                    // Block until the app-wide trip cascades into THIS run's signal (cooperative stop), then return.
                    if (!perRunTripped.await(5, TimeUnit.SECONDS)) {
                        return SubagentExecutionResult.emptyFailure("cascade never arrived", Instant.now());
                    }
                    return success("cascaded");
                });
        final DefaultWorkflowRunner runner = DefaultWorkflowRunner.builder(manager, cancellableBase).build();
        runners.add(runner);

        final CompletableFuture<String> result = CompletableFuture
                .supplyAsync(() -> runner.run(ctx -> ctx.agent(sub, "g").text()));
        assertThat(leafStarted.await(5, TimeUnit.SECONDS)).isTrue(); // the leaf is in flight, the cascade is wired
        baseCoordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED); // an app-wide stop arrives mid-run

        // The leaf only returns "cascaded" once the run's own signal trips — proving the parent cascade reached it.
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("cascaded");
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** Polls {@code runner}'s run store until {@code id} reaches {@code expected}, failing if it does not in time. */
    private static void awaitState(DefaultWorkflowRunner runner, RunId id, WorkflowRunState expected) {
        final long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (runner.status(id).map(WorkflowRun::getState).filter(expected::equals).isPresent()) {
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
                + runner.status(id).map(WorkflowRun::getState).orElse(null) + ")");
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

    /** Records the branch keys it is asked to derive, and returns an env sharing all borrowed collaborators. */
    private static final class RecordingFactory implements WorktreeEnvironmentFactory {
        private final ConcurrentLinkedQueue<String> branchKeys = new ConcurrentLinkedQueue<>();

        @Override
        public SubagentExecutionEnvironment derive(SubagentExecutionEnvironment baseEnv, String branchKey) {
            branchKeys.add(branchKey);
            return baseEnv.toBuilder().build();
        }
    }

    /** Delegating cache recording every load/save key, so the whole-bypass is assertable at the cache seam. */
    private static final class RecordingStepCache implements StepResultCache {
        private final StepResultCache delegate = new InMemoryStepResultCache();
        private final ConcurrentLinkedQueue<StepKey> loadedKeys = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<StepKey> savedKeys = new ConcurrentLinkedQueue<>();

        @Override
        public Optional<StepOutcome> load(StepKey key) {
            loadedKeys.add(key);
            return delegate.load(key);
        }

        @Override
        public void save(StepKey key, StepOutcome outcome) {
            savedKeys.add(key);
            delegate.save(key, outcome);
        }

        @Override
        public void evict(StepKey key) {
            delegate.evict(key);
        }
    }
}
