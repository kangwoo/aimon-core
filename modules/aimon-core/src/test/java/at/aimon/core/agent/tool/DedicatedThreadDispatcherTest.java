package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

@DisplayName("DedicatedThreadDispatcher Tests")
class DedicatedThreadDispatcherTest {

    private static final String TASK = "Task";
    private static final String READ = "Read";
    private static final String GREP = "Grep";

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

    private static DefaultToolRegistry registry() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool(TASK, ConcurrencyBehavior.DEDICATED_THREAD, InterruptBehavior.EXTERNALLY_TERMINATED));
        registry.register(tool(READ, ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.NON_INTERRUPTIBLE));
        registry.register(tool(GREP, ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.NON_INTERRUPTIBLE));
        return registry;
    }

    private static ToolUse use(String id, String name) {
        return ToolUse.of(id, name, Map.of());
    }

    private static List<String> ids(List<ToolUseResult> results) {
        return results.stream().map(ToolUseResult::getToolUseId).toList();
    }

    /** Records every call the decorator hands it, then runs the batch sequentially. */
    private static final class RecordingDelegate implements ParallelToolDispatcher, AutoCloseable {
        private final List<List<ToolUse>> calls = new CopyOnWriteArrayList<>();
        private final List<DispatchOptions> options = new CopyOnWriteArrayList<>();
        private final DefaultParallelToolDispatcher sequential = DefaultParallelToolDispatcher.sequential();
        private boolean closed;

        @Override
        public List<ToolUseResult> dispatch(List<ToolUse> toolUses, ToolRegistry registry, DispatchOptions options,
                Function<ToolUse, ToolUseResult> runner, Consumer<ToolUse> onStarted,
                BiConsumer<ToolUse, ToolUseResult> onCompleted) {
            this.options.add(options);
            return dispatch(toolUses, registry, runner, onStarted, onCompleted);
        }

        @Override
        public List<ToolUseResult> dispatch(List<ToolUse> toolUses, ToolRegistry registry,
                Function<ToolUse, ToolUseResult> runner, Consumer<ToolUse> onStarted,
                BiConsumer<ToolUse, ToolUseResult> onCompleted) {
            calls.add(toolUses);
            return sequential.dispatch(toolUses, registry, runner, onStarted, onCompleted);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static DedicatedThreadDispatcher dispatcher(ParallelToolDispatcher delegate, int maxThreads) {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(maxThreads);
        return new DedicatedThreadDispatcher(delegate, config, DedicatedThreadPermits.of(config));
    }

    // ---- bypass -------------------------------------------------------------

    @Test
    @DisplayName("disabled config hands the original batch to the delegate in exactly one call")
    void disabledDelegatesWholeBatchOnce() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final DedicatedThreadConfig off = DedicatedThreadConfig.disabled();
        final DedicatedThreadDispatcher dispatcher = new DedicatedThreadDispatcher(delegate, off,
                DedicatedThreadPermits.of(off));
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK));

        final List<ToolUseResult> results = dispatcher.dispatch(batch, registry(),
                tu -> ToolUseResult.success(tu.getId(), "ok"), null, null);

        assertThat(delegate.calls).hasSize(1);
        assertThat(delegate.calls.get(0)).isSameAs(batch);
        assertThat(ids(results)).containsExactly("t1", "t2");
    }

    @Test
    @DisplayName("a batch with no DEDICATED_THREAD tool goes to the delegate whole")
    void noDedicatedToolDelegatesWholeBatch() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final List<ToolUse> batch = List.of(use("r1", READ), use("g1", GREP));

        dispatcher(delegate, 4).dispatch(batch, registry(), tu -> ToolUseResult.success(tu.getId(), "ok"), null, null);

        assertThat(delegate.calls).hasSize(1);
        assertThat(delegate.calls.get(0)).isSameAs(batch);
    }

    @Test
    @DisplayName("an execution at or beyond maxForkDepth goes to the delegate whole")
    void forkDepthAtLimitDelegatesWholeBatch() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK));
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        dispatcher(delegate, 4).dispatch(batch, registry(), DispatchOptions.builder().forkDepth(1).build(), tu -> {
            threads.add(Thread.currentThread());
            return ToolUseResult.success(tu.getId(), "ok");
        }, null, null);

        assertThat(delegate.calls).hasSize(1);
        assertThat(delegate.calls.get(0)).isSameAs(batch);
        assertThat(threads).containsExactly(Thread.currentThread());
    }

    @Test
    @DisplayName("an unregistered tool name is not dedicated")
    void unregisteredNameDelegates() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final List<ToolUse> batch = List.of(use("x1", "Hallucinated"), use("x2", "Hallucinated"));

        dispatcher(delegate, 4).dispatch(batch, registry(), tu -> ToolUseResult.success(tu.getId(), "ok"), null, null);

        assertThat(delegate.calls).containsExactly(batch);
    }

    @Test
    @DisplayName("a null registry goes to the delegate whole")
    void nullRegistryDelegates() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final List<ToolUse> batch = List.of(use("t1", TASK));

        dispatcher(delegate, 4).dispatch(batch, null, tu -> ToolUseResult.success(tu.getId(), "ok"), null, null);

        assertThat(delegate.calls).containsExactly(batch);
    }

    // ---- fan-out ------------------------------------------------------------

    @Test
    @DisplayName("consecutive DEDICATED_THREAD calls run at the same time, each on its own fresh daemon thread")
    void dedicatedCallsRunConcurrentlyOnFreshThreads() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final CyclicBarrier barrier = new CyclicBarrier(3);
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK), use("t3", TASK));

        final List<ToolUseResult> results = dispatcher(delegate, 4).dispatch(batch, registry(), tu -> {
            threads.add(Thread.currentThread());
            try {
                // Only passes if all three are in flight at once.
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                return ToolUseResult.error(tu.getId(), "barrier: " + e);
            }
            return ToolUseResult.success(tu.getId(), "ok");
        }, null, null);

        assertThat(results).allMatch(r -> !r.isError());
        assertThat(ids(results)).containsExactly("t1", "t2", "t3");
        assertThat(threads).hasSize(3).doesNotHaveDuplicates().doesNotContain(Thread.currentThread())
                .allMatch(Thread::isDaemon).allMatch(t -> t.getName().startsWith("tool-dedicated-"));
        assertThat(delegate.calls).isEmpty();
    }

    @Test
    @DisplayName("wall clock is about the longest call, not the sum")
    void wallClockIsAboutTheMax() {
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK), use("t3", TASK));
        final long start = System.nanoTime();

        dispatcher(new RecordingDelegate(), 4).dispatch(batch, registry(), tu -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolUseResult.success(tu.getId(), "ok");
        }, null, null);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500);
    }

    @Test
    @DisplayName("results come back in input order even when completion order is reversed")
    void resultsInInputOrder() {
        final CountDownLatch t3Done = new CountDownLatch(1);
        final CountDownLatch t2Done = new CountDownLatch(1);
        final List<String> completion = new CopyOnWriteArrayList<>();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK), use("t3", TASK));

        final List<ToolUseResult> results = dispatcher(new RecordingDelegate(), 4).dispatch(batch, registry(), tu -> {
            try {
                if (tu.getId().equals("t1")) {
                    t2Done.await(5, TimeUnit.SECONDS);
                } else if (tu.getId().equals("t2")) {
                    t3Done.await(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            completion.add(tu.getId());
            if (tu.getId().equals("t3")) {
                t3Done.countDown();
            } else if (tu.getId().equals("t2")) {
                t2Done.countDown();
            }
            return ToolUseResult.success(tu.getId(), "ok");
        }, null, null);

        assertThat(completion).containsExactly("t3", "t2", "t1");
        assertThat(ids(results)).containsExactly("t1", "t2", "t3");
    }

    @Test
    @DisplayName("onStarted fires on the calling thread in input order; onCompleted on the dedicated thread")
    void callbackThreads() {
        final List<String> started = new CopyOnWriteArrayList<>();
        final List<Thread> startedThreads = new CopyOnWriteArrayList<>();
        final Map<String, Thread> runThreads = new ConcurrentHashMap<>();
        final Map<String, Thread> completedThreads = new ConcurrentHashMap<>();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK), use("t3", TASK));

        dispatcher(new RecordingDelegate(), 4).dispatch(batch, registry(), tu -> {
            runThreads.put(tu.getId(), Thread.currentThread());
            return ToolUseResult.success(tu.getId(), "ok");
        }, tu -> {
            started.add(tu.getId());
            startedThreads.add(Thread.currentThread());
        }, (tu, r) -> completedThreads.put(tu.getId(), Thread.currentThread()));

        assertThat(started).containsExactly("t1", "t2", "t3");
        assertThat(startedThreads).containsOnly(Thread.currentThread());
        assertThat(completedThreads).isEqualTo(runThreads);
        assertThat(completedThreads.values()).doesNotContain(Thread.currentThread());
    }

    @Test
    @DisplayName("mixed batch: other segments go to the delegate in order, around the dedicated segment")
    void mixedBatchSegments() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final List<String> events = new CopyOnWriteArrayList<>();
        final CyclicBarrier tasks = new CyclicBarrier(2);
        final List<ToolUse> batch = List.of(use("r1", READ), use("r2", READ), use("t1", TASK), use("t2", TASK),
                use("g1", GREP), use("t3", TASK));

        final List<ToolUseResult> results = dispatcher(delegate, 4).dispatch(batch, registry(), tu -> {
            events.add("start:" + tu.getId());
            if (tu.getId().equals("t1") || tu.getId().equals("t2")) {
                try {
                    tasks.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return ToolUseResult.error(tu.getId(), "barrier: " + e);
                }
            }
            events.add("end:" + tu.getId());
            return ToolUseResult.success(tu.getId(), "ok");
        }, null, null);

        assertThat(ids(results)).containsExactly("r1", "r2", "t1", "t2", "g1", "t3");
        assertThat(results).allMatch(r -> !r.isError());
        assertThat(delegate.calls).hasSize(2);
        assertThat(delegate.calls.get(0)).extracting(ToolUse::getId).containsExactly("r1", "r2");
        assertThat(delegate.calls.get(1)).extracting(ToolUse::getId).containsExactly("g1");
        // Grep starts only after both Tasks finished, and the trailing Task after Grep.
        assertThat(events.indexOf("start:g1")).isGreaterThan(events.indexOf("end:t1"))
                .isGreaterThan(events.indexOf("end:t2"));
        assertThat(events.indexOf("start:t3")).isGreaterThan(events.indexOf("end:g1"));
        assertThat(events.indexOf("start:t1")).isGreaterThan(events.indexOf("end:r2"));
    }

    // ---- bound --------------------------------------------------------------

    @Test
    @DisplayName("over the bound, extra calls run inline on the calling thread and every permit comes back")
    void overTheBoundRunsInline() {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(2);
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(config);
        final DedicatedThreadDispatcher dispatcher = new DedicatedThreadDispatcher(new RecordingDelegate(), config,
                permits);
        final AtomicInteger live = new AtomicInteger();
        final AtomicInteger maxLive = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        final Map<String, Thread> threads = new ConcurrentHashMap<>();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK), use("t3", TASK), use("t4", TASK),
                use("t5", TASK));
        final Thread caller = Thread.currentThread();

        final List<ToolUseResult> results = dispatcher.dispatch(batch, registry(), tu -> {
            threads.put(tu.getId(), Thread.currentThread());
            if (Thread.currentThread() == caller) {
                // The inline lane: let the dedicated ones finish once the caller has had its turn.
                release.countDown();
                return ToolUseResult.success(tu.getId(), "ok");
            }
            maxLive.accumulateAndGet(live.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            live.decrementAndGet();
            return ToolUseResult.success(tu.getId(), "ok");
        }, null, null);

        assertThat(ids(results)).containsExactly("t1", "t2", "t3", "t4", "t5");
        assertThat(maxLive.get()).isLessThanOrEqualTo(2);
        assertThat(threads.get("t1")).isNotSameAs(caller);
        assertThat(threads.get("t2")).isNotSameAs(caller);
        assertThat(threads.get("t3")).isSameAs(caller);
        assertThat(permits.availablePermits()).isEqualTo(2);
    }

    @Test
    @DisplayName("two dispatches sharing one permit set stay within the bound and both finish")
    void sharedPermitsAcrossConcurrentDispatches() throws Exception {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(3);
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(config);
        final DedicatedThreadDispatcher first = new DedicatedThreadDispatcher(new RecordingDelegate(), config, permits);
        final DedicatedThreadDispatcher second = new DedicatedThreadDispatcher(new RecordingDelegate(), config,
                permits);
        final AtomicInteger liveDedicated = new AtomicInteger();
        final AtomicInteger maxLiveDedicated = new AtomicInteger();
        final Function<ToolUse, ToolUseResult> runner = tu -> {
            final boolean dedicated = Thread.currentThread().getName().startsWith("tool-dedicated-");
            if (dedicated) {
                maxLiveDedicated.accumulateAndGet(liveDedicated.incrementAndGet(), Math::max);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (dedicated) {
                liveDedicated.decrementAndGet();
            }
            return ToolUseResult.success(tu.getId(), "ok");
        };
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK), use("t3", TASK), use("t4", TASK));
        final ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            final Future<List<ToolUseResult>> a = callers
                    .submit(() -> first.dispatch(batch, registry(), runner, null, null));
            final Future<List<ToolUseResult>> b = callers
                    .submit(() -> second.dispatch(batch, registry(), runner, null, null));

            assertThat(ids(a.get(10, TimeUnit.SECONDS))).containsExactly("t1", "t2", "t3", "t4");
            assertThat(ids(b.get(10, TimeUnit.SECONDS))).containsExactly("t1", "t2", "t3", "t4");
        } finally {
            callers.shutdownNow();
        }
        assertThat(maxLiveDedicated.get()).isLessThanOrEqualTo(3);
        assertThat(permits.availablePermits()).isEqualTo(3);
    }

    // ---- failure isolation --------------------------------------------------

    @Test
    @DisplayName("a runner that throws or returns null yields an error result; the batch and permits survive")
    void runnerFailuresIsolated() {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(4);
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(config);
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK), use("t3", TASK));

        final List<ToolUseResult> results = new DedicatedThreadDispatcher(new RecordingDelegate(), config, permits)
                .dispatch(batch, registry(), tu -> {
                    if (tu.getId().equals("t1")) {
                        throw new IllegalStateException("boom");
                    }
                    if (tu.getId().equals("t2")) {
                        return null;
                    }
                    return ToolUseResult.success(tu.getId(), "ok");
                }, null, null);

        assertThat(ids(results)).containsExactly("t1", "t2", "t3");
        assertThat(results.get(0).isError()).isTrue();
        assertThat(results.get(0).getContent()).contains("boom");
        assertThat(results.get(1).isError()).isTrue();
        assertThat(results.get(2).isError()).isFalse();
        assertThat(permits.availablePermits()).isEqualTo(4);
    }

    @Test
    @DisplayName("an Error on a dedicated thread becomes an error result and the permit is returned")
    void errorOnDedicatedThreadIsolated() {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(4);
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(config);
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK));

        final List<ToolUseResult> results = new DedicatedThreadDispatcher(new RecordingDelegate(), config, permits)
                .dispatch(batch, registry(), tu -> {
                    if (tu.getId().equals("t1")) {
                        throw new AssertionError("fatal");
                    }
                    return ToolUseResult.success(tu.getId(), "ok");
                }, null, null);

        assertThat(ids(results)).containsExactly("t1", "t2");
        assertThat(results.get(0).isError()).isTrue();
        assertThat(results.get(0).getContent()).contains("fatal");
        assertThat(permits.availablePermits()).isEqualTo(4);
    }

    // ---- interrupts ---------------------------------------------------------

    @Test
    @DisplayName("an interrupted caller passes the interrupt on, waits for every call, and restores its flag")
    void callerInterruptPropagatesAndIsRestored() throws Exception {
        final CountDownLatch allRunning = new CountDownLatch(2);
        final Map<String, Boolean> sawInterrupt = new ConcurrentHashMap<>();
        final AtomicReference<List<ToolUseResult>> results = new AtomicReference<>();
        final AtomicBoolean callerFlagAfter = new AtomicBoolean();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK));

        final Thread caller = new Thread(() -> {
            results.set(dispatcher(new RecordingDelegate(), 4).dispatch(batch, registry(), tu -> {
                allRunning.countDown();
                try {
                    Thread.sleep(10_000);
                    sawInterrupt.put(tu.getId(), false);
                } catch (InterruptedException e) {
                    sawInterrupt.put(tu.getId(), true);
                }
                return ToolUseResult.success(tu.getId(), "ok");
            }, null, null));
            callerFlagAfter.set(Thread.interrupted());
        });
        caller.start();
        assertThat(allRunning.await(5, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(5_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(sawInterrupt).containsEntry("t1", true).containsEntry("t2", true);
        assertThat(ids(results.get())).containsExactly("t1", "t2");
        assertThat(callerFlagAfter.get()).isTrue();
    }

    @Test
    @DisplayName("a late interrupt landing on a dedicated thread after its call returned never reaches the caller")
    void lateInterruptStaysOnTheDedicatedThread() {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(4);
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(config);
        final CountDownLatch returned = new CountDownLatch(1);
        final CountDownLatch mayFinish = new CountDownLatch(1);
        final AtomicReference<Thread> dedicated = new AtomicReference<>();
        final List<ToolUse> batch = List.of(use("t1", TASK));

        // A terminator fired from another thread after the tool returned: the thread is interrupted while it still
        // runs onCompleted, the last thing it does before it dies.
        final Thread lateTerminator = new Thread(() -> {
            try {
                returned.await(5, TimeUnit.SECONDS);
                dedicated.get().interrupt();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                mayFinish.countDown();
            }
        });
        lateTerminator.start();

        final List<ToolUseResult> results = new DedicatedThreadDispatcher(new RecordingDelegate(), config, permits)
                .dispatch(batch, registry(), tu -> {
                    dedicated.set(Thread.currentThread());
                    return ToolUseResult.success(tu.getId(), "ok");
                }, null, (tu, r) -> {
                    returned.countDown();
                    try {
                        mayFinish.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        assertThat(Thread.interrupted()).isFalse();
        assertThat(results).singleElement().satisfies(r -> assertThat(r.isError()).isFalse());
        assertThat(dedicated.get()).isNotSameAs(Thread.currentThread());
        assertThat(permits.availablePermits()).isEqualTo(4);
    }

    @Test
    @DisplayName("back-to-back batches never share a thread: the first batch's threads are gone, not reused")
    void backToBackBatchesUseFreshThreads() throws Exception {
        final DedicatedThreadDispatcher dispatcher = dispatcher(new RecordingDelegate(), 4);
        final List<Thread> first = new CopyOnWriteArrayList<>();
        final List<Thread> second = new CopyOnWriteArrayList<>();

        dispatcher.dispatch(List.of(use("a1", TASK), use("a2", TASK)), registry(), tu -> {
            first.add(Thread.currentThread());
            return ToolUseResult.success(tu.getId(), "ok");
        }, null, null);
        dispatcher.dispatch(List.of(use("b1", TASK), use("b2", TASK)), registry(), tu -> {
            second.add(Thread.currentThread());
            return ToolUseResult.success(tu.getId(), "ok");
        }, null, null);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2).doesNotContainAnyElementsOf(first);
        // A late terminator interrupt aimed at a first-batch thread has nowhere to land: that thread has died.
        for (Thread thread : first) {
            thread.join(1_000);
            assertThat(thread.isAlive()).isFalse();
        }
    }

    @Test
    @DisplayName("an Error on a dedicated thread still fires onCompleted, with the error result")
    void errorOnDedicatedThreadFiresOnCompleted() {
        final Map<String, ToolUseResult> completed = new ConcurrentHashMap<>();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK));

        final List<ToolUseResult> results = dispatcher(new RecordingDelegate(), 4).dispatch(batch, registry(), tu -> {
            if (tu.getId().equals("t1")) {
                throw new AssertionError("fatal");
            }
            return ToolUseResult.success(tu.getId(), "ok");
        }, null, (tu, r) -> completed.put(tu.getId(), r));

        assertThat(completed).containsOnlyKeys("t1", "t2");
        assertThat(completed.get("t1")).isSameAs(results.get(0));
        assertThat(completed.get("t1").isError()).isTrue();
        assertThat(completed.get("t1").getContent()).contains("fatal");
    }

    @Test
    @DisplayName("a thread that cannot be created runs its call inline; order is kept and every permit comes back")
    void threadStartFailureRunsInline() {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(3);
        final AtomicInteger created = new AtomicInteger();
        final ThreadFactory failingSecond = task -> {
            if (created.incrementAndGet() == 2) {
                throw new OutOfMemoryError("unable to create native thread");
            }
            return new Thread(task, "tool-dedicated-test-" + created.get());
        };
        final DedicatedThreadPermits permits = new DedicatedThreadPermits(3, failingSecond);
        final Map<String, Thread> threads = new ConcurrentHashMap<>();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK), use("t3", TASK));

        final List<ToolUseResult> results = new DedicatedThreadDispatcher(new RecordingDelegate(), config, permits)
                .dispatch(batch, registry(), tu -> {
                    threads.put(tu.getId(), Thread.currentThread());
                    return ToolUseResult.success(tu.getId(), "ok");
                }, null, null);

        assertThat(ids(results)).containsExactly("t1", "t2", "t3");
        assertThat(results).allMatch(r -> !r.isError());
        assertThat(threads.get("t2")).isSameAs(Thread.currentThread());
        assertThat(threads.get("t1")).isNotSameAs(Thread.currentThread());
        assertThat(threads.get("t3")).isNotSameAs(Thread.currentThread());
        assertThat(permits.availablePermits()).isEqualTo(3);
    }

    @Test
    @DisplayName("an Error on the inline lane interrupts and joins the started threads before it propagates")
    void errorOnInlineLaneJoinsStartedThreads() {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(1);
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(config);
        final CountDownLatch t1Running = new CountDownLatch(1);
        final AtomicBoolean t1SawInterrupt = new AtomicBoolean();
        final AtomicReference<Thread> t1Thread = new AtomicReference<>();
        final List<ToolUse> started = new CopyOnWriteArrayList<>();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK), use("t3", TASK));
        final DedicatedThreadDispatcher dispatcher = new DedicatedThreadDispatcher(new RecordingDelegate(), config,
                permits);

        assertThatThrownBy(() -> dispatcher.dispatch(batch, registry(), tu -> {
            if (tu.getId().equals("t1")) {
                t1Thread.set(Thread.currentThread());
                t1Running.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    t1SawInterrupt.set(true);
                }
                return ToolUseResult.success(tu.getId(), "ok");
            }
            // t2 runs inline (the only permit is taken); wait until t1 is really running, then fail hard.
            try {
                t1Running.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("inline fatal");
        }, started::add, null)).isInstanceOf(AssertionError.class).hasMessage("inline fatal");

        assertThat(t1SawInterrupt).isTrue();
        assertThat(t1Thread.get().isAlive()).isFalse();
        assertThat(started).extracting(ToolUse::getId).containsExactly("t1", "t2");
        assertThat(permits.availablePermits()).isEqualTo(1);
        assertThat(Thread.interrupted()).isFalse();
    }

    @Test
    @DisplayName("nested fan-out on one shared permit set does not deadlock")
    void nestedFanOutDoesNotDeadlock() {
        final DedicatedThreadConfig config = DedicatedThreadConfig.builder().enabled(true).maxThreads(2).maxForkDepth(2)
                .build();
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(config);
        final DedicatedThreadDispatcher outer = new DedicatedThreadDispatcher(new RecordingDelegate(), config, permits);
        final DedicatedThreadDispatcher inner = new DedicatedThreadDispatcher(new RecordingDelegate(), config, permits);
        final DispatchOptions depthOne = DispatchOptions.builder().forkDepth(1).build();
        final Map<String, List<String>> innerResults = new ConcurrentHashMap<>();
        final List<ToolUse> batch = List.of(use("o1", TASK), use("o2", TASK));

        final List<ToolUseResult> results = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> outer.dispatch(batch, registry(), tu -> {
                    final List<ToolUse> children = List.of(use(tu.getId() + ".c1", TASK),
                            use(tu.getId() + ".c2", TASK));
                    innerResults.put(tu.getId(), ids(inner.dispatch(children, registry(), depthOne,
                            child -> ToolUseResult.success(child.getId(), "ok"), null, null)));
                    return ToolUseResult.success(tu.getId(), "ok");
                }, null, null));

        assertThat(ids(results)).containsExactly("o1", "o2");
        assertThat(innerResults).containsEntry("o1", List.of("o1.c1", "o1.c2")).containsEntry("o2",
                List.of("o2.c1", "o2.c2"));
        assertThat(permits.availablePermits()).isEqualTo(2);
    }

    @Test
    @DisplayName("the options reach the delegate, for the whole batch and for each non-dedicated segment")
    void optionsReachTheDelegate() {
        final DispatchOptions deep = DispatchOptions.builder().forkDepth(1).build();
        final RecordingDelegate bypass = new RecordingDelegate();
        dispatcher(bypass, 4).dispatch(List.of(use("t1", TASK)), registry(), deep,
                tu -> ToolUseResult.success(tu.getId(), "ok"), null, null);
        assertThat(bypass.options).containsExactly(deep);

        final DispatchOptions root = DispatchOptions.root();
        final RecordingDelegate segments = new RecordingDelegate();
        dispatcher(segments, 4).dispatch(List.of(use("r1", READ), use("t1", TASK), use("g1", GREP)), registry(), root,
                tu -> ToolUseResult.success(tu.getId(), "ok"), null, null);
        assertThat(segments.options).containsExactly(root, root);
    }

    @Test
    @DisplayName("an already-interrupted caller starts no thread; the segment runs sequentially and skips")
    void interruptedOnEntryUsesTheSequentialPath() throws Exception {
        final RecordingDelegate delegate = new RecordingDelegate();
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        final AtomicReference<List<ToolUseResult>> results = new AtomicReference<>();
        final AtomicBoolean flagAfter = new AtomicBoolean();
        final AtomicReference<Thread> callerRef = new AtomicReference<>();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK));

        final Thread caller = new Thread(() -> {
            callerRef.set(Thread.currentThread());
            Thread.currentThread().interrupt();
            // Models the executor's runner: an interrupted execution skips the call and leaves the flag set.
            results.set(dispatcher(delegate, 4).dispatch(batch, registry(), tu -> {
                threads.add(Thread.currentThread());
                return Thread.currentThread().isInterrupted()
                        ? ToolUseResult.error(tu.getId(), "skipped")
                        : ToolUseResult.success(tu.getId(), "ran");
            }, null, null));
            flagAfter.set(Thread.interrupted());
        });
        caller.start();
        caller.join(5_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(delegate.calls).singleElement().satisfies(call -> assertThat(call).isEqualTo(batch));
        assertThat(threads).containsExactly(callerRef.get());
        assertThat(results.get()).extracting(ToolUseResult::getContent).containsExactly("skipped", "skipped");
        assertThat(flagAfter).isTrue();
    }

    @Test
    @DisplayName("a caller interrupted while it runs an inline call forwards the interrupt and restores its flag")
    void callerInterruptedDuringInlineCall() throws Exception {
        final DedicatedThreadConfig config = DedicatedThreadConfig.enabled(1);
        final DedicatedThreadPermits permits = DedicatedThreadPermits.of(config);
        final CountDownLatch bothRunning = new CountDownLatch(2);
        final Map<String, Boolean> sawInterrupt = new ConcurrentHashMap<>();
        final AtomicReference<List<ToolUseResult>> results = new AtomicReference<>();
        final AtomicBoolean flagAfter = new AtomicBoolean();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK));

        final Thread caller = new Thread(() -> {
            results.set(new DedicatedThreadDispatcher(new RecordingDelegate(), config, permits).dispatch(batch,
                    registry(), tu -> {
                        bothRunning.countDown();
                        try {
                            Thread.sleep(10_000);
                            sawInterrupt.put(tu.getId(), false);
                        } catch (InterruptedException e) {
                            sawInterrupt.put(tu.getId(), true);
                            // Like the executor's runner, leave the flag for the loop checkpoint.
                            Thread.currentThread().interrupt();
                        }
                        return ToolUseResult.success(tu.getId(), "ok");
                    }, null, null));
            flagAfter.set(Thread.interrupted());
        });
        caller.start();
        assertThat(bothRunning.await(5, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(5_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(sawInterrupt).containsEntry("t1", true).containsEntry("t2", true);
        assertThat(ids(results.get())).containsExactly("t1", "t2");
        assertThat(flagAfter).isTrue();
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("a forwarded interrupt skips a call whose runner already returned (it never hits onCompleted)")
    void forwardedInterruptSkipsReturnedRunner() throws Exception {
        final CountDownLatch t1InCompleted = new CountDownLatch(1);
        final CountDownLatch t2Running = new CountDownLatch(1);
        final CountDownLatch t2Interrupted = new CountDownLatch(1);
        final CountDownLatch releaseT1 = new CountDownLatch(1);
        final AtomicBoolean t1ListenerInterrupted = new AtomicBoolean();
        final AtomicBoolean flagAfter = new AtomicBoolean();
        final List<ToolUse> batch = List.of(use("t1", TASK), use("t2", TASK));

        final Thread caller = new Thread(() -> {
            dispatcher(new RecordingDelegate(), 4).dispatch(batch, registry(), tu -> {
                if (tu.getId().equals("t2")) {
                    t2Running.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        t2Interrupted.countDown();
                    }
                }
                return ToolUseResult.success(tu.getId(), "ok");
            }, null, (tu, r) -> {
                if (!tu.getId().equals("t1")) {
                    return;
                }
                t1InCompleted.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        releaseT1.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                t1ListenerInterrupted.set(interrupted || Thread.interrupted());
            });
            flagAfter.set(Thread.interrupted());
        });
        caller.start();
        assertThat(t1InCompleted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(t2Running.await(5, TimeUnit.SECONDS)).isTrue();
        // The caller is joining t1 (still in its listener); its interrupt is forwarded slot by slot, t1 before t2.
        caller.interrupt();
        assertThat(t2Interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        releaseT1.countDown();
        caller.join(5_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(t1ListenerInterrupted).isFalse();
        assertThat(flagAfter).isTrue();
    }

    // ---- delegation ---------------------------------------------------------

    @Test
    @DisplayName("close() closes an AutoCloseable delegate and leaves any other delegate alone")
    void closeDelegates() throws Exception {
        final RecordingDelegate closeable = new RecordingDelegate();
        dispatcher(closeable, 1).close();
        assertThat(closeable.closed).isTrue();

        final List<String> touched = Collections.synchronizedList(new ArrayList<>());
        final ParallelToolDispatcher plain = (toolUses, registry, runner, onStarted, onCompleted) -> {
            touched.add("dispatch");
            return List.of();
        };
        dispatcher(plain, 1).close();
        assertThat(touched).isEmpty();
    }

    @Test
    @DisplayName("routes the harvest tail; eager methods mirror the delegate")
    void routesHarvestTailAndMirrorsEager() {
        final DefaultParallelToolDispatcher pooled = new DefaultParallelToolDispatcher(
                ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(3).streamingOverlap(true).build());
        try {
            final DedicatedThreadDispatcher dispatcher = dispatcher(pooled, 1);
            assertThat(dispatcher.routesHarvestTail()).isTrue();
            assertThat(pooled.routesHarvestTail()).isFalse();
            assertThat(dispatcher.supportsEagerDispatch()).isTrue();
            assertThat(dispatcher.eagerPermits()).isEqualTo(3);
            assertThat(dispatcher.isEagerEligible(use("r1", READ), registry())).isTrue();
            assertThat(dispatcher.isEagerEligible(use("t1", TASK), registry())).isFalse();
            final Optional<?> eager = dispatcher.submitEager(use("r1", READ),
                    tu -> ToolUseResult.success(tu.getId(), "ok"));
            assertThat(eager).isPresent();
        } finally {
            pooled.close();
        }
    }
}
