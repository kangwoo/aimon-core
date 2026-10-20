package at.aimon.core.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * A {@link ParallelToolDispatcher} decorator that runs each maximal run of consecutive
 * {@link ConcurrencyBehavior#DEDICATED_THREAD DEDICATED_THREAD} calls in a batch at the same time, one call per newly
 * created thread, and hands everything else to the wrapped dispatcher unchanged.
 *
 * <p>
 * The batch is cut into segments. A segment of {@code DEDICATED_THREAD} calls is fanned out here; any other segment
 * goes to the delegate as one {@code dispatch} call, so it keeps the existing rules (sequential, or the shared pool
 * when every tool in it is {@code CONCURRENT_SAFE}). Segments run one after another — each is joined before the next
 * starts — so the relative order and the "starts after the previous finished" meaning of the other tools are preserved.
 * A batch with no {@code DEDICATED_THREAD} call, a disabled configuration, or an execution at or beyond {@link
 * DedicatedThreadConfig#getMaxForkDepth()} is handed to the delegate whole, in a single call.
 *
 * <p>
 * Why a fresh thread per call and not a pool: a {@code DEDICATED_THREAD} tool registers a terminator that interrupts
 * its executing thread, and such an interrupt can arrive after the tool returned. On a pooled worker it would hit the
 * worker's next task; on a thread that dies with the call there is no next task.
 *
 * <p>
 * Threads come from the shared {@link DedicatedThreadPermits}. A permit is taken without blocking; when none is free,
 * the call runs inline on the calling thread exactly as it would without this decorator, so at most
 * {@code maxThreads + 1} calls of one batch run at once and nested fan-out cannot deadlock.
 *
 * <p>
 * The three guarantees of {@link ParallelToolDispatcher#dispatch} hold unchanged: results come back in input order;
 * {@code onStarted} fires on the calling thread in input order; {@code onCompleted} fires as each call finishes — on
 * the dedicated thread, in completion order, so a listener must be thread-safe. That includes the callbacks a fork
 * executor passes when {@code maxForkDepth >= 2}: they are safe there for the same reason they are on the pool path
 * (the fork's output sink is a no-op or a thread-safe store append).
 *
 * <p>
 * If the calling thread is interrupted while it waits for the dedicated threads, it interrupts the ones whose call is
 * still running, keeps waiting for them, and restores its own interrupt flag before returning, so the executor's loop
 * checkpoint still sees the interrupt. Forwarding that interrupt is safe only because a tool's terminator fires solely
 * from the coordinator's {@code requestInterrupt}, which has already tripped every sibling's registrar in the same
 * pass: the forwarded interrupt repeats a cancel the siblings already have and can never start one on its own. A
 * dedicated thread whose call has already returned is not interrupted, so the interrupt cannot land in its
 * {@code onCompleted} listener. A caller that is already interrupted when a dedicated segment begins starts no thread:
 * the segment goes to the delegate, whose runner skips each call exactly as the sequential path does.
 *
 * <p>
 * If something escapes on the calling thread mid-segment — an {@link Error} from a call on the inline lane or from a
 * listener — the dedicated threads already started are interrupted and joined before it propagates, so no thread
 * outlives the dispatch call that started it.
 */
public final class DedicatedThreadDispatcher implements ParallelToolDispatcher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DedicatedThreadDispatcher.class);

    private final ParallelToolDispatcher delegate;
    private final DedicatedThreadConfig config;
    private final DedicatedThreadPermits permits;

    /**
     * @param delegate
     *            the dispatcher that runs every segment that is not {@code DEDICATED_THREAD} (must not be null)
     * @param config
     *            the dedicated-thread configuration (must not be null)
     * @param permits
     *            the permit set bounding live dedicated threads; share one across every dispatcher the bound should
     *            cover (must not be null)
     */
    public DedicatedThreadDispatcher(ParallelToolDispatcher delegate, DedicatedThreadConfig config,
            DedicatedThreadPermits permits) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.permits = Objects.requireNonNull(permits, "permits cannot be null");
    }

    @Override
    public List<ToolUseResult> dispatch(List<ToolUse> toolUses, ToolRegistry registry,
            Function<ToolUse, ToolUseResult> runner, Consumer<ToolUse> onStarted,
            BiConsumer<ToolUse, ToolUseResult> onCompleted) {
        return dispatch(toolUses, registry, DispatchOptions.root(), runner, onStarted, onCompleted);
    }

    @Override
    public List<ToolUseResult> dispatch(List<ToolUse> toolUses, ToolRegistry registry, DispatchOptions options,
            Function<ToolUse, ToolUseResult> runner, Consumer<ToolUse> onStarted,
            BiConsumer<ToolUse, ToolUseResult> onCompleted) {
        Objects.requireNonNull(toolUses, "toolUses cannot be null");
        Objects.requireNonNull(options, "options cannot be null");
        Objects.requireNonNull(runner, "runner cannot be null");

        if (!config.isEnabled() || registry == null || options.getForkDepth() >= config.getMaxForkDepth()
                || toolUses.stream().noneMatch(toolUse -> isDedicated(toolUse, registry))) {
            return delegate.dispatch(toolUses, registry, options, runner, onStarted, onCompleted);
        }
        final List<ToolUseResult> results = new ArrayList<>(toolUses.size());
        int start = 0;
        while (start < toolUses.size()) {
            final boolean dedicated = isDedicated(toolUses.get(start), registry);
            int end = start + 1;
            while (end < toolUses.size() && isDedicated(toolUses.get(end), registry) == dedicated) {
                end++;
            }
            final List<ToolUse> segment = toolUses.subList(start, end);
            // An already-interrupted caller starts no thread: the delegate runs the segment sequentially, where the
            // runner skips each call and leaves the flag for the loop checkpoint, exactly as without this decorator.
            if (dedicated && !Thread.currentThread().isInterrupted()) {
                results.addAll(runDedicated(segment, runner, onStarted, onCompleted));
            } else {
                results.addAll(
                        delegate.dispatch(List.copyOf(segment), registry, options, runner, onStarted, onCompleted));
            }
            start = end;
        }
        return results;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Always {@code true}: a tail of {@code DEDICATED_THREAD} calls after an eager prefix should fan out like any
     * other batch. Segments that are not {@code DEDICATED_THREAD} still go to the delegate, whose own gate decides how
     * they run.
     */
    @Override
    public boolean routesHarvestTail() {
        return true;
    }

    @Override
    public boolean supportsEagerDispatch() {
        return delegate.supportsEagerDispatch();
    }

    @Override
    public boolean isEagerEligible(ToolUse toolUse, ToolRegistry registry) {
        return delegate.isEagerEligible(toolUse, registry);
    }

    @Override
    public Optional<CompletableFuture<ToolUseResult>> submitEager(ToolUse toolUse,
            Function<ToolUse, ToolUseResult> runner) {
        return delegate.submitEager(toolUse, runner);
    }

    @Override
    public int eagerPermits() {
        return delegate.eagerPermits();
    }

    /** Closes the delegate when it is {@link AutoCloseable}; the permits need no closing. */
    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private static boolean isDedicated(ToolUse toolUse, ToolRegistry registry) {
        final Optional<Tool> tool = registry.findByName(toolUse.getName());
        // An unregistered / hallucinated name is not dedicated — the same conservatism as the batch gate.
        return tool.isPresent() && tool.get().getConcurrencyBehavior() == ConcurrencyBehavior.DEDICATED_THREAD;
    }

    private List<ToolUseResult> runDedicated(List<ToolUse> segment, Function<ToolUse, ToolUseResult> runner,
            Consumer<ToolUse> onStarted, BiConsumer<ToolUse, ToolUseResult> onCompleted) {
        final List<Slot> slots = new ArrayList<>(segment.size());
        try {
            for (ToolUse toolUse : segment) {
                ToolDispatchCallbacks.safeOnStarted(log, onStarted, toolUse);
                slots.add(startOrRunInline(toolUse, runner, onCompleted));
            }
            return joinAll(slots);
        } catch (Throwable t) {
            // Only an Error (from a call on the inline lane or a listener) can get here. Do not leave the threads
            // already started: interrupt them and wait for them, then let the Error go on as it would without this
            // decorator. Each thread hands its own permit back, so nothing is released here.
            stopAndJoin(slots);
            throw t;
        }
    }

    private List<ToolUseResult> joinAll(List<Slot> slots) {
        boolean callerInterrupted = false;
        final List<ToolUseResult> results = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot.future == null) {
                results.add(slot.result);
                continue;
            }
            while (true) {
                try {
                    results.add(slot.future.get());
                    break;
                } catch (InterruptedException e) {
                    // The calling thread itself was interrupted while joining. Pass the same lever on to the calls
                    // still running, keep waiting for them, and restore the flag afterwards so the executor's loop
                    // checkpoint — the one place that consumes it and promotes it to a signal — still sees it.
                    callerInterrupted = true;
                    slots.forEach(Slot::interruptIfRunning);
                } catch (ExecutionException e) {
                    final Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.error("Dedicated tool thread failed for tool_use {} ({}): {}", slot.toolUse.getId(),
                            slot.toolUse.getName(), cause.getMessage(), cause);
                    results.add(
                            ToolUseResult.error(slot.toolUse.getId(), "Tool execution error: " + cause.getMessage()));
                    break;
                }
            }
        }
        if (callerInterrupted) {
            Thread.currentThread().interrupt();
        }
        return results;
    }

    /**
     * Interrupts every started dedicated thread whose call is still running and joins all of them. The join is not
     * bounded, for the same reason the normal join after a forwarded interrupt is not: the interrupt is the cancel
     * lever these calls honour (a fork's loop checkpoint), and returning early would leave threads running that no
     * dispatch call owns any more. An interrupt of the calling thread meanwhile is kept and restored.
     */
    private static void stopAndJoin(List<Slot> slots) {
        slots.forEach(Slot::interruptIfRunning);
        boolean callerInterrupted = false;
        for (Slot slot : slots) {
            if (slot.thread == null) {
                continue;
            }
            while (true) {
                try {
                    slot.thread.join();
                    break;
                } catch (InterruptedException e) {
                    callerInterrupted = true;
                }
            }
        }
        if (callerInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Slot startOrRunInline(ToolUse toolUse, Function<ToolUse, ToolUseResult> runner,
            BiConsumer<ToolUse, ToolUseResult> onCompleted) {
        if (!permits.tryAcquire()) {
            // No permit free: run on the calling thread, exactly as without this decorator.
            return Slot.inline(toolUse, ToolDispatchCallbacks.runAndComplete(log, runner, onCompleted, toolUse));
        }
        final Slot slot = Slot.dedicated(toolUse);
        final Thread thread;
        try {
            thread = permits.newThread(() -> runOnDedicatedThread(slot, runner, onCompleted));
            slot.thread = thread;
            thread.start();
        } catch (RuntimeException | OutOfMemoryError e) {
            // The thread could not be created or started (e.g. the OS refused a native thread), so its finally will
            // never run: hand the permit back here and run the call inline.
            permits.release();
            log.warn("Could not start a dedicated thread for tool_use {} ({}); running it on the calling thread: {}",
                    toolUse.getId(), toolUse.getName(), e.getMessage());
            return Slot.inline(toolUse, ToolDispatchCallbacks.runAndComplete(log, runner, onCompleted, toolUse));
        }
        return slot;
    }

    /**
     * The body of a dedicated thread. Even a runner that throws an {@link Error} yields an error result that goes to
     * {@code onCompleted}, so a UI that saw the call start also sees it finish. The slot is marked returned before
     * {@code onCompleted} runs and the future is completed after it, so the event order stays start, completed, joined.
     */
    private void runOnDedicatedThread(Slot slot, Function<ToolUse, ToolUseResult> runner,
            BiConsumer<ToolUse, ToolUseResult> onCompleted) {
        final ToolUse toolUse = slot.toolUse;
        try {
            ToolUseResult result;
            try {
                result = ToolDispatchCallbacks.runSafely(log, runner, toolUse);
            } catch (Throwable t) {
                log.error("Tool runner failed on its dedicated thread for tool_use {} ({}): {}", toolUse.getId(),
                        toolUse.getName(), t.getMessage(), t);
                result = ToolUseResult.error(toolUse.getId(), "Tool execution error: " + t.getMessage());
            }
            slot.markRunnerReturned();
            ToolDispatchCallbacks.safeOnCompleted(log, onCompleted, toolUse, result);
            slot.future.complete(result);
        } catch (Throwable t) {
            // A listener threw an Error: the joiner turns it into an error result.
            slot.future.completeExceptionally(t);
        } finally {
            permits.release();
        }
    }

    /**
     * One call of a dedicated segment: either already run inline ({@code future == null}) or running on a thread of
     * its own.
     */
    private static final class Slot {
        private final ToolUse toolUse;
        private final ToolUseResult result;
        private final CompletableFuture<ToolUseResult> future;
        private final Object lock = new Object();

        /** Set by the calling thread before {@link Thread#start()}; read only by the calling thread. */
        private Thread thread;

        /** Guarded by {@link #lock}; once set, a forwarded interrupt no longer reaches {@link #thread}. */
        private boolean runnerReturned;

        private Slot(ToolUse toolUse, ToolUseResult result, CompletableFuture<ToolUseResult> future) {
            this.toolUse = toolUse;
            this.result = result;
            this.future = future;
        }

        static Slot inline(ToolUse toolUse, ToolUseResult result) {
            return new Slot(toolUse, result, null);
        }

        static Slot dedicated(ToolUse toolUse) {
            return new Slot(toolUse, null, new CompletableFuture<>());
        }

        /**
         * Called on the dedicated thread as soon as the runner returns, before {@code onCompleted} runs. Also clears
         * the thread's interrupt flag, in case a forwarded interrupt landed between the runner's return and this
         * call: the flag has no reader left on this thread (no loop checkpoint runs here; the caller restores its
         * own), and the listener must not inherit it.
         */
        void markRunnerReturned() {
            synchronized (lock) {
                runnerReturned = true;
                Thread.interrupted();
            }
        }

        /**
         * Interrupts the dedicated thread if its runner has not returned yet. Under the same lock as
         * {@link #markRunnerReturned()}, so a forwarded interrupt never reaches the {@code onCompleted} listener that
         * follows the runner.
         */
        void interruptIfRunning() {
            if (thread == null) {
                return;
            }
            synchronized (lock) {
                if (!runnerReturned) {
                    thread.interrupt();
                }
            }
        }
    }
}
