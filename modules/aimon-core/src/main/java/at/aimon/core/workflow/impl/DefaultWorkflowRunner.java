package at.aimon.core.workflow.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.workflow.RunHandle;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.RunQuery;
import at.aimon.core.workflow.RunStore;
import at.aimon.core.workflow.StepResultCache;
import at.aimon.core.workflow.WorkflowBackgroundConfig;
import at.aimon.core.workflow.WorkflowBudget;
import at.aimon.core.workflow.WorkflowConcurrencyConfig;
import at.aimon.core.workflow.WorkflowEventSink;
import at.aimon.core.workflow.WorkflowRun;
import at.aimon.core.workflow.WorkflowRunState;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowScript;
import at.aimon.core.workflow.WorktreeEnvironmentFactory;

/**
 * Default {@link WorkflowRunner}. Application-scoped: constructed once at bootstrap with borrowed collaborators (a
 * {@link SubagentExecutionManager} and a base {@link SubagentExecutionEnvironment}) which it never closes.
 *
 * <p>
 * Both foreground {@link #run(WorkflowScript, RunId)} and background {@link #runInBackground(WorkflowScript, RunId)}
 * fan out onto a single <b>runner-owned shared</b> {@link BoundedFanoutDispatcher} (design §5.2): its fixed pool bounds
 * the aggregate fan-out concurrency across all concurrent runs, while each {@code parallel}/{@code pipeline} call is
 * additionally capped at {@code perBatchMax}, so overlapping runs share the pool rather than each spawning their own
 * (avoiding thread explosion). It is physically separate from the run-hosting pool: a background run's script
 * body occupies one run-hosting worker while its fan-out uses the shared pool's workers.
 *
 * <p>
 * A background run clones the subagent {@code executeInBackground} lifecycle at run granularity: a durable PENDING
 * {@code RunStore} record up front, then the script body on the run-hosting pool, a {@code whenComplete} finalizer that
 * records the terminal state and releases per-run resources, and a pool-rejection path that settles the run FAILED.
 * Each background run gets a <b>per-run environment</b> whose cancellation signal is its own coordinator's, so
 * {@link #stop(RunId)} reaches the run's in-flight subagents (design §5.1). {@link #close()} shuts down the
 * runner-owned pools (shared fan-out + run-hosting); after close the fan-out dispatcher degrades to sequential, so
 * {@code run()} stays usable.
 */
public final class DefaultWorkflowRunner implements WorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowRunner.class);
    /** Store-claim re-attempts on re-submit — kept small so a shared (remote) RunStore sees few round-trips. */
    private static final int MAX_RESUBMIT_STORE_ATTEMPTS = 4;
    /** Node-local registry spins between store attempts, absorbing the tiny claim-vs-register window. */
    private static final int RESUBMIT_REGISTRY_SPINS = 256;

    private final SubagentExecutionManager manager;
    private final SubagentExecutionEnvironment baseEnv;
    private final WorkflowConcurrencyConfig concurrency;
    private final WorkflowEventSink eventSink;
    private final WorkflowBudget budget;
    private final StepResultCache stepResultCache;
    private final RunStore runStore;
    private final BoundedFanoutDispatcher fanout;
    private final ContextExecutionOptions executionOptions;
    private final ExecutorService runHostingExecutor;
    private final Duration shutdownDrain;
    private final RunningRunRegistry runningRuns = new RunningRunRegistry();

    /**
     * Creates a runner with default concurrency, no event sink, the default budget, no resume cache, an in-memory run
     * store, and the default background pool.
     *
     * @param manager
     *            the borrowed subagent execution manager (must not be null)
     * @param baseEnv
     *            the borrowed base execution environment (must not be null)
     */
    public DefaultWorkflowRunner(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv) {
        this(builder(manager, baseEnv));
    }

    /**
     * Creates a foreground-configured runner (default run store + background pool).
     *
     * @param manager
     *            the borrowed subagent execution manager (must not be null)
     * @param baseEnv
     *            the borrowed base execution environment (must not be null)
     * @param concurrency
     *            the fan-out concurrency configuration (must not be null)
     * @param eventSink
     *            the progress sink; null is treated as {@link WorkflowEventSink#NO_OP}
     * @param budget
     *            the run-scoped agent-count/token backstops (must not be null)
     */
    public DefaultWorkflowRunner(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv,
            WorkflowConcurrencyConfig concurrency, WorkflowEventSink eventSink, WorkflowBudget budget) {
        this(builder(manager, baseEnv).concurrency(Objects.requireNonNull(concurrency, "concurrency cannot be null"))
                .eventSink(eventSink).budget(Objects.requireNonNull(budget, "budget cannot be null")));
    }

    /**
     * Creates a runner with a resume cache (default run store + background pool).
     *
     * @param manager
     *            the borrowed subagent execution manager (must not be null)
     * @param baseEnv
     *            the borrowed base execution environment (must not be null)
     * @param concurrency
     *            the fan-out concurrency configuration (must not be null)
     * @param eventSink
     *            the progress sink; null is treated as {@link WorkflowEventSink#NO_OP}
     * @param budget
     *            the run-scoped agent-count/token backstops (must not be null)
     * @param stepResultCache
     *            the resume step cache; null is treated as {@link StepResultCache#NO_OP} (no resume)
     */
    public DefaultWorkflowRunner(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv,
            WorkflowConcurrencyConfig concurrency, WorkflowEventSink eventSink, WorkflowBudget budget,
            StepResultCache stepResultCache) {
        this(builder(manager, baseEnv).concurrency(Objects.requireNonNull(concurrency, "concurrency cannot be null"))
                .eventSink(eventSink).budget(Objects.requireNonNull(budget, "budget cannot be null"))
                .stepResultCache(stepResultCache));
    }

    private DefaultWorkflowRunner(Builder b) {
        this.manager = Objects.requireNonNull(b.manager, "manager cannot be null");
        this.baseEnv = Objects.requireNonNull(b.baseEnv, "baseEnv cannot be null");
        this.concurrency = b.concurrency != null ? b.concurrency : WorkflowConcurrencyConfig.defaults();
        this.eventSink = b.eventSink != null ? b.eventSink : WorkflowEventSink.NO_OP;
        this.budget = b.budget != null ? b.budget : WorkflowBudget.defaults();
        this.stepResultCache = b.stepResultCache != null ? b.stepResultCache : StepResultCache.NO_OP;
        this.runStore = b.runStore != null ? b.runStore : new InMemoryRunStore();
        final WorkflowBackgroundConfig bg = b.backgroundConfig != null
                ? b.backgroundConfig
                : WorkflowBackgroundConfig.defaults();
        // The shared fan-out pool is fair — a single run's fan-out may not seize every worker. Derive the
        // per-batch cap from how many runs may host concurrently (sequential / single-worker configs pass through
        // unchanged; an explicit perBatchMax >= maxConcurrency is rejected).
        this.fanout = new BoundedFanoutDispatcher(this.concurrency.forSharedPool(bg.getMaxConcurrentRuns()));
        // The global leaf ceiling and the nesting depth are sourced from the ORIGINAL config (not the forSharedPool
        // derivation, which only re-derives perBatchMax), so they never silently revert (§6.2). worktreeFactory
        // is nullable — isolation is unavailable when unset (fails loud).
        this.executionOptions = new ContextExecutionOptions(
                new LeafConcurrencyLimiter(this.concurrency.getMaxConcurrency()), b.worktreeFactory,
                this.concurrency.getMaxNestingDepth());
        this.runHostingExecutor = newRunHostingExecutor(bg);
        this.shutdownDrain = bg.getShutdownDrain();
    }

    /**
     * @param manager
     *            the borrowed subagent execution manager (must not be null)
     * @param baseEnv
     *            the borrowed base execution environment (must not be null)
     * @return a new builder for a fully configured runner (run store, background pool, resume cache, ...)
     */
    public static Builder builder(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv) {
        return new Builder(manager, baseEnv);
    }

    @Override
    public <T> T run(WorkflowScript<T> script, RunId runId) {
        Objects.requireNonNull(script, "script cannot be null");
        Objects.requireNonNull(runId, "runId cannot be null");
        // Fan out onto the shared runner-owned pool. Foreground runs carry no owning agent-context yet (null
        // agentRuntimeId); background runs use baseEnv's context. The shared DEFAULT_RUN_ID is documented as ephemeral
        // ("no meaningful resume"), so it must never touch the step cache — otherwise every no-arg run() of every
        // script would share one key space (same id, null context) and could replay each other's outcomes.
        final StepResultCache cache = DEFAULT_RUN_ID.equals(runId) ? StepResultCache.NO_OP : stepResultCache;
        // Per-run coordinator for the foreground path too: a run-fatal abort must trip a signal the run's
        // in-flight fan-out branches observe, and the borrowed baseEnv's app-wide signal must never be tripped by one
        // run's failure. The cascade registration keeps an app-wide stop reaching this run's subagents.
        final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        final CancellationSignal.Registration parentReg = baseEnv.getCancellationSignal()
                .onCancel(() -> coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED));
        final SubagentExecutionEnvironment perRunEnv = baseEnv.toBuilder().cancellationSignal(coordinator.getSignal())
                .build();
        final DefaultWorkflowContext ctx = new DefaultWorkflowContext(manager, perRunEnv, fanout, eventSink, budget,
                new ResumeBinding(runId, null, cache), executionOptions);
        try {
            return script.run(ctx);
        } finally {
            ctx.clearRootFrame();
            // Trip unconditionally BEFORE closing (a harmless no-op for a fully-joined run): besides the plain
            // run-fatal throw, a script that CATCHES a fan-out abort and returns normally leaves in-flight orphan
            // leaves behind — without this trip they would hold a closed, forever-untrippable signal, unreachable
            // even by an app-wide stop once parentReg is removed.
            coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED);
            coordinator.close();
            parentReg.remove();
        }
    }

    @Override
    public <T> RunHandle<T> runInBackground(WorkflowScript<T> script, RunId runId) {
        Objects.requireNonNull(script, "script cannot be null");
        Objects.requireNonNull(runId, "runId cannot be null");
        // The RunStore is the authoritative idempotency gate: putIfAbsentOrTerminal atomically claims the id when
        // it is absent or terminal, so at most one submit dispatches per non-terminal window. A losing submit returns
        // the existing live handle. finalizeRun writes the store terminal state BEFORE removing the registry entry, so
        // a claim can momentarily precede the loser's find() observing the entry (or precede the winner registering
        // it); that tiny same-node window is retried rather than throwing. The retry is two-level so a shared (remote)
        // store is not hammered: the store claim is re-attempted only a handful of times, while the cheap node-local
        // registry lookup absorbs the spinning in between. Only a genuine cross-node owner (a shared store with no
        // local entry) exhausts the attempts and surfaces a clear error.
        final WorkflowRun pending = WorkflowRun.pending(runId, runId.scriptName(), baseEnv.getPrincipal().orElse(null),
                baseEnv.getAgentRuntimeId(), Instant.now());
        for (int attempts = 0; attempts < MAX_RESUBMIT_STORE_ATTEMPTS; attempts++) {
            if (runStore.putIfAbsentOrTerminal(pending)) {
                return dispatch(script, runId);
            }
            for (int spins = 0; spins < RESUBMIT_REGISTRY_SPINS; spins++) {
                final Optional<RunningRunRegistry.Entry> existing = runningRuns.find(runId);
                if (existing.isPresent()) {
                    return castHandle(existing.get());
                }
                Thread.onSpinWait();
            }
        }
        throw new IllegalStateException("run " + runId + " is active but is not hosted on this node");
    }

    private <T> RunHandle<T> dispatch(WorkflowScript<T> script, RunId runId) {
        // Per-run coordinator + environment: the run's fan-out subagents observe THIS run's cancellation signal.
        final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        final SubagentExecutionEnvironment perRunEnv = baseEnv.toBuilder().cancellationSignal(coordinator.getSignal())
                .build();
        final RunControl control = new RunControl(coordinator, null);
        final CompletableFuture<T> future = new CompletableFuture<>();
        final RunHandle<T> handle = new RunHandle<>(runId, future);
        final RunningRunRegistry.Entry entry = runningRuns.register(runId, control, handle, future);
        // Single cleanup point: the finalizer records the terminal state and releases per-run resources exactly once,
        // removing only THIS entry (value-checked) so a stale finalizer cannot clobber a re-submit's newer entry.
        future.whenComplete((result, error) -> finalizeRun(runId, entry, control, error));

        // Defense in depth: confine the run's cache loads to its owning context, so on a shared/persistent backend
        // one agent can never replay another's outcomes. NO_OP is left unwrapped to preserve the no-resume fast path.
        final StepResultCache runCache = stepResultCache == StepResultCache.NO_OP
                ? stepResultCache
                : ScopedStepResultCache.scopeOrPassThrough(stepResultCache,
                        Optional.ofNullable(perRunEnv.getAgentRuntimeId()));

        final Runnable body = () -> {
            // Stopped while still queued: settle KILLED without ever starting the script body — the documented
            // PENDING -> KILLED transition. The queued-phase interrupt was never delivered (no worker was attached),
            // so the stop flag is the only carrier of that request.
            if (control.isStopRequested()) {
                future.completeExceptionally(new CancellationException("run " + runId + " stopped before start"));
                return;
            }
            control.attachWorker(Thread.currentThread());
            runStore.transition(runId, WorkflowRunState.RUNNING);
            // Fan out onto the shared runner-owned pool (separate from this run-hosting worker).
            final DefaultWorkflowContext ctx = new DefaultWorkflowContext(manager, perRunEnv, fanout, eventSink, budget,
                    new ResumeBinding(runId, perRunEnv.getAgentRuntimeId(), runCache), executionOptions);
            try {
                future.complete(script.run(ctx));
            } catch (Throwable t) {
                // Trip the per-run signal BEFORE settling the future: the finalizer closes the coordinator (making
                // later trips no-ops), and without the trip the run's in-flight fan-out branches would keep executing
                // — unstoppably, since the finalizer also removes the registry entry — for a run already recorded
                // FAILED (§6.2). The failure-trip is distinguished from a stop so the state stays FAILED.
                control.tripOnFailure();
                future.completeExceptionally(t);
            } finally {
                ctx.clearRootFrame();
            }
        };
        try {
            runHostingExecutor.execute(body);
        } catch (RejectedExecutionException rex) {
            // Hosting pool saturated (or shut down): settle FAILED via the finalizer (single cleanup point), no thread.
            log.warn("Background run '{}' rejected: hosting pool saturated ({})", runId, rex.getMessage());
            future.completeExceptionally(rex);
        }
        return handle;
    }

    private void finalizeRun(RunId runId, RunningRunRegistry.Entry entry, RunControl control, Throwable error) {
        try {
            final WorkflowRunState finalState;
            if (error == null) {
                // A normal completion wins even over a concurrently arriving stop request: the script already returned
                // its result, so recording KILLED would contradict the value the handle observably delivers.
                finalState = WorkflowRunState.COMPLETED;
            } else if (control.isStopRequested() || (control.signal().isCancelled() && !control.isFailureTrip())) {
                // KILLED only for an external stop (direct or parent-cascade). A failure-trip also cancels the signal
                // (orphan cleanup) but the run failed on its own — that must stay FAILED.
                finalState = WorkflowRunState.KILLED;
            } else {
                finalState = WorkflowRunState.FAILED;
            }
            runStore.transition(runId, finalState);
        } finally {
            control.close();
            runningRuns.remove(runId, entry);
        }
    }

    /**
     * Casts a live entry's handle to the caller's expected result type. Unchecked by necessity: the registry stores
     * handles untyped, and a re-submit is presumed to carry the same script (and thus the same {@code T}) as the live
     * run it joins — re-submitting a live id with a differently-typed script surfaces as a {@code ClassCastException}
     * at the caller's {@code await}/{@code future} use site (documented on
     * {@code WorkflowRunner#runInBackground}).
     */
    @SuppressWarnings("unchecked")
    private static <T> RunHandle<T> castHandle(RunningRunRegistry.Entry entry) {
        return (RunHandle<T>) entry.handle();
    }

    @Override
    public boolean stop(RunId runId) {
        Objects.requireNonNull(runId, "runId cannot be null");
        return runningRuns.find(runId).map(e -> {
            e.control().requestStop();
            return true;
        }).orElse(false);
    }

    @Override
    public List<WorkflowRun> list(RunQuery query) {
        return runStore.list(query);
    }

    @Override
    public Optional<WorkflowRun> status(RunId runId) {
        Objects.requireNonNull(runId, "runId cannot be null");
        return runStore.find(runId);
    }

    @Override
    public void close() {
        // Shut down ONLY the runner-owned pools (run-hosting + shared fan-out). Borrowed manager/baseEnv are never
        // closed. Drain the hosting pool first (stop starting new script bodies), then close the shared fan-out
        // dispatcher — which is idempotent and thereafter degrades dispatch to sequential, so foreground run() stays
        // usable after close().
        runHostingExecutor.shutdown();
        try {
            if (!runHostingExecutor.awaitTermination(shutdownDrain.toMillis(), TimeUnit.MILLISECONDS)) {
                runHostingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            runHostingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        settleUnfinishedRuns();
        fanout.close();
    }

    /**
     * Settles any run whose future is still incomplete after the hosting pool shut down — a queued body dropped by
     * {@code shutdownNow()} would otherwise never complete, leaving the store record PENDING forever and every awaiter
     * of its handle blocked. Completing the runner's own future fires the normal finalizer (terminal store state +
     * registry removal), so a dropped run settles exactly like a failed one. Runs that finished in the drain window
     * have already been removed by their finalizer, making this a no-op for them.
     */
    private void settleUnfinishedRuns() {
        for (final Map.Entry<RunId, RunningRunRegistry.Entry> live : runningRuns.snapshot()) {
            if (!live.getValue().future().isDone()) {
                live.getValue().future().completeExceptionally(new IllegalStateException(
                        "run " + live.getKey() + " was abandoned: the workflow runner was closed"));
            }
        }
    }

    private static ExecutorService newRunHostingExecutor(WorkflowBackgroundConfig config) {
        final int n = config.getMaxConcurrentRuns();
        return new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(config.getQueueCapacity()), hostingThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory hostingThreadFactory() {
        final AtomicLong counter = new AtomicLong();
        return runnable -> {
            final Thread t = new Thread(runnable, "workflow-run-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Builder for a fully configured {@link DefaultWorkflowRunner}. */
    public static final class Builder {
        private final SubagentExecutionManager manager;
        private final SubagentExecutionEnvironment baseEnv;
        private WorkflowConcurrencyConfig concurrency;
        private WorkflowEventSink eventSink;
        private WorkflowBudget budget;
        private StepResultCache stepResultCache;
        private RunStore runStore;
        private WorkflowBackgroundConfig backgroundConfig;
        private WorktreeEnvironmentFactory worktreeFactory;

        private Builder(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv) {
            this.manager = manager;
            this.baseEnv = baseEnv;
        }

        /** Sets the fan-out concurrency config (default {@link WorkflowConcurrencyConfig#defaults()}). */
        public Builder concurrency(WorkflowConcurrencyConfig concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        /** Sets the progress sink (default {@link WorkflowEventSink#NO_OP}). */
        public Builder eventSink(WorkflowEventSink eventSink) {
            this.eventSink = eventSink;
            return this;
        }

        /** Sets the run-scoped backstops (default {@link WorkflowBudget#defaults()}). */
        public Builder budget(WorkflowBudget budget) {
            this.budget = budget;
            return this;
        }

        /** Sets the resume step cache (default {@link StepResultCache#NO_OP}). */
        public Builder stepResultCache(StepResultCache stepResultCache) {
            this.stepResultCache = stepResultCache;
            return this;
        }

        /** Sets the run store for background run metadata (default {@code InMemoryRunStore}). */
        public Builder runStore(RunStore runStore) {
            this.runStore = runStore;
            return this;
        }

        /** Sets the run-hosting pool config (default {@link WorkflowBackgroundConfig#defaults()}). */
        public Builder backgroundConfig(WorkflowBackgroundConfig backgroundConfig) {
            this.backgroundConfig = backgroundConfig;
            return this;
        }

        /**
         * Sets the caller-injected worktree environment factory (design §6.3). When unset, {@code isolate=true} tasks
         * are run-fatal. Nullable.
         */
        public Builder worktreeFactory(WorktreeEnvironmentFactory worktreeFactory) {
            this.worktreeFactory = worktreeFactory;
            return this;
        }

        /**
         * @return a new runner
         */
        public DefaultWorkflowRunner build() {
            return new DefaultWorkflowRunner(this);
        }
    }
}
