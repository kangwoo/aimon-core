package at.aimon.core.agent.tool;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Dispatches a batch of tool calls (the {@code tool_use} blocks from a single LLM response) either in parallel or
 * sequentially, applying a two-stage safety gate before any parallel execution.
 *
 * <p>
 * Responsibility split (SRP / DIP): the executor owns single-tool execution (Pre/PostTool hooks, permission checks,
 * interrupt registration) and passes it in as the {@code runner} callback. The dispatcher owns only the decision of
 * <em>whether</em> to parallelise, the worker-pool fan-out, and reassembling results in the original order. The
 * executor depends on this interface, not on the concrete pool implementation.
 *
 * <p>
 * <b>Safety gate.</b> A batch is parallelised only when all of the following hold:
 * <ul>
 * <li>parallel execution is enabled (see {@link ToolConcurrencyConfig});</li>
 * <li>the batch contains more than one tool ({@code toolUses.size() > 1});</li>
 * <li>every tool in the batch resolves in {@code registry} and declares {@link ConcurrencyBehavior#CONCURRENT_SAFE}
 * with
 * a parallelisable {@link at.aimon.core.agent.interrupt.InterruptBehavior} (NON_INTERRUPTIBLE or COOPERATIVE).</li>
 * </ul>
 * Any unmet condition (including an unregistered/hallucinated tool name, or a single {@code SEQUENTIAL} tool) makes the
 * whole batch fall back to sequential execution.
 *
 * <p>
 * <b>Ordering guarantee.</b> The returned list always matches the input {@code toolUses} order, regardless of the order
 * in which parallel tasks complete.
 */
public interface ParallelToolDispatcher {

    /**
     * Executes the batch, parallelising it iff the safety gate passes; otherwise runs it sequentially. Results are
     * always returned in the same order as {@code toolUses}.
     *
     * <p>
     * {@code onStarted} is invoked for each tool in input order on the calling thread (so start events keep a
     * deterministic order). Note: under the parallel path a per-batch concurrency cap may defer a tool's
     * {@code onStarted} (the calling thread blocks on the cap until a slot frees) — the <em>order</em> is still input
     * order, and the event always fires on the calling thread before the task is submitted to the worker pool.
     * {@code onCompleted} is invoked when each tool finishes — in completion order on a worker thread under the
     * parallel path (so a listener must be thread-safe), in input order on the calling thread under the sequential
     * path; both are matched by tool-use id. Callbacks may be {@code null} (subagent execution emits no events). The
     * dispatcher isolates exceptions thrown by {@code runner}, {@code onStarted}, and {@code onCompleted} so a single
     * failure cannot break ordering or the rest of the batch.
     *
     * @param toolUses
     *            the batch to execute (must not be null)
     * @param registry
     *            the per-session registry used to look up each tool's {@link ConcurrencyBehavior} and
     *            {@link at.aimon.core.agent.interrupt.InterruptBehavior} for the safety gate (nullable — a null
     *            registry forces sequential execution)
     * @param runner
     *            single-tool execution callback delegating to the executor's {@code executeSingleTool} (must not be
     *            null); it is expected to convert tool failures into an error {@link ToolUseResult} rather than throw
     * @param onStarted
     *            invoked before each tool is dispatched, in input order (nullable)
     * @param onCompleted
     *            invoked after each tool completes, with the produced result (nullable)
     * @return the per-tool results in the same order as {@code toolUses} (never null)
     */
    List<ToolUseResult> dispatch(List<ToolUse> toolUses, ToolRegistry registry, Function<ToolUse, ToolUseResult> runner,
            Consumer<ToolUse> onStarted, BiConsumer<ToolUse, ToolUseResult> onCompleted);

    // --- Eager (incremental) dispatch: streaming-tool overlap (design §7)
    // --------------------------------------
    //
    // The batch dispatch() above needs the whole tool_use list up-front. Streaming-tool overlap instead hands the
    // dispatcher one completed tool_use block at a time — as soon as it finishes streaming — so its execution overlaps
    // the still-arriving token stream. The per-tool safety slice ({@link #isEagerEligible}) mirrors the batch gate's
    // Layer-2 check; per-batch prefix-safety and result reassembly are owned by the caller (StreamingToolScheduler),
    // not here. The default implementations opt out (no pool), so a non-pooled dispatcher is unaffected.

    /**
     * @return {@code true} if this dispatcher can run tools eagerly on a shared pool for streaming-tool overlap. The
     *         default is {@code false}; a pooled implementation returns {@code true} only when parallel execution and
     *         the streaming-overlap opt-in are both enabled and the pool is open.
     */
    default boolean supportsEagerDispatch() {
        return false;
    }

    /**
     * Per-tool eligibility slice of the safety gate (the Layer-2 check applied to one tool rather than a whole batch):
     * the tool must resolve in {@code registry}, declare {@link ConcurrencyBehavior#CONCURRENT_SAFE}, and have a
     * parallelisable {@link at.aimon.core.agent.interrupt.InterruptBehavior}. Unlike {@link #dispatch}'s gate this
     * applies <em>no</em> batch-size check — the caller enforces prefix-safety across the batch.
     *
     * @param toolUse
     *            the completed tool use to test (nullable — {@code null} is ineligible)
     * @param registry
     *            the per-session registry for policy/interrupt lookup (nullable — {@code null} is ineligible)
     * @return {@code true} if the tool may be dispatched eagerly on its own
     */
    default boolean isEagerEligible(ToolUse toolUse, ToolRegistry registry) {
        return false;
    }

    /**
     * Submits a single eligible tool to the shared worker pool without any batch bookkeeping, returning a future for
     * its result. The caller owns per-batch concurrency (a permit acquired before this call) and result ordering. The
     * {@code runner} is expected to convert tool failures into an error {@link ToolUseResult} rather than throw; the
     * implementation additionally isolates a contract-violating throw into an error result.
     *
     * @param toolUse
     *            the tool to execute (must not be null)
     * @param runner
     *            the single-tool execution callback (must not be null)
     * @return a future for the tool result, or {@link Optional#empty()} if this dispatcher cannot run it eagerly (no
     *         pool, disabled, or closed) — in which case the caller runs it later on the harvest path
     */
    default Optional<CompletableFuture<ToolUseResult>> submitEager(ToolUse toolUse,
            Function<ToolUse, ToolUseResult> runner) {
        return Optional.empty();
    }

    /**
     * @return the number of tools from one batch that may occupy the shared pool concurrently during eager dispatch —
     *         the per-batch cap the caller sizes its permit semaphore to. Default {@code 1}.
     */
    default int eagerPermits() {
        return 1;
    }
}
