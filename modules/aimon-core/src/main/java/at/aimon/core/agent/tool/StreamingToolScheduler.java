package at.aimon.core.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Coordinates <em>streaming-tool overlap</em> (design §7): as each tool_use block finishes streaming, a
 * side-effect-free tool is dispatched to the shared worker pool so its execution overlaps the still-arriving token
 * stream, and its result is harvested — in the model's original tool order — once the stream ends.
 *
 * <p>
 * <b>Scope.</b> One instance per streaming attempt (an executor iteration, re-initialised via
 * {@link #resetForNewAttempt()} on a prompt-too-long re-issue). It is <em>not</em> the executor-scoped pool: it holds
 * only per-attempt state (eager futures, a prefix-safety flag, a per-batch permit semaphore) and delegates the actual
 * execution to the shared {@link ParallelToolDispatcher} via {@link ParallelToolDispatcher#submitEager}.
 *
 * <p>
 * <b>Prefix-safety rule.</b> A tool at position <i>N</i> is eager-dispatched only while every tool at position
 * {@code < N} was itself eligible. tool_use blocks arrive in model order, so the scheduler simply stops dispatching
 * ("poisons") at the first ineligible tool: that tool and all after it are left for the executor to run on the harvest
 * path. This preserves today's ordering guarantees — a {@link ConcurrencyBehavior#CONCURRENT_SAFE} tool never observes
 * a
 * later {@code SEQUENTIAL} tool's side effects, because the {@code SEQUENTIAL} tool (and anything after it) only runs
 * after the eager prefix has already completed; and any read-before-write hazard is avoided because a write is never
 * eager (it poisons at its own position, so reads before it still ran, and reads after it are deferred to run after the
 * write).
 *
 * <p>
 * <b>Deferred events.</b> The scheduler executes tools early but emits no lifecycle events. The executor emits
 * {@code onStarted} / {@code onCompleted} only at harvest, in input order, so with overlap on the observable event
 * stream and the tool results are byte-identical to the non-overlap path — only wall-clock is reduced.
 *
 * <p>
 * <b>Threading.</b> {@link #onToolUseReady} and {@link #disableForRetry} run on the single provider stream thread (or,
 * under buffered replay, the flushing thread — still one thread). {@link #resetForNewAttempt} runs before a stream
 * starts and {@link #hasEager}/{@link #joinEager}/{@link #hasAnyEager}/{@link #cancelAll} run after it ends, all on the
 * executor loop thread; the stream's completion establishes the happens-before. Eager tasks themselves run on shared
 * pool threads but touch only the caller-supplied {@code runner}. All mutable state is nonetheless guarded by an
 * internal lock for defence in depth.
 */
public final class StreamingToolScheduler {

    private static final Logger log = LoggerFactory.getLogger(StreamingToolScheduler.class);

    private final ParallelToolDispatcher dispatcher;
    private final ToolRegistry registry;
    private final Function<ToolUse, ToolUseResult> runner;

    private final Object lock = new Object();
    private final Map<String, CompletableFuture<ToolUseResult>> eagerById = new LinkedHashMap<>();
    private Semaphore permits;
    private boolean poisoned;
    private boolean disabled;

    /**
     * @param dispatcher
     *            the shared dispatcher that owns the worker pool (must not be null)
     * @param registry
     *            the per-session registry used for per-tool eligibility (must not be null)
     * @param runner
     *            the single-tool execution callback (delegating to the executor's {@code executeSingleTool}); must not
     *            be null and is expected to return an error result rather than throw
     */
    public StreamingToolScheduler(ParallelToolDispatcher dispatcher, ToolRegistry registry,
            Function<ToolUse, ToolUseResult> runner) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.runner = Objects.requireNonNull(runner, "runner cannot be null");
        this.permits = new Semaphore(Math.max(1, dispatcher.eagerPermits()));
    }

    /**
     * Called as each tool_use block finishes streaming, in model order. Eager-dispatches the tool when the prefix is
     * still safe and the tool is eligible; otherwise marks the prefix poisoned so this tool and every later one are
     * deferred to the harvest path. A no-op once poisoned or disabled.
     *
     * @param toolUse
     *            the completed tool use (must not be null)
     */
    public void onToolUseReady(ToolUse toolUse) {
        Objects.requireNonNull(toolUse, "toolUse cannot be null");
        final Semaphore currentPermits;
        synchronized (lock) {
            if (disabled || poisoned) {
                return;
            }
            if (!dispatcher.isEagerEligible(toolUse, registry)) {
                // First ineligible tool poisons the rest of the batch: it and every later tool run on the harvest path,
                // preserving sequential-after-unsafe ordering.
                poisoned = true;
                log.debug("Streaming overlap poisoned at tool_use {} ({}); remaining tools run at harvest",
                        toolUse.getId(), toolUse.getName());
                return;
            }
            currentPermits = permits;
        }

        // Acquire a per-batch permit on the stream thread (backpressure) before submission, mirroring the two-tier
        // bound in DefaultParallelToolDispatcher#dispatchParallel. Uninterruptible: a stream-thread interrupt is not a
        // tool-cancellation signal.
        currentPermits.acquireUninterruptibly();
        final Optional<CompletableFuture<ToolUseResult>> future = dispatcher.submitEager(toolUse, runner);
        if (future.isEmpty()) {
            currentPermits.release();
            synchronized (lock) {
                // The pool could not take it (closed / disabled). Stop eager dispatch; this tool and the rest run at
                // harvest.
                poisoned = true;
            }
            return;
        }
        final CompletableFuture<ToolUseResult> f = future.get();
        f.whenComplete((result, error) -> currentPermits.release());
        synchronized (lock) {
            if (disabled) {
                // A retry/cancel raced in after submission; drop the just-submitted future so it is never harvested.
                f.cancel(false);
                return;
            }
            eagerById.put(toolUse.getId(), f);
        }
    }

    /**
     * @param toolUseId
     *            the id of the tool use to look up
     * @return {@code true} if an eager future exists for this tool and the scheduler has not been disabled
     */
    public boolean hasEager(String toolUseId) {
        synchronized (lock) {
            return !disabled && eagerById.containsKey(toolUseId);
        }
    }

    /**
     * @return {@code true} if at least one eager future is available for harvest (and the scheduler was not disabled by
     *         a retry/cancel). When {@code false} the executor takes the ordinary batch-dispatch path unchanged.
     */
    public boolean hasAnyEager() {
        synchronized (lock) {
            return !disabled && !eagerById.isEmpty();
        }
    }

    /**
     * Joins the eager result for a tool, blocking until it completes. Isolates a task failure or cancellation into an
     * error {@link ToolUseResult} so a single eager failure never breaks the harvest.
     *
     * @param toolUse
     *            the tool use whose eager result to harvest (must have {@link #hasEager} {@code == true})
     * @return the tool result (never null)
     * @throws IllegalStateException
     *             if no eager future exists for this tool (caller must gate on {@link #hasEager})
     */
    public ToolUseResult joinEager(ToolUse toolUse) {
        Objects.requireNonNull(toolUse, "toolUse cannot be null");
        final CompletableFuture<ToolUseResult> f;
        synchronized (lock) {
            f = eagerById.get(toolUse.getId());
            if (f == null) {
                throw new IllegalStateException("No eager future for tool_use " + toolUse.getId());
            }
        }
        try {
            return f.join();
        } catch (CompletionException | CancellationException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Eager tool task failed for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(),
                    cause.getMessage(), cause);
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: " + cause.getMessage());
        }
    }

    /**
     * Disables eager dispatch for the remainder of the current streaming call and cancels any in-flight eager futures.
     * Invoked when the gateway discards an attempt (retry / fallback): the discarded attempt's early work must not be
     * harvested, and since eager tools are side-effect-free their cancellation is contract-safe. The retried attempt
     * then runs tools on the ordinary harvest path (eager stays off until {@link #resetForNewAttempt()}).
     */
    public void disableForRetry() {
        synchronized (lock) {
            disabled = true;
            cancelAndClearLocked();
        }
    }

    /**
     * Discards all eager state and cancels in-flight futures. Invoked when the turn abandons this response without
     * harvesting — a skill-approval suspend, a mid-stream cancel, or a provider error. Idempotent.
     */
    public void cancelAll() {
        synchronized (lock) {
            disabled = true;
            cancelAndClearLocked();
        }
    }

    /**
     * Re-arms the scheduler for a fresh streaming attempt on the same iteration (a prompt-too-long re-issue rebuilds
     * the
     * message set and streams again). Cancels any leftover futures, clears the poison/disabled flags, and refreshes the
     * permit semaphore so the new attempt can eager-dispatch from a clean slate.
     */
    public void resetForNewAttempt() {
        synchronized (lock) {
            cancelAndClearLocked();
            poisoned = false;
            disabled = false;
            permits = new Semaphore(Math.max(1, dispatcher.eagerPermits()));
        }
    }

    private void cancelAndClearLocked() {
        for (CompletableFuture<ToolUseResult> future : eagerById.values()) {
            future.cancel(false);
        }
        eagerById.clear();
    }
}
