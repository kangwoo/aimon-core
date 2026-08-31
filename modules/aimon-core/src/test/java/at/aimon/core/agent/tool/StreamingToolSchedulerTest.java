package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Tests for {@link StreamingToolScheduler} (streaming-tool overlap, design §7).
 *
 * <p>
 * Exercised against a real {@link DefaultParallelToolDispatcher} so the eager submit path, the two-tier permit bound,
 * and the CONCURRENT_SAFE + parallelisable-interrupt eligibility gate are covered end-to-end rather than mocked.
 */
@DisplayName("StreamingToolScheduler Tests")
class StreamingToolSchedulerTest {

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

    private static DefaultToolRegistry registryWith(Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool t : tools) {
            registry.register(t);
        }
        return registry;
    }

    private static ToolConcurrencyConfig overlapConfig(int maxConcurrency) {
        return ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(maxConcurrency).streamingOverlap(true)
                .build();
    }

    private static ToolConcurrencyConfig overlapConfig(int maxConcurrency, int perBatchMax) {
        return ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(maxConcurrency).perBatchMax(perBatchMax)
                .streamingOverlap(true).build();
    }

    /** Runner that records each invoked tool-use id and echoes a success result. */
    private static final class RecordingRunner implements Function<ToolUse, ToolUseResult> {
        final List<String> invoked = new CopyOnWriteArrayList<>();

        @Override
        public ToolUseResult apply(ToolUse tu) {
            invoked.add(tu.getId());
            return ToolUseResult.success(tu.getId(), "ran:" + tu.getName());
        }
    }

    // ---- happy path ---------------------------------------------------------

    @Nested
    @DisplayName("Eager dispatch")
    class EagerDispatch {

        @Test
        @DisplayName("an all-safe prefix is eager-dispatched and joinable, in id-keyed harvest")
        void allSafeEagerlyDispatched() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("B", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.NON_INTERRUPTIBLE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(4))) {
                final RecordingRunner runner = new RecordingRunner();
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A"));
                scheduler.onToolUseReady(use("2", "B"));

                assertThat(scheduler.hasAnyEager()).isTrue();
                assertThat(scheduler.hasEager("1")).isTrue();
                assertThat(scheduler.hasEager("2")).isTrue();

                assertThat(scheduler.joinEager(use("1", "A")).getContent()).isEqualTo("ran:A");
                assertThat(scheduler.joinEager(use("2", "B")).getContent()).isEqualTo("ran:B");
                assertThat(runner.invoked).containsExactlyInAnyOrder("1", "2");
            }
        }

        @Test
        @DisplayName("eager execution actually starts before harvest (overlaps the stream)")
        void eagerExecutionStartsBeforeJoin() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(2))) {
                final CountDownLatch started = new CountDownLatch(1);
                final Function<ToolUse, ToolUseResult> runner = tu -> {
                    started.countDown();
                    return ToolUseResult.success(tu.getId(), "ok");
                };
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A"));

                // The tool runs on a pool thread the moment its block is ready — before we ever call joinEager. If it
                // only ran at harvest this await would time out.
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(scheduler.joinEager(use("1", "A")).isSuccess()).isTrue();
            }
        }
    }

    // ---- prefix safety ------------------------------------------------------

    @Nested
    @DisplayName("Prefix safety (poisoning)")
    class PrefixSafety {

        @Test
        @DisplayName("first ineligible tool poisons the rest: it and every later tool are deferred to harvest")
        void firstUnsafePoisonsRest() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("W", ConcurrencyBehavior.SEQUENTIAL, InterruptBehavior.NON_INTERRUPTIBLE),
                    tool("C", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(4))) {
                final RecordingRunner runner = new RecordingRunner();
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A")); // safe -> eager
                scheduler.onToolUseReady(use("2", "W")); // SEQUENTIAL -> poisons
                scheduler.onToolUseReady(use("3", "C")); // after poison -> ignored

                assertThat(scheduler.hasEager("1")).isTrue();
                assertThat(scheduler.hasEager("2")).isFalse();
                assertThat(scheduler.hasEager("3")).isFalse();
                assertThat(scheduler.joinEager(use("1", "A")).isSuccess()).isTrue();
                // Only the safe prefix ever ran eagerly; W and C are left for the harvest path.
                assertThat(runner.invoked).containsExactly("1");
            }
        }

        @Test
        @DisplayName("a THREAD_INTERRUPT tool (even if CONCURRENT_SAFE) poisons: it is not parallelisable")
        void threadInterruptPoisons() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.THREAD_INTERRUPT));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(4))) {
                final RecordingRunner runner = new RecordingRunner();
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A"));

                assertThat(scheduler.hasAnyEager()).isFalse();
                assertThat(runner.invoked).isEmpty();
            }
        }

        @Test
        @DisplayName("an unregistered (hallucinated) tool name poisons")
        void unregisteredNamePoisons() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(4))) {
                final RecordingRunner runner = new RecordingRunner();
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "Ghost"));

                assertThat(scheduler.hasAnyEager()).isFalse();
            }
        }

        @Test
        @DisplayName("overlap-off dispatcher never dispatches eagerly (degrades to no-op)")
        void overlapOffNoEager() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            // enabled but streamingOverlap=false -> supportsEagerDispatch()==false -> isEagerEligible()==false.
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                    ToolConcurrencyConfig.enabled(4))) {
                final RecordingRunner runner = new RecordingRunner();
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A"));

                assertThat(scheduler.hasAnyEager()).isFalse();
                assertThat(runner.invoked).isEmpty();
            }
        }
    }

    // ---- retry / cancel / reset lifecycle -----------------------------------

    @Nested
    @DisplayName("Lifecycle (retry / cancel / reset)")
    class Lifecycle {

        @Test
        @DisplayName("disableForRetry drops eager state and keeps eager off until reset")
        void disableForRetryStopsEager() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("B", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(4))) {
                final RecordingRunner runner = new RecordingRunner();
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A"));
                scheduler.disableForRetry();

                assertThat(scheduler.hasAnyEager()).isFalse();
                assertThat(scheduler.hasEager("1")).isFalse();

                // A tool_use arriving after the retry-disable is ignored (eager stays off for the rest of the call).
                scheduler.onToolUseReady(use("2", "B"));
                assertThat(scheduler.hasAnyEager()).isFalse();
            }
        }

        @Test
        @DisplayName("resetForNewAttempt re-arms eager dispatch for a fresh attempt")
        void resetReArms() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(4))) {
                final RecordingRunner runner = new RecordingRunner();
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A"));
                scheduler.disableForRetry();
                assertThat(scheduler.hasAnyEager()).isFalse();

                scheduler.resetForNewAttempt();
                scheduler.onToolUseReady(use("2", "A"));

                assertThat(scheduler.hasAnyEager()).isTrue();
                assertThat(scheduler.hasEager("2")).isTrue();
                assertThat(scheduler.joinEager(use("2", "A")).isSuccess()).isTrue();
            }
        }

        @Test
        @DisplayName("resetForNewAttempt clears a prior poison so the new attempt can dispatch again")
        void resetClearsPoison() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("W", ConcurrencyBehavior.SEQUENTIAL, InterruptBehavior.NON_INTERRUPTIBLE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(4))) {
                final RecordingRunner runner = new RecordingRunner();
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "W")); // poisons immediately
                assertThat(scheduler.hasAnyEager()).isFalse();

                scheduler.resetForNewAttempt();
                scheduler.onToolUseReady(use("2", "A")); // fresh attempt: safe -> eager again

                assertThat(scheduler.hasEager("2")).isTrue();
            }
        }

        @Test
        @DisplayName("cancelAll discards eager state without harvesting")
        void cancelAllDiscards() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(4))) {
                final RecordingRunner runner = new RecordingRunner();
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A"));
                scheduler.cancelAll();

                assertThat(scheduler.hasAnyEager()).isFalse();
                assertThat(scheduler.hasEager("1")).isFalse();
            }
        }
    }

    // ---- harvest failure isolation ------------------------------------------

    @Nested
    @DisplayName("Harvest failure isolation")
    class HarvestFailure {

        @Test
        @DisplayName("joinEager isolates a throwing runner into an error result (never throws)")
        void joinEagerIsolatesThrow() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(2))) {
                final Function<ToolUse, ToolUseResult> runner = tu -> {
                    throw new RuntimeException("boom");
                };
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A"));

                final ToolUseResult result = scheduler.joinEager(use("1", "A"));
                assertThat(result.isError()).isTrue();
                assertThat(result.getToolUseId()).isEqualTo("1");
                assertThat(result.getContent()).contains("boom");
            }
        }

        @Test
        @DisplayName("joinEager on an unknown tool id throws IllegalStateException (caller must gate on hasEager)")
        void joinEagerUnknownThrows() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(2))) {
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry,
                        new RecordingRunner());

                assertThatThrownBy(() -> scheduler.joinEager(use("nope", "A")))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    // ---- permit backpressure (two-tier bound) -------------------------------

    @Nested
    @DisplayName("Permit backpressure")
    class PermitBackpressure {

        @Test
        @DisplayName("perBatchMax bounds in-flight eager tools; the stream thread backpressures on a full pool")
        void perBatchMaxBoundsInFlight() throws Exception {
            final DefaultToolRegistry registry = registryWith(
                    tool("A", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE),
                    tool("B", ConcurrencyBehavior.CONCURRENT_SAFE, InterruptBehavior.COOPERATIVE));
            // maxConcurrency high enough that perBatchMax=1 (not the pool) is the binding limit.
            try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(4, 1))) {
                final AtomicInteger invocations = new AtomicInteger();
                final CountDownLatch aRunning = new CountDownLatch(1);
                final CountDownLatch aRelease = new CountDownLatch(1);
                final Function<ToolUse, ToolUseResult> runner = tu -> {
                    invocations.incrementAndGet();
                    if ("1".equals(tu.getId())) {
                        aRunning.countDown();
                        try {
                            aRelease.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return ToolUseResult.success(tu.getId(), "ok");
                };
                final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, registry, runner);

                scheduler.onToolUseReady(use("1", "A")); // acquires the only permit; A blocks on aRelease
                assertThat(aRunning.await(5, TimeUnit.SECONDS)).isTrue();

                // Submit B on another thread: it must block on the permit until A releases, so it cannot run yet.
                final Thread t = new Thread(() -> scheduler.onToolUseReady(use("2", "B")), "submit-B");
                t.start();

                // While A holds the sole permit, only A has been invoked — B is backpressured on the stream thread.
                Thread.sleep(200);
                assertThat(invocations.get()).isEqualTo(1);
                assertThat(scheduler.hasEager("2")).isFalse();

                aRelease.countDown(); // A completes -> releases permit -> B proceeds
                t.join(5_000);

                assertThat(scheduler.hasEager("2")).isTrue();
                assertThat(scheduler.joinEager(use("1", "A")).isSuccess()).isTrue();
                assertThat(scheduler.joinEager(use("2", "B")).isSuccess()).isTrue();
                assertThat(invocations.get()).isEqualTo(2);
            }
        }
    }

    // ---- validation ---------------------------------------------------------

    @Test
    @DisplayName("constructor rejects null dependencies")
    void constructorRejectsNulls() {
        try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(2))) {
            final DefaultToolRegistry registry = new DefaultToolRegistry();
            final RecordingRunner runner = new RecordingRunner();
            assertThatThrownBy(() -> new StreamingToolScheduler(null, registry, runner))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new StreamingToolScheduler(dispatcher, null, runner))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new StreamingToolScheduler(dispatcher, registry, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    @DisplayName("onToolUseReady rejects a null tool use")
    void onToolUseReadyRejectsNull() {
        try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(overlapConfig(2))) {
            final StreamingToolScheduler scheduler = new StreamingToolScheduler(dispatcher, new DefaultToolRegistry(),
                    new RecordingRunner());
            assertThatThrownBy(() -> scheduler.onToolUseReady(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
