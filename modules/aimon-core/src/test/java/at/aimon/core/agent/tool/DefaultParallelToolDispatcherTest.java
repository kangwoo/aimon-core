package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

@DisplayName("DefaultParallelToolDispatcher Tests")
class DefaultParallelToolDispatcherTest {

    private static final Consumer<ToolUse> NO_STARTED = null;
    private static final BiConsumer<ToolUse, ToolUseResult> NO_COMPLETED = null;

    // ---- helpers ------------------------------------------------------------

    private static Tool tool(String name, ConcurrencyBehavior behavior, InterruptBehavior interrupt) {
        return new AbstractTool(name, name + " description", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ok");
            }

            @Override
            public ConcurrencyBehavior getConcurrencyBehavior() {
                return behavior;
            }

            @Override
            public InterruptBehavior getInterruptBehavior() {
                return interrupt;
            }
        };
    }

    private static ToolUse use(String id, String name) {
        return ToolUse.of(id, name, Map.of());
    }

    /** Runner that echoes the tool-use id into a success result. */
    private static Function<ToolUse, ToolUseResult> echoRunner() {
        return tu -> ToolUseResult.success(tu.getId(), "ran:" + tu.getName());
    }

    // ---- gate ---------------------------------------------------------------

    @Nested
    @DisplayName("Safety gate (shouldParallelize)")
    class Gate {

        private final ToolConcurrencyConfig enabled = ToolConcurrencyConfig.enabled(4);

        private DefaultToolRegistry registryWith(Tool... tools) {
            final DefaultToolRegistry registry = new DefaultToolRegistry();
            for (Tool t : tools) {
                registry.register(t);
            }
            return registry;
        }

        @Test
        @DisplayName("disabled config never parallelises")
        void disabledConfig() {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("B", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            assertThat(DefaultParallelToolDispatcher.shouldParallelize(List.of(use("1", "A"), use("2", "B")), registry,
                    ToolConcurrencyConfig.disabled())).isFalse();
        }

        @Test
        @DisplayName("single tool batch never parallelises")
        void singleTool() {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            assertThat(DefaultParallelToolDispatcher.shouldParallelize(List.of(use("1", "A")), registry, enabled))
                    .isFalse();
        }

        @Test
        @DisplayName("all CONCURRENT_SAFE + parallelisable interrupt parallelises")
        void allSafe() {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.NON_INTERRUPTIBLE),
                    tool("B", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            assertThat(DefaultParallelToolDispatcher.shouldParallelize(List.of(use("1", "A"), use("2", "B")), registry,
                    enabled)).isTrue();
        }

        @Test
        @DisplayName("a single SEQUENTIAL tool forces sequential")
        void oneSequential() {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("B", ConcurrencyBehavior.SEQUENTIAL, InterruptBehavior.NON_INTERRUPTIBLE));
            assertThat(DefaultParallelToolDispatcher.shouldParallelize(List.of(use("1", "A"), use("2", "B")), registry,
                    enabled)).isFalse();
        }

        @Test
        @DisplayName("unregistered (hallucinated) tool name forces sequential")
        void unregistered() {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            assertThat(DefaultParallelToolDispatcher.shouldParallelize(List.of(use("1", "A"), use("2", "Ghost")),
                    registry, enabled)).isFalse();
        }

        @Test
        @DisplayName("THREAD_INTERRUPT tool excluded even when CONCURRENT_SAFE")
        void threadInterruptExcluded() {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("B", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.THREAD_INTERRUPT));
            assertThat(DefaultParallelToolDispatcher.shouldParallelize(List.of(use("1", "A"), use("2", "B")), registry,
                    enabled)).isFalse();
        }

        @Test
        @DisplayName("EXTERNALLY_TERMINATED tool excluded")
        void externallyTerminatedExcluded() {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("B", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.EXTERNALLY_TERMINATED));
            assertThat(DefaultParallelToolDispatcher.shouldParallelize(List.of(use("1", "A"), use("2", "B")), registry,
                    enabled)).isFalse();
        }

        @Test
        @DisplayName("duplicate tool names in a batch still parallelize when CONCURRENT_SAFE")
        void duplicateToolNames() {
            final DefaultToolRegistry registry = registryWith(
                    tool("Read", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.NON_INTERRUPTIBLE));
            assertThat(DefaultParallelToolDispatcher.shouldParallelize(List.of(use("1", "Read"), use("2", "Read")),
                    registry, enabled)).isTrue();
        }

        @Test
        @DisplayName("null registry forces sequential")
        void nullRegistry() {
            assertThat(DefaultParallelToolDispatcher.shouldParallelize(List.of(use("1", "A"), use("2", "B")), null,
                    enabled)).isFalse();
        }

        @Test
        @DisplayName("default Tool behavior is SEQUENTIAL (no opt-in)")
        void defaultPolicyIsSequential() {
            final Tool plain = new AbstractTool("Plain", "plain", Map.of("type", "object")) {
                @Override
                public ToolResult execute(ToolInput input, ToolContext context) {
                    return ToolResult.success("ok");
                }
            };
            assertThat(plain.getConcurrencyBehavior()).isEqualTo(ConcurrencyBehavior.SEQUENTIAL);
        }
    }

    // ---- ordering & sequential equivalence ----------------------------------

    @Nested
    @DisplayName("Ordering")
    class Ordering {

        @Test
        @DisplayName("sequential path preserves order and invokes callbacks in order")
        void sequentialOrder() {
            final DefaultParallelToolDispatcher dispatcher = DefaultParallelToolDispatcher.sequential();
            final List<String> started = new ArrayList<>();
            final List<String> completed = new ArrayList<>();
            final List<ToolUse> uses = List.of(use("1", "A"), use("2", "B"), use("3", "C"));

            final List<ToolUseResult> results = dispatcher.dispatch(uses, null, echoRunner(),
                    tu -> started.add(tu.getId()), (tu, r) -> completed.add(tu.getId()));

            assertThat(results).extracting(ToolUseResult::getToolUseId).containsExactly("1", "2", "3");
            assertThat(started).containsExactly("1", "2", "3");
            assertThat(completed).containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("parallel path returns results in input order despite reversed completion order")
        void parallelPreservesInputOrder() throws Exception {
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(8))) {
                final DefaultToolRegistry registry = new DefaultToolRegistry();
                final int n = 6;
                final List<ToolUse> uses = IntStream.range(0, n).mapToObj(i -> use(String.valueOf(i), "T" + i))
                        .collect(Collectors.toList());
                uses.forEach(u -> registry.register(
                        tool(u.getName(), ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE)));

                // Earlier-index tools sleep longer so they complete LAST — proving order comes from index, not timing.
                final Function<ToolUse, ToolUseResult> runner = tu -> {
                    final int idx = Integer.parseInt(tu.getId());
                    try {
                        Thread.sleep((n - idx) * 15L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolUseResult.success(tu.getId(), "done");
                };

                final List<ToolUseResult> results = dispatcher.dispatch(uses, registry, runner, NO_STARTED,
                        NO_COMPLETED);

                assertThat(results).extracting(ToolUseResult::getToolUseId).containsExactly("0", "1", "2", "3", "4",
                        "5");
            }
        }

        @Test
        @DisplayName("start events keep input order on the parallel path")
        void parallelStartOrder() throws Exception {
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(4))) {
                final DefaultToolRegistry registry = new DefaultToolRegistry();
                final List<ToolUse> uses = List.of(use("0", "A"), use("1", "B"), use("2", "C"));
                uses.forEach(u -> registry.register(
                        tool(u.getName(), ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE)));
                final List<String> started = new CopyOnWriteArrayList<>();

                dispatcher.dispatch(uses, registry, echoRunner(), tu -> started.add(tu.getId()), NO_COMPLETED);

                assertThat(started).containsExactly("0", "1", "2");
            }
        }
    }

    // ---- real concurrency ---------------------------------------------------

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("eligible batch actually runs in parallel (barrier rendezvous)")
        void runsConcurrently() throws Exception {
            final int n = 3;
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(n))) {
                final DefaultToolRegistry registry = new DefaultToolRegistry();
                final List<ToolUse> uses = IntStream.range(0, n).mapToObj(i -> use(String.valueOf(i), "T" + i))
                        .collect(Collectors.toList());
                uses.forEach(u -> registry.register(
                        tool(u.getName(), ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE)));

                // If the runners executed sequentially the first await() would never be released and the runner would
                // time out, yielding an error result. All-success therefore proves genuine concurrency.
                final CyclicBarrier barrier = new CyclicBarrier(n);
                final Function<ToolUse, ToolUseResult> runner = tu -> {
                    try {
                        barrier.await(3, TimeUnit.SECONDS);
                        return ToolUseResult.success(tu.getId(), "rendezvous");
                    } catch (Exception e) {
                        return ToolUseResult.error(tu.getId(), "no-rendezvous: " + e.getClass().getSimpleName());
                    }
                };

                final List<ToolUseResult> results = dispatcher.dispatch(uses, registry, runner, NO_STARTED,
                        NO_COMPLETED);

                assertThat(results).hasSize(n);
                assertThat(results).allMatch(ToolUseResult::isSuccess);
            }
        }

        @Test
        @DisplayName("onCompleted fires in completion order while results stay input-ordered")
        void completionOrderDiffersFromResultOrder() throws Exception {
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(8))) {
                final DefaultToolRegistry registry = new DefaultToolRegistry();
                final List<ToolUse> uses = IntStream.range(0, 4).mapToObj(i -> use(String.valueOf(i), "T" + i))
                        .collect(Collectors.toList());
                uses.forEach(u -> registry.register(
                        tool(u.getName(), ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE)));
                final List<String> completed = new CopyOnWriteArrayList<>();

                // Earlier indices sleep longer, so index 3 (shortest) completes before index 0 (longest). Index-based
                // reassembly must still return [0,1,2,3]; completion order must NOT match it.
                final Function<ToolUse, ToolUseResult> runner = tu -> {
                    final int idx = Integer.parseInt(tu.getId());
                    try {
                        Thread.sleep((4 - idx) * 40L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolUseResult.success(tu.getId(), "done");
                };

                final List<ToolUseResult> results = dispatcher.dispatch(uses, registry, runner, NO_STARTED,
                        (tu, r) -> completed.add(tu.getId()));

                assertThat(results).extracting(ToolUseResult::getToolUseId).containsExactly("0", "1", "2", "3");
                assertThat(completed).hasSize(4);
                // Robust, timing-tolerant invariant: the shortest-sleeping tool completes before the longest-sleeping.
                assertThat(completed.indexOf("3")).isLessThan(completed.indexOf("0"));
            }
        }
    }

    // ---- per-batch cap (two-tier bound) -------------------------------------

    @Nested
    @DisplayName("Per-batch cap (two-tier bound)")
    class PerBatchCap {

        private void registerSafe(DefaultToolRegistry registry, List<ToolUse> uses) {
            uses.forEach(u -> registry
                    .register(tool(u.getName(), ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE)));
        }

        /** Counting runner: tracks peak simultaneous invocations and rendezvouses {@code groupSize} at a time. */
        private Function<ToolUse, ToolUseResult> countingRunner(AtomicInteger active, AtomicInteger peak,
                CyclicBarrier barrier) {
            return tu -> {
                final int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try {
                    barrier.await(3, TimeUnit.SECONDS);
                    return ToolUseResult.success(tu.getId(), "ok");
                } catch (Exception e) {
                    return ToolUseResult.error(tu.getId(), "no-rendezvous: " + e.getClass().getSimpleName());
                } finally {
                    active.decrementAndGet();
                }
            };
        }

        @Test
        @DisplayName("a batch larger than perBatchMax never exceeds perBatchMax concurrent runners")
        void batchThrottledToPerBatchMax() throws Exception {
            final int perBatchMax = 2;
            final int batch = 6;
            // Global pool (maxConcurrency=batch) is large enough that perBatchMax, not the pool, is the binding limit.
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(batch, perBatchMax))) {
                final List<ToolUse> uses = IntStream.range(0, batch).mapToObj(i -> use(String.valueOf(i), "T" + i))
                        .collect(Collectors.toList());
                final DefaultToolRegistry registry = new DefaultToolRegistry();
                registerSafe(registry, uses);

                final AtomicInteger active = new AtomicInteger();
                final AtomicInteger peak = new AtomicInteger();
                // A barrier of exactly perBatchMax forces that many to run together (proving the cap is reachable);
                // with a timeout, a too-low effective cap would fail the rendezvous and surface as an error result.
                final CyclicBarrier barrier = new CyclicBarrier(perBatchMax);

                final List<ToolUseResult> results = dispatcher.dispatch(uses, registry,
                        countingRunner(active, peak, barrier), NO_STARTED, NO_COMPLETED);

                assertThat(results).hasSize(batch);
                assertThat(results).allMatch(ToolUseResult::isSuccess);
                assertThat(peak.get()).isEqualTo(perBatchMax);
            }
        }

        @Test
        @DisplayName("two concurrent dispatch() calls never exceed the global maxConcurrency")
        void concurrentDispatchesShareGlobalCeiling() throws Exception {
            final int globalMax = 3;
            // perBatchMax == globalMax, so the per-batch cap does not constrain below the pool; the shared pool is the
            // binding constraint across BOTH dispatch() calls (2 batches x 3 tools = 6 tasks competing for 3 slots).
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(globalMax, globalMax))) {
                final AtomicInteger active = new AtomicInteger();
                final AtomicInteger peak = new AtomicInteger();
                // Any globalMax tasks (from either dispatch) rendezvous — proves concurrency reaches the global bound.
                final CyclicBarrier barrier = new CyclicBarrier(globalMax);
                final Function<ToolUse, ToolUseResult> runner = countingRunner(active, peak, barrier);

                final List<ToolUse> batchA = List.of(use("a0", "A0"), use("a1", "A1"), use("a2", "A2"));
                final List<ToolUse> batchB = List.of(use("b0", "B0"), use("b1", "B1"), use("b2", "B2"));
                final DefaultToolRegistry registry = new DefaultToolRegistry();
                registerSafe(registry, batchA);
                registerSafe(registry, batchB);

                final ExecutorService driver = Executors.newFixedThreadPool(2);
                try {
                    final Future<List<ToolUseResult>> fa = driver
                            .submit(() -> dispatcher.dispatch(batchA, registry, runner, NO_STARTED, NO_COMPLETED));
                    final Future<List<ToolUseResult>> fb = driver
                            .submit(() -> dispatcher.dispatch(batchB, registry, runner, NO_STARTED, NO_COMPLETED));
                    assertThat(fa.get(10, TimeUnit.SECONDS)).hasSize(3).allMatch(ToolUseResult::isSuccess);
                    assertThat(fb.get(10, TimeUnit.SECONDS)).hasSize(3).allMatch(ToolUseResult::isSuccess);
                } finally {
                    driver.shutdownNow();
                }
                assertThat(peak.get()).isEqualTo(globalMax);
            }
        }

        @Test
        @DisplayName("onStarted events stay in input order even when throttled by perBatchMax")
        void throttledStartOrderPreserved() throws Exception {
            final int perBatchMax = 2;
            final int batch = 6;
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(batch, perBatchMax))) {
                final List<ToolUse> uses = IntStream.range(0, batch).mapToObj(i -> use(String.valueOf(i), "T" + i))
                        .collect(Collectors.toList());
                final DefaultToolRegistry registry = new DefaultToolRegistry();
                registerSafe(registry, uses);

                final List<String> started = new CopyOnWriteArrayList<>();
                final AtomicInteger active = new AtomicInteger();
                final AtomicInteger peak = new AtomicInteger();
                // Rendezvous perBatchMax at a time so the calling thread must block on the semaphore between groups —
                // exercising the deferred-onStarted path — while still requiring start events in input order.
                final CyclicBarrier barrier = new CyclicBarrier(perBatchMax);

                dispatcher.dispatch(uses, registry, countingRunner(active, peak, barrier),
                        tu -> started.add(tu.getId()), NO_COMPLETED);

                assertThat(started).containsExactly("0", "1", "2", "3", "4", "5");
                assertThat(peak.get()).isEqualTo(perBatchMax);
            }
        }
    }

    // ---- lifecycle ----------------------------------------------------------

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("dispatch after close() falls back to sequential (does not resurrect a pool)")
        void dispatchAfterCloseFallsBackToSequential() {
            final DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(4));
            dispatcher.close();
            final DefaultToolRegistry registry = new DefaultToolRegistry();
            final List<ToolUse> uses = List.of(use("0", "A"), use("1", "B"));
            uses.forEach(u -> registry
                    .register(tool(u.getName(), ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE)));

            final List<ToolUseResult> results = dispatcher.dispatch(uses, registry, echoRunner(), NO_STARTED,
                    NO_COMPLETED);

            assertThat(results).extracting(ToolUseResult::getToolUseId).containsExactly("0", "1");
            assertThat(results).allMatch(ToolUseResult::isSuccess);
        }

        @Test
        @DisplayName("a concurrent close() mid-batch never throws or hangs; remaining tools still complete in order")
        void midBatchCloseFallsBackWithoutLeak() throws Exception {
            // perBatchMax=1 serializes submission: tool B is only submitted after tool A completes, creating a
            // deterministic window to close() the pool between the two submissions and exercise the
            // RejectedExecutionException fallback (which must release the permit and run the tail sequentially).
            final DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(1, 1));
            final List<ToolUse> uses = List.of(use("0", "A"), use("1", "B"));
            final DefaultToolRegistry registry = new DefaultToolRegistry();
            uses.forEach(u -> registry
                    .register(tool(u.getName(), ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE)));

            final CountDownLatch aRunning = new CountDownLatch(1);
            final CountDownLatch aRelease = new CountDownLatch(1);
            final Function<ToolUse, ToolUseResult> runner = tu -> {
                if ("0".equals(tu.getId())) {
                    aRunning.countDown();
                    try {
                        aRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return ToolUseResult.success(tu.getId(), "ran");
            };

            final ExecutorService driver = Executors.newSingleThreadExecutor();
            try {
                final Future<List<ToolUseResult>> dispatch = driver
                        .submit(() -> dispatcher.dispatch(uses, registry, runner, NO_STARTED, NO_COMPLETED));

                // A is running on the pool; the dispatch (calling) thread is now blocked acquiring B's permit.
                assertThat(aRunning.await(5, TimeUnit.SECONDS)).isTrue();
                final Thread closer = new Thread(dispatcher::close, "closer");
                closer.start();
                // Wait until close() has flipped the closed flag (it shuts the pool down immediately after) so that B's
                // submission is rejected; only then release A so the calling thread tries to submit B.
                while (!dispatcher.isClosed()) {
                    Thread.yield();
                }
                aRelease.countDown();

                // Would TIME OUT if the rejected submission leaked a permit and hung the calling thread (the bug).
                final List<ToolUseResult> results = dispatch.get(10, TimeUnit.SECONDS);
                assertThat(results).extracting(ToolUseResult::getToolUseId).containsExactly("0", "1");
                assertThat(results).allMatch(ToolUseResult::isSuccess);
                closer.join(5_000);
            } finally {
                driver.shutdownNow();
                dispatcher.close();
            }
        }

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            final DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(2));
            dispatcher.close();
            dispatcher.close();
        }
    }

    // ---- exception isolation ------------------------------------------------

    @Nested
    @DisplayName("Exception isolation")
    class ExceptionIsolation {

        @Test
        @DisplayName("a throwing runner becomes an error result without breaking the batch (parallel)")
        void runnerThrowsParallel() throws Exception {
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(4))) {
                final DefaultToolRegistry registry = new DefaultToolRegistry();
                final List<ToolUse> uses = List.of(use("0", "A"), use("1", "B"), use("2", "C"));
                uses.forEach(u -> registry.register(
                        tool(u.getName(), ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE)));

                final Function<ToolUse, ToolUseResult> runner = tu -> {
                    if ("1".equals(tu.getId())) {
                        throw new RuntimeException("boom");
                    }
                    return ToolUseResult.success(tu.getId(), "ok");
                };

                final List<ToolUseResult> results = dispatcher.dispatch(uses, registry, runner, NO_STARTED,
                        NO_COMPLETED);

                assertThat(results).extracting(ToolUseResult::getToolUseId).containsExactly("0", "1", "2");
                assertThat(results.get(0).isSuccess()).isTrue();
                assertThat(results.get(1).isError()).isTrue();
                assertThat(results.get(1).getContent()).contains("boom");
                assertThat(results.get(2).isSuccess()).isTrue();
            }
        }

        @Test
        @DisplayName("a null runner result becomes an error result")
        void runnerReturnsNull() {
            final DefaultParallelToolDispatcher dispatcher = DefaultParallelToolDispatcher.sequential();
            final List<ToolUseResult> results = dispatcher.dispatch(List.of(use("1", "A")), null, tu -> null,
                    NO_STARTED, NO_COMPLETED);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).isError()).isTrue();
        }

        @Test
        @DisplayName("throwing listeners do not break the batch")
        void throwingListeners() {
            final DefaultParallelToolDispatcher dispatcher = DefaultParallelToolDispatcher.sequential();
            final AtomicInteger ran = new AtomicInteger();
            final Function<ToolUse, ToolUseResult> runner = tu -> {
                ran.incrementAndGet();
                return ToolUseResult.success(tu.getId(), "ok");
            };

            final List<ToolUseResult> results = dispatcher.dispatch(List.of(use("1", "A"), use("2", "B")), null, runner,
                    tu -> {
                        throw new IllegalStateException("started-boom");
                    }, (tu, r) -> {
                        throw new IllegalStateException("completed-boom");
                    });

            assertThat(ran.get()).isEqualTo(2);
            assertThat(results).extracting(ToolUseResult::getToolUseId).containsExactly("1", "2");
            assertThat(results).allMatch(ToolUseResult::isSuccess);
        }
    }

    // ---- eager dispatch (streaming-tool overlap) ------------------------

    @Nested
    @DisplayName("Eager dispatch")
    class EagerDispatch {

        private DefaultToolRegistry registryWith(Tool... tools) {
            final DefaultToolRegistry registry = new DefaultToolRegistry();
            for (Tool t : tools) {
                registry.register(t);
            }
            return registry;
        }

        private ToolConcurrencyConfig overlap(int max, int perBatch) {
            return ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(max).perBatchMax(perBatch)
                    .streamingOverlap(true).build();
        }

        @Test
        @DisplayName("supportsEagerDispatch requires enabled + streamingOverlap + not closed")
        void supportsEagerDispatchGate() {
            try (DefaultParallelToolDispatcher on = new DefaultParallelToolDispatcher(overlap(4, 4))) {
                assertThat(on.supportsEagerDispatch()).isTrue();
            }
            // enabled but overlap off
            try (DefaultParallelToolDispatcher off = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(4))) {
                assertThat(off.supportsEagerDispatch()).isFalse();
            }
            // overlap requested but concurrency disabled -> builder still records the flag, but not enabled
            try (DefaultParallelToolDispatcher disabled = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.builder().enabled(false).streamingOverlap(true).build())) {
                assertThat(disabled.supportsEagerDispatch()).isFalse();
            }
            // closed dispatcher never supports eager dispatch
            final DefaultParallelToolDispatcher closed = new DefaultParallelToolDispatcher(overlap(4, 4));
            closed.close();
            assertThat(closed.supportsEagerDispatch()).isFalse();
        }

        @Test
        @DisplayName("isEagerEligible mirrors the gate: CONCURRENT_SAFE + parallelisable interrupt only")
        void isEagerEligibleRules() {
            final DefaultToolRegistry registry = registryWith(
                    tool("Safe", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("NonInt", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.NON_INTERRUPTIBLE),
                    tool("Seq", ConcurrencyBehavior.SEQUENTIAL, InterruptBehavior.COOPERATIVE),
                    tool("ThreadInt", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.THREAD_INTERRUPT),
                    tool("ExtTerm", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.EXTERNALLY_TERMINATED));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlap(4, 4))) {
                assertThat(dispatcher.isEagerEligible(use("1", "Safe"), registry)).isTrue();
                assertThat(dispatcher.isEagerEligible(use("2", "NonInt"), registry)).isTrue();
                assertThat(dispatcher.isEagerEligible(use("3", "Seq"), registry)).isFalse();
                assertThat(dispatcher.isEagerEligible(use("4", "ThreadInt"), registry)).isFalse();
                assertThat(dispatcher.isEagerEligible(use("5", "ExtTerm"), registry)).isFalse();
                assertThat(dispatcher.isEagerEligible(use("6", "Ghost"), registry)).isFalse();
            }
        }

        @Test
        @DisplayName("isEagerEligible is false when overlap is off even for a safe tool")
        void isEagerEligibleFalseWhenOverlapOff() {
            final DefaultToolRegistry registry = registryWith(
                    tool("Safe", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(4))) {
                assertThat(dispatcher.isEagerEligible(use("1", "Safe"), registry)).isFalse();
            }
        }

        @Test
        @DisplayName("submitEager runs the tool on the pool and returns its future result")
        void submitEagerRuns() throws Exception {
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlap(4, 4))) {
                final var future = dispatcher.submitEager(use("1", "Safe"), echoRunner());
                assertThat(future).isPresent();
                assertThat(future.get().get(3, TimeUnit.SECONDS).getContent()).isEqualTo("ran:Safe");
            }
        }

        @Test
        @DisplayName("submitEager returns empty after close (no pool resurrection)")
        void submitEagerEmptyAfterClose() {
            final DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlap(4, 4));
            dispatcher.close();
            assertThat(dispatcher.submitEager(use("1", "Safe"), echoRunner())).isEmpty();
        }

        @Test
        @DisplayName("eagerPermits equals perBatchMax")
        void eagerPermitsIsPerBatchMax() {
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlap(8, 3))) {
                assertThat(dispatcher.eagerPermits()).isEqualTo(3);
            }
        }
    }

    // ---- validation ---------------------------------------------------------

    @Test
    @DisplayName("dispatch rejects null toolUses / runner")
    void rejectsNulls() {
        final DefaultParallelToolDispatcher dispatcher = DefaultParallelToolDispatcher.sequential();
        assertThatThrownBy(() -> dispatcher.dispatch(null, null, echoRunner(), NO_STARTED, NO_COMPLETED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(List.of(use("1", "A")), null, null, NO_STARTED, NO_COMPLETED))
                .isInstanceOf(NullPointerException.class);
    }
}
