package at.aimon.core.hook.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookExecutionPolicy.ExecutionMode;
import at.aimon.core.hook.execution.HookExecutionPolicy.TimeoutBehavior;
import at.aimon.core.hook.rewake.RewakeSpec;

/**
 * Default sequential hook executor.
 *
 * <p>
 * For {@link PreToolContext} hooks, accumulates {@link HookResult#getUpdatedInput()} across the chain — each hook sees
 * a
 * context whose {@code currentInput()} reflects mutations applied by previously run hooks. For {@link PostToolContext}
 * hooks the same is done with {@link HookResult#getUpdatedOutput()}.
 *
 * <p>
 * The final accumulated input/output is materialised on the last emitted {@link HookResult} (so callers can pull it via
 * {@code results.get(results.size()-1).getUpdatedInput()} / {@code getUpdatedOutput()}). When
 * {@link HookExecutionPolicy#stopOnBlocked()} is enabled and a hook returns {@link HookStatus#BLOCKED}, the executor
 * short-circuits and the BLOCKED result carries the accumulated updates from the hooks that ran before it.
 */
public class DefaultHookExecutor implements HookExecutor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultHookExecutor.class);

    /**
     * Daemon executor used to run individual hook invocations so that {@link Future#get(long, TimeUnit)} can enforce a
     * wall-clock deadline on the caller's thread without the caller itself hanging on a misbehaving hook.
     *
     * <p>
     * Exactly one task per hook is submitted here — in both SEQUENTIAL and PARALLEL mode. Pool threads never wait on
     * other pool tasks, so a bounded pool cannot deadlock; it only serialises the batch.
     */
    private final ExecutorService hookExecutor;

    /**
     * Whether {@link #hookExecutor} was created by this instance and is therefore ours to shut down.
     *
     * <p>
     * An injected pool is <b>borrowed</b>: its creator closes it, possibly long after this executor is gone. Shutting
     * it down from {@link #close()} would kill a pool other components are still submitting to.
     */
    private final boolean ownsPool;

    /** Default constructor: creates a cached daemon thread pool owned by this instance. */
    public DefaultHookExecutor() {
        this(Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "hook-executor");
            t.setDaemon(true);
            return t;
        }), true);
    }

    /**
     * Constructor allowing injection of a custom executor (e.g. for tests or shared pools).
     *
     * <p>
     * A bounded pool is safe (see {@link #hookExecutor}), but note that in PARALLEL mode a hook's timeout budget starts
     * at submission, so queue wait time counts against it. Size the pool to the expected batch width to avoid spurious
     * timeouts.
     *
     * <p>
     * The pool stays the caller's to close — {@link #close()} leaves an injected pool running.
     *
     * @param hookExecutor
     *            pool used to run hook bodies (must not be null)
     */
    public DefaultHookExecutor(ExecutorService hookExecutor) {
        this(hookExecutor, false);
    }

    private DefaultHookExecutor(ExecutorService hookExecutor, boolean ownsPool) {
        this.hookExecutor = Objects.requireNonNull(hookExecutor, "hookExecutor cannot be null");
        this.ownsPool = ownsPool;
    }

    /**
     * Shuts down the pool this executor created, and does nothing when the pool was injected.
     *
     * <p>
     * The threads are daemons and the default pool is cached, so an unclosed pool is a hygiene problem rather than a
     * leak — idle workers retire themselves after 60s and the JVM never waits on them. What this method buys is the
     * scope-model rule that the creator releases what it created (see {@code docs/overview/scope-model.md} §2), which
     * matters once a host application tears a whole stack down and rebuilds it: without it, every rebuild overlaps the
     * previous pool's retirement window instead of ending it.
     *
     * <p>
     * Deliberately does <b>not</b> wait for termination. Teardown runs late, when nothing should still be submitting,
     * and blocking here would add an unbounded phase to a shutdown sequence that documents which of its phases are
     * unbounded. A hook that arrives after this point is rejected, and {@link RejectedExecutionException} is already a
     * mapped outcome, exactly as for a saturated pool: {@link HookExecutionPolicy#onException(Exception)} decides it,
     * except for a hook that declares fail-closed, which is blocked. Neither waits. In an orderly teardown nothing
     * that fires a guard is left by the time the pool closes (turns end first), and the events a shutdown does fire —
     * {@code onStop}, {@code onSessionEnd} — are advisory and declare nothing, so they are not turned into blocks. A
     * tool call that arrives anyway, from an execution that outlived its drain, is refused rather than run unguarded.
     *
     * <p>
     * Idempotent: shutting an already-stopped pool down again is a no-op.
     */
    @Override
    public void close() {
        if (ownsPool) {
            hookExecutor.shutdownNow();
        }
    }

    @Override
    public <C extends HookContext> List<HookResult> execute(List<? extends ExecutionHook<C>> hooks, C context,
            HookExecutionPolicy policy) {
        Objects.requireNonNull(hooks, "hooks cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(policy, "policy cannot be null");

        final List<? extends ExecutionHook<C>> deduped = applyDedup(hooks, policy);

        if (policy.executionMode() == ExecutionMode.PARALLEL) {
            return executeParallel(deduped, context, policy);
        }
        return executeSequential(deduped, context, policy);
    }

    /**
     * Filters {@code hooks} so that, given a non-null dedup key, only the first occurrence of each key is retained.
     * Hooks whose extractor returns {@code null} or an empty string are passed through unchanged.
     */
    private static <C extends HookContext> List<? extends ExecutionHook<C>> applyDedup(
            List<? extends ExecutionHook<C>> hooks, HookExecutionPolicy policy) {
        final HookExecutionPolicy.DedupKeyExtractor extractor = policy.dedupKeyExtractor();
        if (extractor == null || hooks.isEmpty()) {
            return hooks;
        }
        final Set<String> seen = new LinkedHashSet<>();
        final List<ExecutionHook<C>> kept = new ArrayList<>(hooks.size());
        int dropped = 0;
        for (ExecutionHook<C> hook : hooks) {
            final String key = extractor.extract(hook);
            if (key == null || key.isEmpty() || seen.add(key)) {
                kept.add(hook);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.debug("Hook dedup dropped {} duplicate hook(s) (kept {} of {})", dropped, kept.size(), hooks.size());
        }
        return kept;
    }

    private <C extends HookContext> List<HookResult> executeSequential(List<? extends ExecutionHook<C>> hooks,
            C context, HookExecutionPolicy policy) {
        final List<HookResult> results = new ArrayList<>(hooks.size());
        C threadedContext = context;
        ToolInput accumulatedInput = null;
        ToolResult accumulatedOutput = null;

        for (ExecutionHook<C> hook : hooks) {
            HookResult result = invokeWithTimeout(hook, threadedContext, policy);

            if (result == null) {
                result = HookResult.success();
            }

            // Accumulate updated input / output and re-thread context for next hook.
            if (threadedContext instanceof PreToolContext preCtx && result.getUpdatedInput().isPresent()) {
                accumulatedInput = result.getUpdatedInput().get();
                @SuppressWarnings("unchecked")
                final C nextCtx = (C) preCtx.withCurrentInput(accumulatedInput);
                threadedContext = nextCtx;
            } else if (threadedContext instanceof PostToolContext postCtx && result.getUpdatedOutput().isPresent()) {
                accumulatedOutput = result.getUpdatedOutput().get();
                @SuppressWarnings("unchecked")
                final C nextCtx = (C) postCtx.withCurrentOutput(accumulatedOutput);
                threadedContext = nextCtx;
            }

            results.add(result);

            if (policy.stopOnBlocked() && result.isBlocked()) {
                break;
            }
        }

        applyAccumulatedToLast(results, accumulatedInput, accumulatedOutput);
        return results;
    }

    /**
     * Parallel execution path.
     *
     * <p>
     * All hooks are launched concurrently and observe the same starting context &mdash; {@code updatedInput} /
     * {@code updatedOutput} threading is intentionally <b>not</b> performed (parallel hooks have no defined order).
     * Each hook is timed-out independently per {@link HookExecutionPolicy#timeoutFor(ExecutionHook)} /
     * {@link HookExecutionPolicy#timeoutBehaviorFor(ExecutionHook)}.
     *
     * <p>
     * {@code stopOnBlocked} is a no-op in parallel mode &mdash; already-launched hooks cannot be cancelled mid-flight,
     * so the executor waits for all of them and returns each individual result in input order. Callers that need a
     * single combined view can collapse the list via {@link HookResult#merge(Iterable)}.
     *
     * <p>
     * <b>Pool discipline.</b> Only the hook bodies are submitted to {@link #hookExecutor} &mdash; exactly one pool task
     * per hook. Deadlines are enforced on the <i>calling</i> thread, so no pool thread ever blocks waiting for another
     * pool task. This is what makes a bounded / shared injected pool safe: an earlier design submitted the
     * timeout-wrapper <i>and</i> the hook body to the same pool, which deadlocked once the pool had fewer threads than
     * the batch had hooks.
     *
     * <p>
     * The per-hook budget is measured from submission, so with a bounded pool time spent waiting in the queue counts
     * against a hook's timeout. Size the pool to the expected batch width if that matters. Results are collected in
     * input order and an already-finished hook is never discarded for having outlived its budget while an earlier hook
     * was still being awaited &mdash; the deadline exists to bound waiting, not to throw away work that is done.
     */
    private <C extends HookContext> List<HookResult> executeParallel(List<? extends ExecutionHook<C>> hooks, C context,
            HookExecutionPolicy policy) {
        if (hooks.isEmpty()) {
            return List.of();
        }
        final long startNanos = System.nanoTime();
        final List<Future<HookResult>> futures = new ArrayList<>(hooks.size());
        final List<HookResult> rejected = new ArrayList<>(hooks.size());
        for (ExecutionHook<C> hook : hooks) {
            try {
                futures.add(hookExecutor.submit(() -> hook.execute(context)));
                rejected.add(null);
            } catch (RejectedExecutionException ree) {
                futures.add(null);
                rejected.add(onRejected(hook, policy, ree));
            }
        }
        final List<HookResult> results = new ArrayList<>(hooks.size());
        for (int i = 0; i < futures.size(); i++) {
            final Future<HookResult> f = futures.get(i);
            final ExecutionHook<C> hook = hooks.get(i);
            results.add(f == null
                    ? rejected.get(i)
                    : awaitHook(hook, f, policy, startNanos, toNanosSaturating(policy.timeoutFor(hook))));
        }
        return results;
    }

    /**
     * Invokes a single hook, enforcing the {@linkplain HookExecutionPolicy#timeoutFor(ExecutionHook) effective per-hook
     * timeout} — the policy's, widened when the hook declares a longer budget of its own.
     *
     * <p>
     * Exceptions inside the hook, and a pool that refuses it, are mapped through
     * {@link HookExecutionPolicy#onException(Exception)} — unless the hook declares fail-closed, in which case both
     * block ({@link #onThrown}, {@link #onRejected}). Timeouts are
     * mapped according to {@link HookExecutionPolicy#timeoutBehaviorFor(ExecutionHook)} — the hook's own declaration,
     * or the policy's {@link HookExecutionPolicy#timeoutBehavior()} when it made none: {@code FAIL_OPEN} returns
     * {@link HookResult#success()} (with a WARN log), {@code FAIL_CLOSED} returns a BLOCKED result with a descriptive
     * feedback (with an ERROR log). An interrupted wait is always BLOCKED ({@link #onInterrupted}), unless the hook
     * {@linkplain ExecutionHook#ignoresInterrupt() asks to be waited for}.
     */
    private <C extends HookContext> HookResult invokeWithTimeout(ExecutionHook<C> hook, C ctx,
            HookExecutionPolicy policy) {
        final long startNanos = System.nanoTime();
        final Future<HookResult> future;
        try {
            future = hookExecutor.submit(() -> hook.execute(ctx));
        } catch (RejectedExecutionException ree) {
            return onRejected(hook, policy, ree);
        }
        return awaitHook(hook, future, policy, startNanos, toNanosSaturating(policy.timeoutFor(hook)));
    }

    /**
     * Converts a duration to nanoseconds, saturating at {@link Long#MAX_VALUE} instead of throwing.
     *
     * <p>
     * {@link Duration#toNanos()} throws {@link ArithmeticException} for durations beyond ~292 years. The policy already
     * clamps a hook-declared budget to {@link HookExecutionPolicy#MAX_DECLARED_BUDGET}, but this conversion sits on the
     * one path where an escaping exception would bypass {@link HookExecutionPolicy#onException(Exception)} entirely and
     * surface out of {@code execute} — so it is made total here as well rather than relying on the caller.
     *
     * @param duration
     *            the duration to convert (must not be null)
     * @return the duration in nanoseconds, or {@link Long#MAX_VALUE} if it does not fit
     */
    private static long toNanosSaturating(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            log.warn("Hook timeout {} overflows nanosecond precision; saturating to Long.MAX_VALUE", duration);
            return Long.MAX_VALUE;
        }
    }

    /**
     * Waits for an already-submitted hook task until its budget expires, mapping each failure mode to a result.
     *
     * <p>
     * A failure <i>of the hook</i> goes through {@link #onThrown} ({@link HookExecutionPolicy#onException(Exception)}
     * unless the hook declares fail-closed); expiry goes through
     * {@link HookExecutionPolicy#timeoutBehaviorFor(ExecutionHook)}. An interrupt goes through neither — see
     * {@link #onInterrupted}
     * for why it is answered with BLOCKED regardless of the policy.
     *
     * <p>
     * A hook that {@linkplain ExecutionHook#ignoresInterrupt() ignores an interrupt} is not cancelled by one: the wait
     * goes on for what is left of the budget and ends in the hook's own result or the ordinary timeout, and the
     * thread's interrupt flag is set again on the way out — including when it was already set on the way in.
     *
     * <p>
     * The task is submitted via {@link ExecutorService#submit(java.util.concurrent.Callable)} rather than
     * {@link CompletableFuture#supplyAsync}, so {@link Future#cancel(boolean) cancel(true)} genuinely interrupts the
     * worker thread on timeout instead of merely completing the wrapper exceptionally and leaking the thread.
     *
     * @param startNanos
     *            the {@link System#nanoTime()} reading taken just before submission
     * @param budgetNanos
     *            the per-hook wall-clock budget measured from {@code startNanos}
     */
    private static <C extends HookContext> HookResult awaitHook(ExecutionHook<C> hook, Future<HookResult> future,
            HookExecutionPolicy policy, long startNanos, long budgetNanos) {
        boolean interrupted = false;
        try {
            while (true) {
                final long remainingNanos = Math.max(0L, budgetNanos - (System.nanoTime() - startNanos));
                try {
                    return awaitOnce(hook, future, policy, startNanos, budgetNanos, remainingNanos);
                } catch (InterruptedException ie) {
                    if (!hook.ignoresInterrupt()) {
                        // Re-arm before anything else: the caller's cancellation protocol has no other channel to
                        // ride on, and InterruptedException has already cleared the flag on the way out of Future#get.
                        Thread.currentThread().interrupt();
                        future.cancel(true);
                        return onInterrupted(hook);
                    }
                    // The hook asked to be waited for. InterruptedException cleared the flag, so the next wait blocks;
                    // the flag is put back below, once, whatever the wait ends in.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One bounded wait of {@link #awaitHook}; an interrupt is the caller's to answer. */
    private static <C extends HookContext> HookResult awaitOnce(ExecutionHook<C> hook, Future<HookResult> future,
            HookExecutionPolicy policy, long startNanos, long budgetNanos, long remainingNanos)
            throws InterruptedException {
        try {
            final HookResult result = future.get(remainingNanos, TimeUnit.NANOSECONDS);
            return result != null ? result : HookResult.success();
        } catch (TimeoutException te) {
            future.cancel(true);
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return onTimeout(hook, policy, elapsedMs, TimeUnit.NANOSECONDS.toMillis(budgetNanos));
        } catch (ExecutionException ee) {
            return onThrown(hook, policy, ee.getCause());
        } catch (RuntimeException e) {
            // Defensive: any other runtime error from the executor's wiring, including the CancellationException a
            // concurrently-cancelled task surfaces.
            return onThrown(hook, policy, e);
        }
    }

    /**
     * Maps a hook the pool refused to run — saturated, or shut down — to a result.
     *
     * <p>
     * A hook that {@linkplain HookExecutionPolicy#failsClosedWithoutVerdict(ExecutionHook) declares fail-closed} is
     * blocked: it was never asked, and "the guard could not be started" must not read as "the guard allowed it" —
     * otherwise filling the pool is a way to switch a guard off. Every other hook keeps
     * {@link HookExecutionPolicy#onException(Exception)}, as before.
     *
     * <p>
     * This returns at once, so a pool closed on purpose (stack teardown) cannot make a caller wait; see
     * {@link #close()} for which hooks can still arrive then.
     */
    private static <C extends HookContext> HookResult onRejected(ExecutionHook<C> hook, HookExecutionPolicy policy,
            RejectedExecutionException ree) {
        if (policy.failsClosedWithoutVerdict(hook)) {
            log.error("Hook rejected by executor (pool saturated or shut down); it declares fail-closed, so the"
                    + " operation is blocked. hook={}", hook, ree);
            return HookResult.block("Hook could not be run (the hook executor rejected it), so it gave no verdict");
        }
        log.warn("Hook rejected by executor (pool saturated or shut down). hook={}", hook, ree);
        return policy.onException(ree);
    }

    /**
     * Maps a hook whose body threw to a result.
     *
     * <p>
     * A hook that {@linkplain HookExecutionPolicy#failsClosedWithoutVerdict(ExecutionHook) declares fail-closed} is
     * blocked. The declarative guards catch what their action throws themselves; what reaches this point came from
     * outside that — the matcher predicate, the context, an {@link Error} such as a stack overflow in the matcher on
     * input the model chose — and under the shipped policies it used to read as success. The block reason names the
     * throwable's type and nothing else: its reader is the party the guard constrains, and a message can carry
     * anything. Every other hook keeps {@link HookExecutionPolicy#onException(Exception)}, as before.
     */
    private static <C extends HookContext> HookResult onThrown(ExecutionHook<C> hook, HookExecutionPolicy policy,
            Throwable thrown) {
        if (policy.failsClosedWithoutVerdict(hook)) {
            log.error("Hook threw instead of returning a verdict; it declares fail-closed, so the operation is"
                    + " blocked. hook={}", hook, thrown);
            return HookResult.block("Hook failed before it returned a verdict ("
                    + (thrown == null ? "unknown failure" : thrown.getClass().getSimpleName()) + ")");
        }
        final Exception toMap = thrown instanceof Exception e ? e : new RuntimeException(thrown);
        log.warn("Hook execution failed. Treating as policy-defined result. hook={}", hook, toMap);
        return policy.onException(toMap);
    }

    /**
     * Maps a wait aborted by an interrupt to BLOCKED, deliberately bypassing
     * {@link HookExecutionPolicy#onException(Exception)}.
     *
     * <p>
     * The exception mapper answers "what should happen when a hook <i>fails</i>?", and the default answer of
     * {@link HookExecutionPolicy#continueOnExceptionButStopOnBlocked()} is availability-first: SUCCESS. An interrupt is
     * not a hook failure — the hook may be perfectly healthy, and what was aborted is this thread's ability to wait for
     * its verdict. Routing it through the mapper therefore applies the policy's answer to a question it was never
     * asked, and the consequence is a security one: on a thread whose interrupt flag is already set,
     * {@link Future#get(long, TimeUnit)} throws without waiting, so every hook in the chain reports SUCCESS without
     * having run. A PreTool hook that was about to return BLOCKED is silently downgraded to allow, which makes an
     * interrupt a way around a permission block (see {@code docs/design/agent-execution/interrupt.md} §8).
     *
     * <p>
     * No verdict means no permission to proceed, so an interrupt closes the gate instead — the same shape as
     * {@link TimeoutBehavior#FAIL_CLOSED}, for the same reason. This cannot wrongly deny anything in a healthy turn:
     * the path is reachable only when the thread driving the turn has genuinely been interrupted, which is to say when
     * the turn is being cancelled anyway. For the advisory chains that never enforce BLOCKED (PostTool, OnStop,
     * session and subagent lifecycle) the change is simply truthful reporting — "no answer" rather than "success".
     *
     * <p>
     * An already-completed hook is unaffected: {@code FutureTask#get} returns a finished value without consulting the
     * interrupt flag, so only genuinely pending waits end up here.
     *
     * <p>
     * This is defence in depth, not the primary defence. The primary one is interrupt-flag hygiene in the ReAct loop
     * ({@code CancellationSignals}, interrupt design §8.1–§8.3), which keeps a stale flag from reaching this executor
     * at all; this method covers the paths hygiene has not swept.
     *
     * <p>
     * <b>The one exemption</b> is a hook that {@linkplain ExecutionHook#ignoresInterrupt() asks to be waited for}:
     * {@link #awaitHook} never gets here for it. That is not a way round a guard, because the alternative it takes is
     * not "success without running" — the wait continues and returns the hook's real verdict, or the timeout path's
     * answer when the budget runs out. What it costs is time: the interrupted execution waits for that hook.
     */
    private static <C extends HookContext> HookResult onInterrupted(ExecutionHook<C> hook) {
        log.warn("Hook execution interrupted before a verdict was returned; treating as BLOCKED. hook={}", hook);
        return HookResult.block("Hook execution was interrupted before the hook returned a verdict");
    }

    private static <C extends HookContext> HookResult onTimeout(ExecutionHook<C> hook, HookExecutionPolicy policy,
            long elapsedMs, long limitMs) {
        // The hook's own declaration wins over the chain's default: a declarative guard is FAIL_CLOSED under a
        // FAIL_OPEN policy.
        if (policy.timeoutBehaviorFor(hook) == TimeoutBehavior.FAIL_CLOSED) {
            log.error("Hook timed out (FAIL_CLOSED). hook={} elapsedMs={} limitMs={}", hook, elapsedMs, limitMs);
            return HookResult.block("Hook timed out after " + elapsedMs + "ms (limit=" + limitMs + "ms)");
        }
        log.warn("Hook timed out (FAIL_OPEN). hook={} elapsedMs={} limitMs={}", hook, elapsedMs, limitMs);
        return HookResult.success();
    }

    private static void applyAccumulatedToLast(List<HookResult> results, ToolInput accumulatedInput,
            ToolResult accumulatedOutput) {
        if (results.isEmpty() || (accumulatedInput == null && accumulatedOutput == null)) {
            return;
        }
        final int lastIdx = results.size() - 1;
        final HookResult last = results.get(lastIdx);
        final ToolInput finalInput = accumulatedInput != null ? accumulatedInput : last.getUpdatedInput().orElse(null);
        final ToolResult finalOutput = accumulatedOutput != null
                ? accumulatedOutput
                : last.getUpdatedOutput().orElse(null);
        final HookResult.Builder overlaid = HookResult.builder().decision(last.getDecision())
                .flowControl(last.getFlowControl()).feedback(last.getFeedback().orElse(null)).updatedInput(finalInput)
                .updatedOutput(finalOutput);
        // Rewake specs are orthogonal to the accumulated input/output — carry every spec the last hook emitted over to
        // the rebuilt result, otherwise DefaultHookExecutionManager#scheduleRewakes (which scans results *after* this
        // executor returns) would never see them.
        for (RewakeSpec spec : last.getRewakeSpecs()) {
            overlaid.rewakeSpec(spec);
        }
        results.set(lastIdx, overlaid.build());
    }
}
