package at.aimon.core.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Default {@link ParallelToolDispatcher} backed by a bounded, lazily-created daemon thread pool.
 *
 * <p>
 * The pool is created on first use of the parallel path and only when {@link ToolConcurrencyConfig#isEnabled()} is
 * {@code true}; a dispatcher built with {@link ToolConcurrencyConfig#disabled()} (the default) never allocates a pool
 * and always runs sequentially, so it is free of resource and behavioural cost. Worker threads are daemons, so an
 * un-{@link #close() closed} dispatcher cannot keep the JVM alive; {@link #close()} is provided for deterministic
 * shutdown when an owner's lifecycle calls for it.
 *
 * <p>
 * Lifecycle note: the pool is intended to be executor-scoped (shared across the turns of one agent executor). Each
 * {@link #dispatch} call joins all of its tasks before returning, so worker activity never outlives the turn that
 * submitted it — the pool must not be coupled to the per-turn interrupt coordinator.
 *
 * <p>
 * Two-tier bound: the shared pool size ({@link ToolConcurrencyConfig#getMaxConcurrency()}) is the global
 * host-protection
 * ceiling across all concurrent turns. On top of it, each {@link #dispatch} call applies a stack-local
 * {@link Semaphore} of {@link ToolConcurrencyConfig#getPerBatchMax()} permits so that no single batch occupies more
 * than
 * its fair share of the shared pool. Permits are acquired on the <em>calling</em> thread before a task is submitted, so
 * a tool waiting for a per-batch slot never pins a shared worker thread (and thus never starves a concurrent turn).
 * When {@code perBatchMax == maxConcurrency} (the default) and a batch fits within the bound the semaphore never blocks
 * and behaviour matches a single-tier pool; a batch larger than the bound throttles submission on the calling thread
 * (backpressure) without exceeding the bound.
 *
 * <p>
 * Thread-safety: this class is safe to share across concurrent turns. Pool creation is guarded; result reassembly and
 * the per-batch semaphore are per-call local state.
 */
public final class DefaultParallelToolDispatcher implements ParallelToolDispatcher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultParallelToolDispatcher.class);

    private static final int DRAIN_TIMEOUT_SECONDS = 30;

    private final ToolConcurrencyConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object poolLock = new Object();

    private volatile ExecutorService pool;

    /**
     * @param config
     *            the concurrency configuration (must not be null)
     */
    public DefaultParallelToolDispatcher(ToolConcurrencyConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * @return a dispatcher that always runs tools sequentially (no pool ever created). Convenient default for callers
     *         that have not opted into parallel execution.
     */
    public static DefaultParallelToolDispatcher sequential() {
        return new DefaultParallelToolDispatcher(ToolConcurrencyConfig.disabled());
    }

    @Override
    public List<ToolUseResult> dispatch(List<ToolUse> toolUses, ToolRegistry registry,
            Function<ToolUse, ToolUseResult> runner, Consumer<ToolUse> onStarted,
            BiConsumer<ToolUse, ToolUseResult> onCompleted) {
        Objects.requireNonNull(toolUses, "toolUses cannot be null");
        Objects.requireNonNull(runner, "runner cannot be null");

        if (closed.get() || !shouldParallelize(toolUses, registry, config)) {
            return dispatchSequential(toolUses, runner, onStarted, onCompleted);
        }
        return dispatchParallel(toolUses, runner, onStarted, onCompleted);
    }

    private List<ToolUseResult> dispatchSequential(List<ToolUse> toolUses, Function<ToolUse, ToolUseResult> runner,
            Consumer<ToolUse> onStarted, BiConsumer<ToolUse, ToolUseResult> onCompleted) {
        final List<ToolUseResult> results = new ArrayList<>(toolUses.size());
        for (ToolUse toolUse : toolUses) {
            safeOnStarted(onStarted, toolUse);
            final ToolUseResult result = runSafely(runner, toolUse);
            safeOnCompleted(onCompleted, toolUse, result);
            results.add(result);
        }
        return results;
    }

    private List<ToolUseResult> dispatchParallel(List<ToolUse> toolUses, Function<ToolUse, ToolUseResult> runner,
            Consumer<ToolUse> onStarted, BiConsumer<ToolUse, ToolUseResult> onCompleted) {
        final ExecutorService executor = poolOrInit();
        if (executor == null) {
            // A concurrent close() won the race; run sequentially rather than resurrect a pool close() already drained.
            return dispatchSequential(toolUses, runner, onStarted, onCompleted);
        }
        final int size = toolUses.size();
        final List<CompletableFuture<ToolUseResult>> futures = new ArrayList<>(size);
        final List<ToolUseResult> results = new ArrayList<>(size);

        // Per-batch fairness cap (two-tier bound). Stack-local: one Semaphore per dispatch() call, so it adds no shared
        // mutable state and no shutdown surface — it only bounds how many of THIS batch's tools occupy the shared pool
        // at once, while the pool size (maxConcurrency) stays the global host-protection ceiling. When
        // perBatchMax == maxConcurrency and the batch fits the pool the semaphore never blocks (behaviour matches a
        // single-tier pool); a larger batch throttles submission on the calling thread (backpressure).
        final Semaphore batchPermits = new Semaphore(config.getPerBatchMax());

        // Permits are acquired on the calling thread BEFORE submission so a tool waiting for a per-batch slot never
        // pins a shared worker thread. acquireUninterruptibly mirrors the uninterruptible join() below: tool-level
        // cancellation is the runner's concern (executeSingleTool + InterruptCoordinator), not the dispatch thread's.
        // Start events stay in input order because the calling thread acquires permits sequentially; under throttling
        // (perBatchMax < batch size) a tool's start event is simply deferred until a slot frees.
        for (int idx = 0; idx < size; idx++) {
            final ToolUse current = toolUses.get(idx);
            batchPermits.acquireUninterruptibly();
            safeOnStarted(onStarted, current);
            try {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        final ToolUseResult result = runSafely(runner, current);
                        safeOnCompleted(onCompleted, current, result);
                        return result;
                    } finally {
                        batchPermits.release();
                    }
                }, executor));
            } catch (RejectedExecutionException e) {
                // A concurrent close() shut the pool mid-batch (the entry guard at the top only covers a close() that
                // wins the poolOrInit() race). The permit was not handed to a task, so release it here; then run this
                // tool plus the remaining tail sequentially on the calling thread, mirroring the entry fallback. Never
                // throw out of dispatch() and never leak a permit. onStarted for `current` already fired above.
                batchPermits.release();
                log.warn("Parallel tool dispatch pool rejected a task mid-batch (closed concurrently); "
                        + "running the remaining {} tool(s) sequentially", size - idx);
                joinInto(results, futures, toolUses);
                results.add(runAndComplete(runner, onCompleted, current));
                for (int j = idx + 1; j < size; j++) {
                    final ToolUse tail = toolUses.get(j);
                    safeOnStarted(onStarted, tail);
                    results.add(runAndComplete(runner, onCompleted, tail));
                }
                return results;
            }
        }

        // Happy path: reassemble in input order regardless of completion order.
        joinInto(results, futures, toolUses);
        return results;
    }

    // --- Eager (incremental) dispatch: streaming-tool overlap (design §7) -------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>
     * Enabled only when parallel execution and the streaming-overlap opt-in are both set and the pool is open.
     */
    @Override
    public boolean supportsEagerDispatch() {
        return config.isEnabled() && config.isStreamingOverlap() && !closed.get();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Reuses {@link #isParallelizableInterrupt(InterruptBehavior)} — the identical Layer-2 predicate the whole-batch
     * gate applies — so eager eligibility never diverges from {@link #shouldParallelize}. Requires the
     * streaming-overlap
     * opt-in as well, so eager dispatch is a strict no-op unless an operator turned it on.
     */
    @Override
    public boolean isEagerEligible(ToolUse toolUse, ToolRegistry registry) {
        if (!supportsEagerDispatch() || registry == null || toolUse == null) {
            return false;
        }
        final Optional<Tool> tool = registry.findByName(toolUse.getName());
        if (tool.isEmpty()) {
            return false; // unregistered / hallucinated name → conservative: not eager
        }
        final Tool resolved = tool.get();
        return resolved.getConcurrencyBehavior() == ConcurrencyBehavior.CONCURRENT_SAFE
                && isParallelizableInterrupt(resolved.getInterruptBehavior());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Submits onto the same lazily-created worker pool that {@link #dispatch} uses, so eager and batch dispatch share
     * the one executor-scoped {@link ToolConcurrencyConfig#getMaxConcurrency() maxConcurrency} ceiling. Returns
     * {@link Optional#empty()} when a concurrent {@link #close()} has drained the pool, so the caller runs the tool on
     * the harvest path instead.
     */
    @Override
    public Optional<CompletableFuture<ToolUseResult>> submitEager(ToolUse toolUse,
            Function<ToolUse, ToolUseResult> runner) {
        Objects.requireNonNull(toolUse, "toolUse cannot be null");
        Objects.requireNonNull(runner, "runner cannot be null");
        if (closed.get() || !config.isEnabled()) {
            return Optional.empty();
        }
        final ExecutorService executor = poolOrInit();
        if (executor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(CompletableFuture.supplyAsync(() -> runSafely(runner, toolUse), executor));
        } catch (RejectedExecutionException e) {
            // A concurrent close() shut the pool between poolOrInit() and submission. Fall back to harvest-path
            // execution rather than throwing.
            log.warn("Eager tool dispatch rejected (pool closed concurrently) for tool_use {} ({}); "
                    + "caller will run it on the harvest path", toolUse.getId(), toolUse.getName());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The per-batch cap; the caller sizes its permit semaphore to this so a single streaming batch cannot occupy more
     * than its fair share of the shared pool (two-tier bound, mirroring {@link #dispatchParallel}).
     */
    @Override
    public int eagerPermits() {
        return config.getPerBatchMax();
    }

    /** Joins each future in input order, appending its result; isolates per-task failures into error results. */
    private void joinInto(List<ToolUseResult> results, List<CompletableFuture<ToolUseResult>> futures,
            List<ToolUse> toolUses) {
        for (int i = 0; i < futures.size(); i++) {
            results.add(joinSafely(futures.get(i), toolUses.get(i)));
        }
    }

    /** Runs a single tool on the calling thread and fires onCompleted; used by the sequential fallback paths. */
    private ToolUseResult runAndComplete(Function<ToolUse, ToolUseResult> runner,
            BiConsumer<ToolUse, ToolUseResult> onCompleted, ToolUse toolUse) {
        final ToolUseResult result = runSafely(runner, toolUse);
        safeOnCompleted(onCompleted, toolUse, result);
        return result;
    }

    /**
     * Returns the worker pool, creating it lazily on first use. Returns {@code null} if the dispatcher has been
     * {@link #close() closed} — the {@code closed} flag is re-checked <em>inside</em> {@code poolLock} so a pool is
     * never resurrected after {@code close()} has already captured and drained the live reference (which would leak the
     * new pool). Callers fall back to sequential execution on {@code null}.
     */
    private ExecutorService poolOrInit() {
        ExecutorService existing = pool;
        if (existing != null) {
            return existing;
        }
        synchronized (poolLock) {
            if (closed.get()) {
                return null;
            }
            if (pool == null) {
                pool = Executors.newFixedThreadPool(config.getMaxConcurrency(), new DaemonToolThreadFactory());
                log.debug("Initialized parallel tool dispatch pool (maxConcurrency={})", config.getMaxConcurrency());
            }
            return pool;
        }
    }

    /**
     * Two-stage safety gate: model intent (batch size &gt; 1, enabled) and framework safety (every tool resolves and is
     * concurrent-safe with a parallelisable interrupt behaviour). Package-private and static for direct unit testing.
     */
    static boolean shouldParallelize(List<ToolUse> toolUses, ToolRegistry registry, ToolConcurrencyConfig config) {
        if (!config.isEnabled() || registry == null || toolUses.size() < 2) {
            return false;
        }
        for (ToolUse toolUse : toolUses) {
            final Optional<Tool> tool = registry.findByName(toolUse.getName());
            if (tool.isEmpty()) {
                return false; // unregistered / hallucinated name → conservative: sequential
            }
            final Tool resolved = tool.get();
            if (resolved.getConcurrencyBehavior() != ConcurrencyBehavior.CONCURRENT_SAFE
                    || !isParallelizableInterrupt(resolved.getInterruptBehavior())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Only NON_INTERRUPTIBLE and COOPERATIVE tools are parallelised. THREAD_INTERRUPT/EXTERNALLY_TERMINATED tools
     * register a terminator bound to the executing thread, whose semantics are ambiguous on a shared worker thread, so
     * they are excluded from the parallel path.
     */
    private static boolean isParallelizableInterrupt(InterruptBehavior behavior) {
        return behavior == InterruptBehavior.NON_INTERRUPTIBLE || behavior == InterruptBehavior.COOPERATIVE;
    }

    private ToolUseResult runSafely(Function<ToolUse, ToolUseResult> runner, ToolUse toolUse) {
        try {
            final ToolUseResult result = runner.apply(toolUse);
            if (result != null) {
                return result;
            }
            log.error("Tool runner returned null for tool_use {} ({})", toolUse.getId(), toolUse.getName());
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: runner returned no result");
        } catch (Exception e) {
            // The executor's executeSingleTool already wraps tool failures, but a runner contract violation must not
            // escape and break the batch.
            log.error("Tool runner threw for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(), e.getMessage(),
                    e);
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: " + e.getMessage());
        }
    }

    private ToolUseResult joinSafely(CompletableFuture<ToolUseResult> future, ToolUse toolUse) {
        try {
            return future.join();
        } catch (CompletionException | CancellationException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Tool task failed for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(),
                    cause.getMessage(), cause);
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: " + cause.getMessage());
        }
    }

    private void safeOnStarted(Consumer<ToolUse> onStarted, ToolUse toolUse) {
        if (onStarted == null) {
            return;
        }
        try {
            onStarted.accept(toolUse);
        } catch (Exception e) {
            log.warn("onStarted listener threw for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(),
                    e.getMessage(), e);
        }
    }

    private void safeOnCompleted(BiConsumer<ToolUse, ToolUseResult> onCompleted, ToolUse toolUse,
            ToolUseResult result) {
        if (onCompleted == null) {
            return;
        }
        try {
            onCompleted.accept(toolUse, result);
        } catch (Exception e) {
            log.warn("onCompleted listener threw for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(),
                    e.getMessage(), e);
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
                log.warn("Parallel tool dispatch pool drain timed out after {}s; forcing shutdown",
                        DRAIN_TIMEOUT_SECONDS);
                existing.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            existing.shutdownNow();
        }
    }

    /** Daemon thread factory so the pool never blocks JVM shutdown; named for diagnostics. */
    private static final class DaemonToolThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            final Thread thread = new Thread(r, "tool-dispatch-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
