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
 * If the calling thread is interrupted while it waits for the dedicated threads, it interrupts the ones still running,
 * keeps waiting for them, and restores its own interrupt flag before returning, so the executor's loop checkpoint
 * still sees the interrupt.
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
            return delegate.dispatch(toolUses, registry, runner, onStarted, onCompleted);
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
            results.addAll(dedicated
                    ? runDedicated(segment, runner, onStarted, onCompleted)
                    : delegate.dispatch(List.copyOf(segment), registry, runner, onStarted, onCompleted));
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
        for (ToolUse toolUse : segment) {
            safeOnStarted(onStarted, toolUse);
            slots.add(startOrRunInline(toolUse, runner, onCompleted));
        }

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
                    for (Slot other : slots) {
                        if (other.future != null && !other.future.isDone()) {
                            other.thread.interrupt();
                        }
                    }
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

    private Slot startOrRunInline(ToolUse toolUse, Function<ToolUse, ToolUseResult> runner,
            BiConsumer<ToolUse, ToolUseResult> onCompleted) {
        if (!permits.tryAcquire()) {
            // No permit free: run on the calling thread, exactly as without this decorator.
            return Slot.inline(toolUse, runAndComplete(runner, onCompleted, toolUse));
        }
        final CompletableFuture<ToolUseResult> future = new CompletableFuture<>();
        final Thread thread;
        try {
            thread = permits.newThread(() -> {
                try {
                    future.complete(runAndComplete(runner, onCompleted, toolUse));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                } finally {
                    permits.release();
                }
            });
            thread.start();
        } catch (RuntimeException | OutOfMemoryError e) {
            // The thread could not be created or started (e.g. the OS refused a native thread), so its finally will
            // never run: hand the permit back here and run the call inline.
            permits.release();
            log.warn("Could not start a dedicated thread for tool_use {} ({}); running it on the calling thread: {}",
                    toolUse.getId(), toolUse.getName(), e.getMessage());
            return Slot.inline(toolUse, runAndComplete(runner, onCompleted, toolUse));
        }
        return Slot.dedicated(toolUse, future, thread);
    }

    private static ToolUseResult runAndComplete(Function<ToolUse, ToolUseResult> runner,
            BiConsumer<ToolUse, ToolUseResult> onCompleted, ToolUse toolUse) {
        final ToolUseResult result = runSafely(runner, toolUse);
        safeOnCompleted(onCompleted, toolUse, result);
        return result;
    }

    private static ToolUseResult runSafely(Function<ToolUse, ToolUseResult> runner, ToolUse toolUse) {
        try {
            final ToolUseResult result = runner.apply(toolUse);
            if (result != null) {
                return result;
            }
            log.error("Tool runner returned null for tool_use {} ({})", toolUse.getId(), toolUse.getName());
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: runner returned no result");
        } catch (Exception e) {
            log.error("Tool runner threw for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(), e.getMessage(),
                    e);
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: " + e.getMessage());
        }
    }

    private static void safeOnStarted(Consumer<ToolUse> onStarted, ToolUse toolUse) {
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

    private static void safeOnCompleted(BiConsumer<ToolUse, ToolUseResult> onCompleted, ToolUse toolUse,
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

    /** One call of a dedicated segment: either already run inline ({@code future == null}) or running on a thread. */
    private static final class Slot {
        private final ToolUse toolUse;
        private final ToolUseResult result;
        private final CompletableFuture<ToolUseResult> future;
        private final Thread thread;

        private Slot(ToolUse toolUse, ToolUseResult result, CompletableFuture<ToolUseResult> future, Thread thread) {
            this.toolUse = toolUse;
            this.result = result;
            this.future = future;
            this.thread = thread;
        }

        static Slot inline(ToolUse toolUse, ToolUseResult result) {
            return new Slot(toolUse, result, null, null);
        }

        static Slot dedicated(ToolUse toolUse, CompletableFuture<ToolUseResult> future, Thread thread) {
            return new Slot(toolUse, null, future, thread);
        }
    }
}
