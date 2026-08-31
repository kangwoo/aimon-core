package at.aimon.core.hook.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookExecutionPolicy.ExecutionMode;
import at.aimon.core.hook.execution.HookExecutionPolicy.TimeoutBehavior;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

class DefaultHookExecutorTest {

    private static PreToolContext preCtx() {
        final HookRegistry registry = new DefaultHookRegistry();
        final Environment env = Environment.createDefault();
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(registry)
                .environment(env).toolUse(ToolUse.of("id", "Bash", Map.of("command", "secret"))).iterationCount(1)
                .build();
    }

    private static PostToolContext postCtx() {
        final HookRegistry registry = new DefaultHookRegistry();
        final Environment env = Environment.createDefault();
        final ToolUse tu = ToolUse.of("id", "Bash", Map.of("command", "echo"));
        return PostToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(registry).environment(env).toolUse(tu)
                .toolUseResult(ToolUseResult.success("id", "raw-output")).iterationCount(1).build();
    }

    @Test
    void preToolUpdatedInputIsThreadedToNextHook() {
        final AtomicReference<ToolInput> seenBySecond = new AtomicReference<>();
        final PreToolHook redact = ctx -> HookResult.withUpdatedInput(ToolInput.of(Map.of("command", "***")));
        final PreToolHook observer = ctx -> {
            seenBySecond.set(ctx.currentInput());
            return HookResult.success();
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final List<HookResult> results = executor.execute(List.of(redact, observer), preCtx(),
                HookExecutionPolicy.continueOnExceptionButStopOnBlocked());

        assertThat(results).hasSize(2);
        assertThat(seenBySecond.get().toMap()).isEqualTo(Map.of("command", "***"));

        // The cumulative updated input is reflected on the last result.
        assertThat(results.get(results.size() - 1).getUpdatedInput()).isPresent();
        assertThat(results.get(results.size() - 1).getUpdatedInput().get().toMap()).isEqualTo(Map.of("command", "***"));
    }

    @Test
    void preToolBlockShortCircuitsAndPreservesPriorAccumulation() {
        final PreToolHook redact = ctx -> HookResult.withUpdatedInput(ToolInput.of(Map.of("command", "***")));
        final PreToolHook denier = ctx -> HookResult.block("nope");
        final AtomicReference<Boolean> thirdRan = new AtomicReference<>(false);
        final PreToolHook never = ctx -> {
            thirdRan.set(true);
            return HookResult.success();
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final List<HookResult> results = executor.execute(List.of(redact, denier, never), preCtx(),
                HookExecutionPolicy.continueOnExceptionButStopOnBlocked());

        assertThat(results).hasSize(2);
        assertThat(thirdRan.get()).isFalse();

        final HookResult last = results.get(results.size() - 1);
        assertThat(last.isBlocked()).isTrue();
        assertThat(last.getUpdatedInput()).isPresent();
        assertThat(last.getUpdatedInput().get().toMap()).isEqualTo(Map.of("command", "***"));
    }

    @Test
    void postToolUpdatedOutputIsThreadedToNextHook() {
        final AtomicReference<ToolResult> seenBySecond = new AtomicReference<>();
        final PostToolHook mask = ctx -> HookResult.withUpdatedOutput(ToolResult.success("[masked]"));
        final PostToolHook observer = ctx -> {
            seenBySecond.set(ctx.currentOutput());
            return HookResult.success();
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final List<HookResult> results = executor.execute(List.of(mask, observer), postCtx(),
                HookExecutionPolicy.continueOnExceptionAndNeverStop());

        assertThat(results).hasSize(2);
        assertThat(seenBySecond.get().getContent()).isEqualTo("[masked]");

        final HookResult last = results.get(results.size() - 1);
        assertThat(last.getUpdatedOutput()).isPresent();
        assertThat(last.getUpdatedOutput().get().getContent()).isEqualTo("[masked]");
    }

    @Test
    void exceptionMappedByPolicyAndDoesNotInterruptChain() {
        final PreToolHook boom = ctx -> {
            throw new RuntimeException("boom");
        };
        final PreToolHook follower = ctx -> HookResult.success();

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final List<HookResult> results = executor.execute(List.of(boom, follower), preCtx(),
                HookExecutionPolicy.continueOnExceptionAndNeverStop());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).isBlocked()).isFalse();
    }

    @Test
    void emptyHookListReturnsEmptyResults() {
        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final List<HookResult> results = executor.execute(List.<PreToolHook>of(), preCtx(),
                HookExecutionPolicy.continueOnExceptionButStopOnBlocked());
        assertThat(results).isEmpty();
    }

    @Test
    void slowHookFailOpenTimesOutToSuccessAndChainContinues() {
        final PreToolHook slow = ctx -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HookResult.success();
        };
        final AtomicReference<Boolean> followerRan = new AtomicReference<>(false);
        final PreToolHook follower = ctx -> {
            followerRan.set(true);
            return HookResult.success();
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionButStopOnBlocked()
                .withTimeout(Duration.ofMillis(50)).withTimeoutBehavior(TimeoutBehavior.FAIL_OPEN);

        final List<HookResult> results = executor.execute(List.of(slow, follower), preCtx(), policy);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).isBlocked()).isFalse();
        assertThat(followerRan.get()).isTrue();
    }

    @Test
    void slowHookFailClosedTimesOutToBlockedAndStops() {
        final PreToolHook slow = ctx -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HookResult.success();
        };
        final AtomicReference<Boolean> followerRan = new AtomicReference<>(false);
        final PreToolHook follower = ctx -> {
            followerRan.set(true);
            return HookResult.success();
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionButStopOnBlocked()
                .withTimeout(Duration.ofMillis(50)).withTimeoutBehavior(TimeoutBehavior.FAIL_CLOSED);

        final List<HookResult> results = executor.execute(List.of(slow, follower), preCtx(), policy);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isBlocked()).isTrue();
        assertThat(results.get(0).getFeedback()).isPresent();
        assertThat(results.get(0).getFeedback().get()).contains("timed out");
        assertThat(followerRan.get()).isFalse();
    }

    /** A preTool hook that sleeps and declares a budget of its own, the way a {@code timeoutMs}-carrying one does. */
    private static PreToolHook slowHookDeclaring(long sleepMs, Duration budget) {
        return new PreToolHook() {
            @Override
            public HookResult execute(PreToolContext context) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return HookResult.withFeedback("interrupted");
                }
                return HookResult.withFeedback("ran to completion");
            }

            @Override
            public java.util.Optional<Duration> getExecutionBudget() {
                return java.util.Optional.of(budget);
            }
        };
    }

    @Test
    void aHookDeclaringALongerBudgetIsNotCutOffByThePolicyTimeout() {
        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionButStopOnBlocked()
                .withTimeout(Duration.ofMillis(50)).withTimeoutBehavior(TimeoutBehavior.FAIL_CLOSED);

        final List<HookResult> results = executor.execute(List.of(slowHookDeclaring(200L, Duration.ofMillis(400))),
                preCtx(), policy);

        // Under the bare 50ms policy timeout this would have been a FAIL_CLOSED block; the declared budget widens the
        // net so the hook's own result comes back instead.
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isBlocked()).isFalse();
        assertThat(results.get(0).getFeedback()).contains("ran to completion");
    }

    @Test
    void declaredBudgetIsHonouredPerHookInParallelMode() {
        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop()
                .withTimeout(Duration.ofMillis(50)).withTimeoutBehavior(TimeoutBehavior.FAIL_CLOSED)
                .withExecutionMode(ExecutionMode.PARALLEL);

        // Both outlive the 50ms policy timeout; only the one that declared a budget is allowed to.
        final PreToolHook undeclared = ctx -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HookResult.withFeedback("ran to completion");
        };
        final List<HookResult> results = executor
                .execute(List.of(slowHookDeclaring(200L, Duration.ofMillis(400)), undeclared), preCtx(), policy);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).isBlocked()).isFalse();
        assertThat(results.get(0).getFeedback()).contains("ran to completion");
        assertThat(results.get(1).isBlocked()).isTrue();
        assertThat(results.get(1).getFeedback().orElseThrow()).contains("timed out");
    }

    // ---- Phase 3 (WI-3.1.b) parallel mode ----------------------------------------------------

    @Test
    void parallelModeRunsAllHooksAndReturnsAllResults() {
        final java.util.concurrent.atomic.AtomicInteger ranCount = new java.util.concurrent.atomic.AtomicInteger();
        final PreToolHook a = ctx -> {
            ranCount.incrementAndGet();
            return HookResult.allow();
        };
        final PreToolHook b = ctx -> {
            ranCount.incrementAndGet();
            return HookResult.deny("nope");
        };
        final PreToolHook c = ctx -> {
            ranCount.incrementAndGet();
            return HookResult.withFeedback("hi");
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionButStopOnBlocked()
                .withExecutionMode(ExecutionMode.PARALLEL);

        final List<HookResult> results = executor.execute(List.of(a, b, c), preCtx(), policy);

        // Even with stopOnBlocked, parallel mode does not short-circuit.
        assertThat(results).hasSize(3);
        assertThat(ranCount.get()).isEqualTo(3);
    }

    @Test
    void parallelModeMergesToDenyWhenAnyHookDenies() {
        final PreToolHook a = ctx -> HookResult.allow();
        final PreToolHook b = ctx -> HookResult.deny("nope");

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop()
                .withExecutionMode(ExecutionMode.PARALLEL);

        final List<HookResult> results = executor.execute(List.of(a, b), preCtx(), policy);
        final HookResult merged = HookResult.merge(results);

        assertThat(merged.getDecision()).isEqualTo(Decision.DENY);
        assertThat(merged.getFeedback()).isPresent();
        assertThat(merged.getFeedback().get()).contains("nope");
    }

    @Test
    void parallelModeDoesNotThreadUpdatedInputBetweenHooks() {
        final AtomicReference<ToolInput> seenByB = new AtomicReference<>();
        final PreToolHook a = ctx -> HookResult.withUpdatedInput(ToolInput.of(Map.of("command", "***")));
        final PreToolHook b = ctx -> {
            seenByB.set(ctx.currentInput());
            return HookResult.allow();
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop()
                .withExecutionMode(ExecutionMode.PARALLEL);

        executor.execute(List.of(a, b), preCtx(), policy);

        // b sees the original input, not a's redaction (parallel hooks observe the same starting state).
        assertThat(seenByB.get().toMap()).isEqualTo(Map.of("command", "secret"));
    }

    @Test
    void parallelModeReturnsResultsInInputOrder() {
        final PreToolHook a = ctx -> HookResult.withFeedback("a");
        final PreToolHook b = ctx -> HookResult.withFeedback("b");
        final PreToolHook c = ctx -> HookResult.withFeedback("c");

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop()
                .withExecutionMode(ExecutionMode.PARALLEL);

        final List<HookResult> results = executor.execute(List.of(a, b, c), preCtx(), policy);
        assertThat(results).extracting(r -> r.getFeedback().orElseThrow()).containsExactly("a", "b", "c");
    }

    @Test
    void parallelModeEmptyHookListReturnsEmpty() {
        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final List<HookResult> results = executor.execute(List.<PreToolHook>of(), preCtx(),
                HookExecutionPolicy.continueOnExceptionAndNeverStop().withExecutionMode(ExecutionMode.PARALLEL));
        assertThat(results).isEmpty();
    }

    // ---- Phase 3 (WI-3.1.c) dedup -----------------------------------------------------------

    @Test
    void dedupKeyExtractorDropsDuplicateHooksKeepingFirst() {
        final java.util.concurrent.atomic.AtomicInteger countA = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger countB = new java.util.concurrent.atomic.AtomicInteger();
        final PreToolHook firstAuditor = ctx -> {
            countA.incrementAndGet();
            return HookResult.allow();
        };
        final PreToolHook secondAuditor = ctx -> {
            countB.incrementAndGet();
            return HookResult.allow();
        };
        // Two hooks share the same dedup key "audit" -> only the first should run.
        final HookExecutionPolicy.DedupKeyExtractor extractor = h -> {
            if (h == firstAuditor || h == secondAuditor) {
                return "audit";
            }
            return null;
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop()
                .withDedupKeyExtractor(extractor);

        final List<HookResult> results = executor.execute(List.of(firstAuditor, secondAuditor), preCtx(), policy);

        assertThat(results).hasSize(1);
        assertThat(countA.get()).isEqualTo(1);
        assertThat(countB.get()).isZero();
    }

    @Test
    void dedupExtractorReturningNullKeepsAllHooks() {
        final java.util.concurrent.atomic.AtomicInteger ranA = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger ranB = new java.util.concurrent.atomic.AtomicInteger();
        final PreToolHook a = ctx -> {
            ranA.incrementAndGet();
            return HookResult.allow();
        };
        final PreToolHook b = ctx -> {
            ranB.incrementAndGet();
            return HookResult.allow();
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop()
                .withDedupKeyExtractor(h -> null);

        executor.execute(List.of(a, b), preCtx(), policy);

        assertThat(ranA.get()).isEqualTo(1);
        assertThat(ranB.get()).isEqualTo(1);
    }

    // ---- A-4: a bounded pool narrower than the batch must not deadlock -----------------------

    /**
     * Regression guard for the pool-discipline fix. An earlier design submitted the timeout wrapper <i>and</i> the
     * hook body to {@code hookExecutor}, so a pool with fewer threads than the batch had hooks deadlocked: the wrapper
     * held the only worker while waiting for a body that could never be scheduled. Deadlines are now awaited on the
     * calling thread — exactly one pool task per hook — so a single-threaded pool merely serialises the batch.
     *
     * <p>
     * The per-hook budget runs from submission, so queued hooks burn it while waiting; the policy timeout is generous
     * and the hooks are trivial on purpose. This asserts completion and ordering, not timing. The JUnit
     * {@code @Timeout} makes a regression fail fast instead of hanging the suite.
     */
    @Test
    @Timeout(20)
    void parallelModeWithAPoolNarrowerThanTheBatchStillCompletes() {
        final ExecutorService singleThread = Executors.newFixedThreadPool(1);
        try {
            final DefaultHookExecutor executor = new DefaultHookExecutor(singleThread);
            final HookExecutionPolicy policy = HookExecutionPolicy.continueOnExceptionAndNeverStop()
                    .withTimeout(Duration.ofSeconds(30)).withExecutionMode(ExecutionMode.PARALLEL);

            final PreToolHook a = ctx -> HookResult.withFeedback("a");
            final PreToolHook b = ctx -> HookResult.withFeedback("b");
            final PreToolHook c = ctx -> HookResult.withFeedback("c");
            final PreToolHook d = ctx -> HookResult.withFeedback("d");

            final List<HookResult> results = executor.execute(List.of(a, b, c, d), preCtx(), policy);

            assertThat(results).hasSize(4);
            assertThat(results).extracting(r -> r.getFeedback().orElseThrow()).containsExactly("a", "b", "c", "d");
        } finally {
            singleThread.shutdownNow();
        }
    }

    // ---- A-2: applyAccumulatedToLast must not drop the last hook's rewake specs ---------------

    /**
     * Regression guard for rewake-spec preservation. When a PreTool chain accumulates an {@code updatedInput}, the
     * executor rebuilds the last result to materialise it. Rewake specs are orthogonal to that accumulation, so the
     * rebuild must copy them over — otherwise {@code DefaultHookExecutionManager#scheduleRewakes}, which scans the
     * results only after this executor returns, would never see them and the rewake would silently never fire.
     */
    @Test
    void sequentialModePreservesRewakeSpecsOnTheRebuiltLastResult() {
        final RewakeSpec spec = RewakeSpec.builder().trigger(new RewakeTriggerDelay(Duration.ofMinutes(5)))
                .reason("re-check the deployment once it settles").build();

        final PreToolHook redact = ctx -> HookResult.withUpdatedInput(ToolInput.of(Map.of("command", "***")));
        final PreToolHook rewaker = ctx -> HookResult.builder()
                .updatedInput(ToolInput.of(Map.of("command", "***final***"))).rewakeSpec(spec).build();

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        final List<HookResult> results = executor.execute(List.of(redact, rewaker), preCtx(),
                HookExecutionPolicy.continueOnExceptionButStopOnBlocked());

        assertThat(results).hasSize(2);

        final HookResult last = results.get(results.size() - 1);
        assertThat(last.getUpdatedInput()).isPresent();
        assertThat(last.getUpdatedInput().get().toMap()).isEqualTo(Map.of("command", "***final***"));
        assertThat(last.getRewakeSpecs()).containsExactly(spec);
    }

    // ---- B-4: the creator releases the pool it created ---------------------------------------

    /**
     * Fires one hook to force the pool into existence, then asserts that {@code close()} is what ended its worker.
     *
     * <p>
     * The obvious form of this test — count threads named {@code hook-executor} before and after — passes
     * <i>vacuously</i>: a cached pool creates no thread until the first submit, so a test that never fires a hook
     * compares {@code 0} against {@code 0} and would keep passing if {@code close()} did nothing at all. It also cannot
     * tell this executor's workers from those of every other executor in the same JVM, since they all share that one
     * name. So the worker is captured by identity from inside the hook body, and the assertion is that <i>this</i>
     * thread was alive before the close and dies after it.
     *
     * <p>
     * The wait matters as much as the assertion: an idle cached worker retires by itself, but only after 60s. Anything
     * that dies inside {@link #POOL_STOP_TIMEOUT_MS} was killed by {@code shutdownNow}, not by that timeout.
     */
    @Test
    @Timeout(20)
    void closeShutsDownThePoolItCreatedItself() throws Exception {
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final PreToolHook capture = ctx -> {
            worker.set(Thread.currentThread());
            return HookResult.success();
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        executor.execute(List.of(capture), preCtx(), HookExecutionPolicy.continueOnExceptionButStopOnBlocked());

        final Thread pooled = worker.get();
        assertThat(pooled).as("the hook body must have run on a pool thread, not the caller's").isNotNull()
                .isNotSameAs(Thread.currentThread());
        assertThat(pooled.getName()).isEqualTo("hook-executor");
        assertThat(pooled.isAlive()).as("a cached worker idles for 60s, so it must still be alive here").isTrue();

        executor.close();

        assertThat(awaitDeath(pooled)).as("close() must retire the worker rather than leave it to the 60s reap")
                .isTrue();
    }

    /**
     * An injected pool is borrowed, so {@code close()} must leave it running — its creator may still be submitting to
     * it from somewhere this executor knows nothing about.
     */
    @Test
    @Timeout(20)
    void closeLeavesAnInjectedPoolRunning() throws Exception {
        final ExecutorService injected = Executors.newFixedThreadPool(1);
        try {
            final DefaultHookExecutor executor = new DefaultHookExecutor(injected);
            executor.execute(List.of(ctx -> HookResult.success()), preCtx(),
                    HookExecutionPolicy.continueOnExceptionButStopOnBlocked());

            executor.close();

            assertThat(injected.isShutdown()).isFalse();
            assertThat(injected.submit(() -> "still serving").get()).isEqualTo("still serving");
        } finally {
            injected.shutdownNow();
        }
    }

    /**
     * Teardown may run twice (an explicit close followed by a registry sweep), and a hook that arrives after it must
     * come back as a policy-mapped result rather than an escaping {@code RejectedExecutionException} — the same outcome
     * a saturated pool already produces.
     */
    @Test
    @Timeout(20)
    void closeIsIdempotentAndLaterHooksAreRejectedThroughThePolicy() throws Exception {
        final DefaultHookExecutor executor = new DefaultHookExecutor();
        executor.execute(List.of(ctx -> HookResult.success()), preCtx(),
                HookExecutionPolicy.continueOnExceptionButStopOnBlocked());

        executor.close();
        executor.close();

        final List<HookResult> afterClose = executor.execute(List.of(ctx -> HookResult.success()), preCtx(),
                HookExecutionPolicy.continueOnExceptionAndNeverStop());
        assertThat(afterClose).hasSize(1);
        assertThat(afterClose.get(0).getFlowControl()).isEqualTo(FlowControl.CONTINUE);
    }

    /** Upper bound on how long a {@code shutdownNow}-ed worker may take to die — far below the 60s idle reap. */
    private static final long POOL_STOP_TIMEOUT_MS = 5_000L;

    private static boolean awaitDeath(Thread thread) throws InterruptedException {
        thread.join(POOL_STOP_TIMEOUT_MS);
        return !thread.isAlive();
    }

    // ---- interrupted waits (interrupt design §8) --------------------------------------------

    /** A hook that blocks long enough that its future is still pending when the caller stops waiting. */
    private static PreToolHook blockingHook() {
        return ctx -> {
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HookResult.success();
        };
    }

    /**
     * The security case from interrupt design §8: on a thread whose interrupt flag is already set,
     * {@code Future#get} throws without waiting, so under the fail-open exception mapper every hook in the chain
     * reported SUCCESS without having run — an interrupt was a way past a PreTool BLOCK. An interrupted wait must
     * come back BLOCKED instead, and the caller's cancellation must still be on the thread afterwards.
     */
    @Test
    @Timeout(20)
    void anInterruptedWaitIsBlockedRatherThanFailedOpen() {
        final AtomicReference<Boolean> followerRan = new AtomicReference<>(false);
        final PreToolHook follower = ctx -> {
            followerRan.set(true);
            return HookResult.success();
        };

        final DefaultHookExecutor executor = new DefaultHookExecutor();
        try {
            Thread.currentThread().interrupt();
            final List<HookResult> results = executor.execute(List.of(blockingHook(), follower), preCtx(),
                    HookExecutionPolicy.continueOnExceptionButStopOnBlocked());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isBlocked()).as("the fail-open mapper must not answer for an interrupt").isTrue();
            assertThat(results.get(0).getFeedback().orElseThrow()).contains("interrupted");
            assertThat(followerRan.get()).isFalse();
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the caller's cancellation has no other channel to ride on").isTrue();
        } finally {
            Thread.interrupted();
            executor.close();
        }
    }

    /**
     * {@code stopOnBlocked} does not decide whether a BLOCKED result is enforced — OnStart runs under the never-stop
     * policy and its caller still aborts the turn on one. So the interrupt answer must not depend on the policy
     * either: the chain runs to the end, and every hook that was never awaited says BLOCKED rather than a SUCCESS
     * nobody earned.
     */
    @Test
    @Timeout(20)
    void aNeverStopPolicyStillReportsInterruptedWaitsAsBlocked() {
        final DefaultHookExecutor executor = new DefaultHookExecutor();
        try {
            Thread.currentThread().interrupt();
            final List<HookResult> results = executor.execute(List.of(blockingHook(), blockingHook()), preCtx(),
                    HookExecutionPolicy.continueOnExceptionAndNeverStop());

            assertThat(results).hasSize(2);
            assertThat(results).allSatisfy(result -> assertThat(result.isBlocked()).isTrue());
        } finally {
            Thread.interrupted();
            executor.close();
        }
    }

    /**
     * Only a genuinely pending wait becomes BLOCKED. {@code FutureTask#get} hands back an already-finished value
     * without consulting the interrupt flag, so a hook that ran to completion keeps its verdict — the interrupt
     * bounds waiting, it does not throw away work that is done.
     */
    @Test
    @Timeout(20)
    void anAlreadyFinishedHookKeepsItsVerdictOnAnInterruptedThread() {
        final ExecutorService inline = new InlineExecutorService();
        final DefaultHookExecutor executor = new DefaultHookExecutor(inline);
        try {
            Thread.currentThread().interrupt();
            final List<HookResult> results = executor.execute(
                    List.of((PreToolHook) ctx -> HookResult.withFeedback("ran to completion")), preCtx(),
                    HookExecutionPolicy.continueOnExceptionButStopOnBlocked());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isBlocked()).isFalse();
            assertThat(results.get(0).getFeedback()).contains("ran to completion");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            executor.close();
            inline.shutdownNow();
        }
    }

    /**
     * Runs every submitted task on the calling thread, so a hook's future is already complete by the time the
     * executor awaits it. Lets the "finished work survives an interrupt" case be asserted without racing a pool.
     */
    private static final class InlineExecutorService extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
