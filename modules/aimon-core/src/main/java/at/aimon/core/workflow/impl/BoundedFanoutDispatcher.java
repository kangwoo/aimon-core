package at.aimon.core.workflow.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.workflow.WorkflowConcurrencyConfig;
import at.aimon.core.workflow.exception.WorkflowException;

/**
 * Bounded fan-out/join engine for workflow {@code parallel} / {@code pipeline}.
 *
 * <p>
 * This is a generic, tool-domain-free adaptation of the four type-agnostic pieces of
 * {@code DefaultParallelToolDispatcher}: (1) a lazily-created, {@code poolLock}-guarded daemon worker pool — an
 * <em>unbounded cached</em> pool (design §6.2), so a nested fan-out reached from a pool worker always finds a
 * fresh thread — that is never resurrected after {@link #close()}; (2) a daemon {@link ThreadFactory}; (3) a two-tier
 * thread bound — the runner-shared reserve-before-flip guard capped at
 * {@link WorkflowConcurrencyConfig#getMaxLiveFanoutThreads() maxLiveFanoutThreads} plus a stack-local
 * {@link Semaphore} of {@link WorkflowConcurrencyConfig#getPerBatchMax() perBatchMax} permits acquired on the
 * <em>calling</em> thread before each submit (so a task waiting for a slot never pins a shared worker); and (4)
 * input-order positional reassembly with two-layer failure isolation. Note that
 * {@link WorkflowConcurrencyConfig#getMaxConcurrency() maxConcurrency} does <b>not</b> size this pool — it sizes
 * the runner's {@link LeafConcurrencyLimiter}, the authoritative ceiling on concurrent LLM calls. The tool-domain
 * safety gate ({@code shouldParallelize}) and the eager-streaming surface are intentionally dropped — whether to
 * parallelise is the workflow operator's intent ({@code parallel} vs {@code pipeline}), not a per-item registry
 * policy.
 *
 * <p>
 * <b>Run-fatal carve-out (deviation from the source):</b> the source isolates <em>every</em> runner failure into an
 * error result. This engine instead re-throws any {@link WorkflowException} (a framework-level control signal,
 * e.g.
 * the agent-count backstop) out of {@link #dispatch} rather than substituting it via {@code onError}, so a run-fatal
 * condition is not silently swallowed on the fan-out path. Ordinary execution failures (any other throwable, or a
 * {@code null} return) are still isolated into {@code onError.apply(item, throwable)}.
 *
 * <p>
 * The type parameters live on {@link #dispatch} rather than the class, so one instance handles every {@code <I, R>}
 * combination a run produces without an unchecked cast.
 *
 * <p>
 * Thread-safety: safe to share across concurrent calls. Pool creation is guarded; result reassembly and the per-batch
 * semaphore are per-call local state.
 */
public final class BoundedFanoutDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BoundedFanoutDispatcher.class);

    private static final int DRAIN_TIMEOUT_SECONDS = 30;

    /** Poll interval for the signal-checking join, so {@code stop(runId)} unblocks a nested join within one tick. */
    private static final long JOIN_POLL_MILLIS = 50L;

    private final WorkflowConcurrencyConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object poolLock = new Object();

    /**
     * Runner-shared count of reserved fan-out worker slots (design §6.2). The absolute thread guard: a
     * {@code dispatch} reserves {@code min(perBatchMax, size)} slots up front; if the reservation would exceed
     * {@code maxLiveFanoutThreads} it rolls back and runs sequentially, so the unbounded cached pool cannot grow to
     * {@code perBatchMax^maxNestingDepth} live threads. Shared across every run that shares this dispatcher, so one
     * deep run's reservation can degrade a concurrent run's dispatch (documented coupling).
     */
    private final AtomicInteger reservedThreads = new AtomicInteger();

    private volatile ExecutorService pool;

    /**
     * @param config
     *            the concurrency configuration (must not be null)
     */
    public BoundedFanoutDispatcher(WorkflowConcurrencyConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * @return a dispatcher that always runs tasks sequentially (no pool ever created).
     */
    public static BoundedFanoutDispatcher sequential() {
        return new BoundedFanoutDispatcher(WorkflowConcurrencyConfig.disabled());
    }

    /**
     * Runs every item through {@code runner} and returns the results reassembled in <b>input order</b> (regardless of
     * completion order).
     *
     * <p>
     * A runner that throws an ordinary exception or returns {@code null} is isolated: the corresponding result becomes
     * {@code onError.apply(item, throwable)} and the batch continues. A runner that throws an
     * {@link WorkflowException} is <b>not</b> isolated — it is re-thrown out of this method to abort the run
     * (run-fatal carve-out). When the config is disabled, or fewer than two items are given, or the dispatcher is
     * closed, all items run sequentially on the calling thread.
     *
     * @param items
     *            the items to process (must not be null)
     * @param runner
     *            the per-item work (must not be null); must be safe to call from a worker thread
     * @param onError
     *            maps an isolated (item, throwable) to a substitute result (must not be null; may return {@code null})
     * @param <I>
     *            the item type
     * @param <R>
     *            the result type
     * @return the results in input order (never null)
     * @throws WorkflowException
     *             if a runner raises a run-fatal control signal
     */
    public <I, R> List<R> dispatch(List<I> items, Function<I, R> runner, BiFunction<I, Throwable, R> onError) {
        return dispatch(items, runner, onError, NoopCancellationSignal.INSTANCE);
    }

    /**
     * Runs every item through {@code runner} and returns the results reassembled in input order, threading the run's
     * {@code signal} into the join so a {@code stop(runId)} unblocks a nested join within one poll interval. See
     * {@link #dispatch(List, Function, BiFunction)} for the isolation / run-fatal / sequential-fallback semantics.
     *
     * @param items
     *            the items to process (must not be null)
     * @param runner
     *            the per-item work (must not be null)
     * @param onError
     *            maps an isolated (item, throwable) to a substitute result (must not be null; may return {@code null})
     * @param signal
     *            the run's cancellation signal, polled by the join (must not be null)
     * @param <I>
     *            the item type
     * @param <R>
     *            the result type
     * @return the results in input order (never null)
     */
    public <I, R> List<R> dispatch(List<I> items, Function<I, R> runner, BiFunction<I, Throwable, R> onError,
            CancellationSignal signal) {
        Objects.requireNonNull(items, "items cannot be null");
        Objects.requireNonNull(runner, "runner cannot be null");
        Objects.requireNonNull(onError, "onError cannot be null");
        Objects.requireNonNull(signal, "signal cannot be null");

        if (closed.get() || !config.isEnabled() || items.size() < 2) {
            return dispatchSequential(items, runner, onError);
        }
        // Absolute thread guard (reserve-before-flip, §6.2): reserve this batch's worst-case concurrent worker
        // count up front. If the reservation would exceed maxLiveFanoutThreads, roll it back and run sequentially on
        // the
        // calling thread — bounding the nested cached-pool footprint without a fixed-pool worker-starvation deadlock.
        final int reserve = Math.min(config.getPerBatchMax(), items.size());
        final int now = reservedThreads.addAndGet(reserve);
        if (now > config.getMaxLiveFanoutThreads()) {
            reservedThreads.addAndGet(-reserve);
            log.warn("Fan-out live-thread guard tripped (reserved would be {} > cap {}); running {} item(s) "
                    + "sequentially", now, config.getMaxLiveFanoutThreads(), items.size());
            return dispatchSequential(items, runner, onError);
        }
        try {
            return dispatchParallel(items, runner, onError, signal);
        } finally {
            reservedThreads.addAndGet(-reserve);
        }
    }

    private <I, R> List<R> dispatchSequential(List<I> items, Function<I, R> runner,
            BiFunction<I, Throwable, R> onError) {
        final List<R> results = new ArrayList<>(items.size());
        for (final I item : items) {
            results.add(runSafely(runner, item, onError));
        }
        return results;
    }

    private <I, R> List<R> dispatchParallel(List<I> items, Function<I, R> runner, BiFunction<I, Throwable, R> onError,
            CancellationSignal signal) {
        final ExecutorService executor = poolOrInit();
        if (executor == null) {
            // A concurrent close() won the race; run sequentially rather than resurrect a pool close() already drained.
            return dispatchSequential(items, runner, onError);
        }
        final int size = items.size();
        final List<CompletableFuture<R>> futures = new ArrayList<>(size);
        final List<R> results = new ArrayList<>(size);

        // Per-batch fairness cap (two-tier bound). Stack-local: one Semaphore per dispatch() call. Permits are acquired
        // on the calling thread BEFORE submission so a task waiting for a per-batch slot never pins a shared worker.
        final Semaphore batchPermits = new Semaphore(config.getPerBatchMax());

        for (int idx = 0; idx < size; idx++) {
            final I current = items.get(idx);
            batchPermits.acquireUninterruptibly();
            try {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return runSafely(runner, current, onError);
                    } finally {
                        batchPermits.release();
                    }
                }, executor));
            } catch (RejectedExecutionException e) {
                // A concurrent close() shut the pool mid-batch. The permit was not handed to a task, so release it;
                // then run this item plus the remaining tail sequentially on the calling thread. Never throw a
                // RejectedExecutionException out of dispatch() and never leak a permit.
                batchPermits.release();
                log.warn("Fan-out pool rejected a task mid-batch (closed concurrently); running the remaining {} "
                        + "item(s) sequentially", size - idx);
                joinInto(results, futures, items, onError, signal);
                results.add(runSafely(runner, current, onError));
                for (int j = idx + 1; j < size; j++) {
                    results.add(runSafely(runner, items.get(j), onError));
                }
                return results;
            }
        }

        // Happy path: reassemble in input order regardless of completion order.
        joinInto(results, futures, items, onError, signal);
        return results;
    }

    /**
     * Joins each future in input order, appending its result; isolates per-task failures via {@code onError}. On a
     * run-fatal {@link WorkflowException} it best-effort cancels the not-yet-joined tail before propagating —
     * which only prevents <em>un-started</em> tasks from running ({@code CompletableFuture.cancel} never interrupts an
     * in-flight supplier, and on the cached pool most tasks start immediately). Actually stopping in-flight orphan
     * branches is the runner's job: the run-fatal path trips the per-run {@link CancellationSignal} before the run
     * settles, so orphan leaves observe it at their next checkpoint and stop cooperatively (§6.2).
     */
    private <I, R> void joinInto(List<R> results, List<CompletableFuture<R>> futures, List<I> items,
            BiFunction<I, Throwable, R> onError, CancellationSignal signal) {
        for (int i = 0; i < futures.size(); i++) {
            final R result;
            try {
                result = joinSafely(futures.get(i), items.get(i), onError, signal);
            } catch (WorkflowException e) {
                for (int j = i + 1; j < futures.size(); j++) {
                    futures.get(j).cancel(true);
                }
                throw e;
            }
            results.add(result);
        }
    }

    /**
     * Returns the worker pool, creating it lazily on first use. Returns {@code null} if the dispatcher has been
     * {@link #close() closed} — the {@code closed} flag is re-checked <em>inside</em> {@code poolLock} so a pool is
     * never resurrected after {@code close()} has captured and drained the live reference.
     */
    private ExecutorService poolOrInit() {
        final ExecutorService existing = pool;
        if (existing != null) {
            return existing;
        }
        synchronized (poolLock) {
            if (closed.get()) {
                return null;
            }
            if (pool == null) {
                // Unbounded cached pool (design §6.2): a nested fan-out reached from a pool worker always finds a
                // fresh thread, so a fixed-pool worker-starvation deadlock is structurally impossible.
                // Thread count is instead bounded by the reserve-before-flip guard (reservedThreads).
                pool = Executors.newCachedThreadPool(new DaemonFanoutThreadFactory());
                log.debug("Initialized workflow cached fan-out pool (maxLiveFanoutThreads={})",
                        config.getMaxLiveFanoutThreads());
            }
            return pool;
        }
    }

    private <I, R> R runSafely(Function<I, R> runner, I item, BiFunction<I, Throwable, R> onError) {
        try {
            final R result = runner.apply(item);
            if (result != null) {
                return result;
            }
            log.warn("Fan-out runner returned null; substituting an error result");
            return onError.apply(item, new IllegalStateException("runner returned no result"));
        } catch (WorkflowException e) {
            // Run-fatal control signal (e.g. the agent-count backstop). Never isolate — propagate to abort the run.
            throw e;
        } catch (Exception e) {
            log.warn("Fan-out runner threw; substituting an error result: {}", e.getMessage(), e);
            return onError.apply(item, e);
        }
    }

    /**
     * Joins a single future with a bounded, signal-polling {@code get} loop: a tripped run
     * {@link CancellationSignal} unblocks the join within one poll interval — {@code future.join()} was
     * non-interruptible and could not observe a stop on a nested worker thread. Preserves the run-fatal carve-out
     * (unwrap and re-throw {@link WorkflowException}) and isolates every other failure via {@code onError}.
     *
     * <p>
     * A thread interrupt alone does <b>not</b> abandon the join when a real signal is threaded: a run stop always
     * trips the signal (and is honoured immediately on either the timeout or the interrupt branch), whereas an
     * unrelated interrupt of the joining thread (e.g. a host-level timeout) would otherwise cascade {@code onError}
     * across the whole remaining batch while the work keeps running. The join keeps polling and re-asserts the
     * interrupt flag on exit so the caller still observes it. The exception is the no-signal overload
     * ({@link NoopCancellationSignal}): with no trippable stop lever, the interrupt is the only cancellation
     * mechanism, so it abandons the position as before.
     */
    private <I, R> R joinSafely(CompletableFuture<R> future, I item, BiFunction<I, Throwable, R> onError,
            CancellationSignal signal) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get(JOIN_POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (signal.isCancelled()) {
                        // Cooperative stop: abandon the wait and isolate this position. The leaf observes the same
                        // signal and stops cooperatively; future.cancel does not interrupt an already-running leaf.
                        future.cancel(true);
                        return onError.apply(item, new CancellationException("run cancelled"));
                    }
                    // Not cancelled — keep polling.
                } catch (ExecutionException e) {
                    final Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof WorkflowException workflowException) {
                        // Run-fatal control signal surfaced from a worker; propagate unwrapped to abort the run.
                        throw workflowException;
                    }
                    log.warn("Fan-out task failed; substituting an error result: {}", cause.getMessage(), cause);
                    return onError.apply(item, cause);
                } catch (CancellationException e) {
                    return onError.apply(item, e);
                } catch (InterruptedException e) {
                    if (signal.isCancelled()) {
                        // A stop raced the interrupt: same cooperative-stop substitution as the timeout branch.
                        interrupted = true;
                        future.cancel(true);
                        return onError.apply(item, new CancellationException("run cancelled"));
                    }
                    if (signal == NoopCancellationSignal.INSTANCE) {
                        // No trippable stop lever exists for this dispatch (the no-signal overload): the interrupt is
                        // the only cancellation mechanism there is, so honour it — abandon this position rather than
                        // poll a future no stop can ever unblock.
                        interrupted = true;
                        future.cancel(true);
                        return onError.apply(item, e);
                    }
                    if (!interrupted) {
                        log.warn("Fan-out join interrupted without a run stop; continuing to wait (interrupt flag "
                                + "restored on exit)");
                    }
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * @return {@code true} once {@link #close()} has been initiated. Package-private: lifecycle introspection / tests.
     */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * Shuts the worker pool down, draining in-flight tasks for up to {@value #DRAIN_TIMEOUT_SECONDS} seconds before
     * forcing termination. Idempotent. After close, {@link #dispatch} falls back to sequential execution.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final ExecutorService existing;
        synchronized (poolLock) {
            existing = pool;
            pool = null;
        }
        if (existing == null) {
            return;
        }
        existing.shutdown();
        try {
            if (!existing.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Workflow fan-out pool drain timed out after {}s; forcing shutdown", DRAIN_TIMEOUT_SECONDS);
                existing.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            existing.shutdownNow();
        }
    }

    /** Daemon thread factory so the pool never blocks JVM shutdown; named for diagnostics. */
    private static final class DaemonFanoutThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            final Thread thread = new Thread(r, "workflow-fanout-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
