package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.workflow.WorkflowConcurrencyConfig;
import at.aimon.core.workflow.exception.WorkflowBudgetExceededException;
import at.aimon.core.workflow.exception.WorkflowException;

@DisplayName("BoundedFanoutDispatcher — input-order fan-out/join, per-batch cap, isolation, run-fatal carve-out, "
        + "live-thread guard, signal-polling join")
class BoundedFanoutDispatcherTest {

    /**
     * onError that never itself throws — matches the {@code parallel} contract of mapping isolated failures to null.
     */
    private static final BiFunction<Object, Throwable, Object> TO_NULL = (item, error) -> null;

    @Test
    @DisplayName("reassembles results in input order regardless of completion order")
    void reassemblesInInputOrder() {
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            final List<Integer> items = List.of(0, 1, 2, 3);
            // Earlier items sleep longer, so completion order is the reverse of input order.
            final Function<Integer, Integer> runner = i -> {
                sleep((4 - i) * 20L);
                return i * 10;
            };

            final List<Integer> results = dispatcher.dispatch(items, runner, (i, t) -> -1);

            assertThat(results).containsExactly(0, 10, 20, 30);
        }
    }

    @Test
    @DisplayName("per-batch cap bounds in-flight tasks to exactly perBatchMax (proves both the cap and parallelism)")
    void perBatchCapBoundsConcurrency() {
        final int perBatchMax = 2;
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(
                WorkflowConcurrencyConfig.enabled(4, perBatchMax))) {
            final List<Integer> items = IntStream.range(0, 6).boxed().toList();
            final AtomicInteger running = new AtomicInteger();
            final AtomicInteger maxObserved = new AtomicInteger();
            // A barrier sized to perBatchMax forces exactly that many tasks to overlap each wave: a broken cap that
            // lets fewer overlap times out (→ failure, not hang); one that lets more pushes maxObserved past
            // perBatchMax.
            final CyclicBarrier barrier = new CyclicBarrier(perBatchMax);
            final Function<Integer, Integer> runner = i -> {
                final int now = running.incrementAndGet();
                maxObserved.accumulateAndGet(now, Math::max);
                await(barrier);
                running.decrementAndGet();
                return i;
            };

            final List<Integer> results = dispatcher.dispatch(items, runner, (i, t) -> -1);

            assertThat(results).containsExactlyElementsOf(items);
            assertThat(maxObserved.get()).isEqualTo(perBatchMax);
        }
    }

    @Test
    @DisplayName("an ordinary runner exception is isolated into onError; the batch continues")
    void ordinaryExceptionIsIsolated() {
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            final List<Integer> items = List.of(0, 1, 2, 3);
            final Function<Integer, Integer> runner = i -> {
                if (i == 2) {
                    throw new RuntimeException("boom");
                }
                return i;
            };

            final List<Integer> results = dispatcher.dispatch(items, runner, (i, t) -> 1000 + i);

            assertThat(results).containsExactly(0, 1, 1002, 3);
        }
    }

    @Test
    @DisplayName("a null runner result is isolated into onError")
    void nullResultIsIsolated() {
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            final List<Integer> items = List.of(0, 1, 2, 3);
            final Function<Integer, Integer> runner = i -> i == 1 ? null : i;

            final List<Integer> results = dispatcher.dispatch(items, runner, (i, t) -> 999);

            assertThat(results).containsExactly(0, 999, 2, 3);
        }
    }

    @Test
    @DisplayName("a run-fatal WorkflowException is NOT isolated — it propagates out of the parallel path")
    void runFatalPropagatesFromParallelPath() {
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            final List<Integer> items = List.of(0, 1, 2, 3);
            final Function<Integer, Integer> runner = i -> {
                if (i == 2) {
                    throw new WorkflowBudgetExceededException("backstop tripped");
                }
                return i;
            };

            assertThatThrownBy(() -> dispatcher.dispatch(items, runner, toNull()))
                    .isInstanceOf(WorkflowBudgetExceededException.class).hasMessageContaining("backstop");
        }
    }

    @Test
    @DisplayName("a run-fatal WorkflowException propagates from the sequential path too")
    void runFatalPropagatesFromSequentialPath() {
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.disabled())) {
            final List<Integer> items = List.of(0, 1, 2);
            final Function<Integer, Integer> runner = i -> {
                if (i == 1) {
                    throw new WorkflowBudgetExceededException("backstop tripped");
                }
                return i;
            };

            assertThatThrownBy(() -> dispatcher.dispatch(items, runner, toNull()))
                    .isInstanceOf(WorkflowException.class);
        }
    }

    @Test
    @DisplayName("disabled config runs sequentially and still returns correct, in-order results")
    void disabledRunsSequentially() {
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.disabled())) {
            final List<Integer> results = dispatcher.dispatch(List.of(0, 1, 2, 3), i -> i * 2, (i, t) -> -1);

            assertThat(results).containsExactly(0, 2, 4, 6);
        }
    }

    @Test
    @DisplayName("a single item takes the sequential path (size < 2) and returns its result")
    void singleItemRunsSequentially() {
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            final List<Integer> results = dispatcher.dispatch(List.of(7), i -> i + 1, (i, t) -> -1);

            assertThat(results).containsExactly(8);
        }
    }

    @Test
    @DisplayName("close() is idempotent, and dispatch after close falls back to sequential (still correct)")
    void closeIsIdempotentAndFallsBackToSequential() {
        final BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4));

        dispatcher.close();
        dispatcher.close(); // idempotent — no throw

        assertThat(dispatcher.isClosed()).isTrue();
        final List<Integer> results = dispatcher.dispatch(List.of(0, 1, 2, 3), i -> i, (i, t) -> -1);
        assertThat(results).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("A nested dispatch over the live-thread cap degrades to sequential on its calling worker, "
            + "and the reservation is released so the next dispatch parallelises again")
    void liveThreadGuardDegradesNestedDispatchAndReleasesReservation() {
        // maxLiveFanoutThreads at its floor (== maxConcurrency): the outer dispatch's reservation (2) already fills
        // the cap, so any nested dispatch trips the reserve-before-flip guard and must roll back to sequential.
        final WorkflowConcurrencyConfig config = WorkflowConcurrencyConfig.builder().enabled(true).maxConcurrency(2)
                .perBatchMax(2).maxLiveFanoutThreads(2).build();
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(config)) {
            final Function<Integer, List<String>> outerRunner = i -> {
                final String callingWorker = Thread.currentThread().getName();
                final List<String> innerThreads = dispatcher.dispatch(List.of(0, 1),
                        j -> Thread.currentThread().getName(), (j, t) -> "inner-error");
                final List<String> observed = new ArrayList<>();
                observed.add(callingWorker);
                observed.addAll(innerThreads);
                return observed;
            };

            final List<List<String>> outerResults = dispatcher.dispatch(List.of(0, 1), outerRunner,
                    (i, t) -> List.of("outer-error"));

            assertThat(outerResults).hasSize(2);
            for (final List<String> observed : outerResults) {
                // [callingWorker, innerThread0, innerThread1] — the guard forced the nested dispatch onto the
                // calling worker thread (sequential), still input-ordered and error-free.
                assertThat(observed).hasSize(3).doesNotContain("inner-error", "outer-error");
                assertThat(observed.get(1)).isEqualTo(observed.get(0));
                assertThat(observed.get(2)).isEqualTo(observed.get(0));
            }

            // The outer reservation was released on completion, so a fresh dispatch parallelises again: the barrier
            // requires both tasks in flight at once (a still-held reservation would degrade to sequential and time
            // out into the onError substitute), and two overlapping tasks imply two distinct worker threads.
            final CyclicBarrier barrier = new CyclicBarrier(2);
            final List<String> secondThreads = dispatcher.dispatch(List.of(0, 1), i -> {
                await(barrier);
                return Thread.currentThread().getName();
            }, (i, t) -> "second-error");

            assertThat(secondThreads).hasSize(2).doesNotContain("second-error");
            assertThat(Set.copyOf(secondThreads)).hasSize(2);
        }
    }

    @Test
    @DisplayName("Tripping the run signal unblocks a join stuck behind a blocked task, substituting "
            + "onError(CancellationException) at that position while finished positions keep their results")
    void trippedSignalUnblocksJoinWithCancellationSubstitute() throws Exception {
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            // The gate proves all three tasks STARTED before the trip — it cannot prove items 0/2 finished, so their
            // positions may legitimately be substituted if the trip races their completion (50ms poll window). Only
            // the parked position 1 is deterministic; 0/2 are asserted tolerantly below.
            final CountDownLatch tripGate = new CountDownLatch(3);
            final CountDownLatch blocker = new CountDownLatch(1);
            final AtomicReference<Throwable> substituted = new AtomicReference<>();
            final Thread tripper = new Thread(() -> {
                awaitLatch(tripGate, 5);
                coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
            });
            tripper.start();
            try {
                final Function<Integer, Integer> runner = i -> {
                    tripGate.countDown();
                    if (i == 1) {
                        awaitLatch(blocker, 10);
                    }
                    return i;
                };

                final long startNanos = System.nanoTime();
                final List<Integer> results = dispatcher.dispatch(List.of(0, 1, 2), runner, (i, t) -> {
                    substituted.set(t);
                    return 1000 + i;
                }, coordinator.getSignal());
                final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                assertThat(results).hasSize(3);
                assertThat(results.get(0)).as("finished or substituted, never lost").isIn(0, 1000);
                assertThat(results.get(1)).as("the parked position is deterministically substituted").isEqualTo(1001);
                assertThat(results.get(2)).as("finished or substituted, never lost").isIn(2, 1002);
                assertThat(substituted.get()).isInstanceOf(CancellationException.class);
                assertThat(elapsedMillis).as("a tripped signal must unblock the join promptly").isLessThan(5_000L);
            } finally {
                blocker.countDown(); // release the parked worker so close() drains promptly
                tripper.join(TimeUnit.SECONDS.toMillis(5));
            }
        }
    }

    @Test
    @DisplayName("interrupt-immunity (trippable signal): interrupting the dispatching thread without a run stop "
            + "neither abandons the batch nor loses the interrupt flag")
    void interruptWithoutStopKeepsJoiningAndPreservesFlag() throws Exception {
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch gate = new CountDownLatch(1);
            final Thread dispatchingThread = Thread.currentThread();
            // Runners cannot finish before the gate opens, and the gate opens only after the dispatching thread has
            // been interrupted — so the interrupt is always pending while the batch is still in flight.
            final Thread interrupter = new Thread(() -> {
                awaitLatch(started, 5);
                dispatchingThread.interrupt();
                gate.countDown();
            });
            interrupter.start();

            List<Integer> results = null;
            final boolean flagPreserved;
            try {
                // A real (trippable, untripped) signal: immunity applies only there — a genuine stop arrives via the
                // signal, so an unrelated interrupt must not be treated as one.
                results = dispatcher.dispatch(List.of(0, 1, 2), i -> {
                    started.countDown();
                    awaitLatch(gate, 10);
                    return i * 7;
                }, toNull(), coordinator.getSignal());
            } finally {
                // Read-and-clear so a failure cannot leak the interrupt flag into later tests.
                flagPreserved = Thread.interrupted();
                interrupter.join(TimeUnit.SECONDS.toMillis(5));
            }

            assertThat(results).as("an unrelated interrupt must not cascade onError over the batch").containsExactly(0,
                    7, 14);
            assertThat(flagPreserved).as("the interrupt flag must be re-asserted on exit").isTrue();
        }
    }

    @Test
    @DisplayName("no-signal dispatch: an interrupt IS honoured as the only cancellation lever — the join abandons "
            + "remaining positions with substitutes and preserves the flag")
    void noopSignalInterruptAbandonsJoin() throws Exception {
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch gate = new CountDownLatch(1);
            final Thread dispatchingThread = Thread.currentThread();
            final Thread interrupter = new Thread(() -> {
                awaitLatch(started, 5);
                dispatchingThread.interrupt();
            });
            interrupter.start();

            List<Integer> results = null;
            final boolean flagPreserved;
            try {
                // The 3-arg overload threads NoopCancellationSignal: no stop can ever trip it, so polling through the
                // interrupt would wait on a future nothing can unblock — the interrupt must abandon instead.
                results = dispatcher.dispatch(List.of(0, 1, 2), i -> {
                    started.countDown();
                    awaitLatch(gate, 10);
                    return i * 7;
                }, toNull());
            } finally {
                flagPreserved = Thread.interrupted();
                gate.countDown(); // release the parked workers so close() drains promptly
                interrupter.join(TimeUnit.SECONDS.toMillis(5));
            }

            assertThat(results).as("every blocked position is substituted, none is lost").containsExactly(null, null,
                    null);
            assertThat(flagPreserved).as("the interrupt flag must be preserved").isTrue();
        }
    }

    @Test
    @DisplayName("a run-fatal WorkflowException propagates through the signal-threaded dispatch overload")
    void runFatalPropagatesThroughSignalThreadedOverload() {
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            final List<Integer> items = List.of(0, 1, 2, 3);
            final Function<Integer, Integer> runner = i -> {
                if (i == 2) {
                    throw new WorkflowBudgetExceededException("backstop tripped");
                }
                return i;
            };

            assertThatThrownBy(() -> dispatcher.dispatch(items, runner, toNull(), coordinator.getSignal()))
                    .isInstanceOf(WorkflowBudgetExceededException.class).hasMessageContaining("backstop");
        }
    }

    @Test
    @DisplayName("rejects null items / runner / onError / signal")
    void rejectsNullArguments() {
        try (BoundedFanoutDispatcher dispatcher = new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.enabled(4))) {
            assertThatNullPointerException().isThrownBy(() -> dispatcher.dispatch(null, i -> i, (i, t) -> null));
            assertThatNullPointerException().isThrownBy(() -> dispatcher.dispatch(List.of(1), null, (i, t) -> null));
            assertThatNullPointerException().isThrownBy(() -> dispatcher.dispatch(List.of(1), i -> i, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> dispatcher.dispatch(List.of(1), i -> i, (i, t) -> null, null));
        }
    }

    @SuppressWarnings("unchecked")
    private static <I, R> BiFunction<I, Throwable, R> toNull() {
        return (BiFunction<I, Throwable, R>) TO_NULL;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitLatch(CountDownLatch latch, int seconds) {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch not released within " + seconds + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
