package at.aimon.core.subagent.task;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

/**
 * Node-local execution handle for a running background subagent task.
 *
 * <p>
 * Whereas {@link BackgroundTask} is the durable, node-independent metadata snapshot, this handle holds the live
 * machinery that can only exist on the node actually running the task: the {@link CompletableFuture}, the per-task
 * {@link InterruptCoordinator} and its {@link CancellationSignal}, and the worker {@link Thread}. It is deliberately
 * <b>not</b> a value object and is <b>not</b> persisted — a shared {@link BackgroundTaskStore} in a scale-out
 * deployment carries metadata across nodes, but a stop request can only be honoured on the owning node (or forwarded to
 * it out of band).
 *
 * <h2>Why not {@code future.cancel(true)}</h2>
 *
 * <p>
 * {@link CompletableFuture#cancel(boolean)} does <em>not</em> interrupt the thread executing a
 * {@code supplyAsync} body — the {@code mayInterruptIfRunning} flag is ignored for the default async execution
 * facility. Cooperative cancellation of a running subagent therefore requires two real levers, both applied by
 * {@link #requestStop()}: trip the per-task cancellation signal (observed at the subagent's ReAct/tool checkpoints) and
 * interrupt the captured worker thread (unblocks a blocking tool). This mirrors the foreground {@code Task} tool's
 * terminator path.
 *
 * <p>
 * All mutable fields are {@code volatile}; the handle is written by the spawning thread and read/mutated by any thread
 * that requests a stop.
 */
public final class RunningTaskHandle {

    private final String taskId;
    private final InterruptCoordinator coordinator;
    private final CancellationSignal signal;

    private volatile CompletableFuture<SubagentExecutionResult> future;
    private volatile Thread workerThread;
    private volatile boolean stopRequested;

    /**
     * Creates a handle for a task whose per-task interrupt coordinator has already been constructed.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @param coordinator
     *            the per-task interrupt coordinator whose signal is injected as the subagent's parent signal (must not
     *            be null)
     */
    public RunningTaskHandle(String taskId, InterruptCoordinator coordinator) {
        this.taskId = Objects.requireNonNull(taskId, "taskId cannot be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator cannot be null");
        this.signal = coordinator.getSignal();
    }

    /**
     * @return the task identifier (never null)
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * @return the per-task cancellation signal to inject as the subagent's parent signal (never null)
     */
    public CancellationSignal getSignal() {
        return signal;
    }

    /**
     * @return the future for the task result, or empty if it has not been attached yet
     */
    public Optional<CompletableFuture<SubagentExecutionResult>> getFuture() {
        return Optional.ofNullable(future);
    }

    /**
     * Attaches the future once it has been created. Called by the spawning thread.
     *
     * @param future
     *            the task's result future (must not be null)
     */
    public void attachFuture(CompletableFuture<SubagentExecutionResult> future) {
        this.future = Objects.requireNonNull(future, "future cannot be null");
    }

    /**
     * Records the worker thread executing the task so a later {@link #requestStop()} can interrupt it. Called from the
     * worker thread as its first action.
     *
     * @param workerThread
     *            the executing thread (must not be null)
     */
    public void attachWorker(Thread workerThread) {
        this.workerThread = Objects.requireNonNull(workerThread, "workerThread cannot be null");
    }

    /**
     * @return {@code true} if a stop has been requested for this task
     */
    public boolean isStopRequested() {
        return stopRequested;
    }

    /**
     * Requests cooperative cancellation of the task: marks the stop flag, trips the per-task cancellation signal (as
     * {@link InterruptReason#PARENT_CANCELLED}), and interrupts the worker thread if it has started. Idempotent — the
     * coordinator collapses repeated interrupt requests, and re-interrupting a thread is harmless.
     */
    public void requestStop() {
        stopRequested = true;
        coordinator.requestInterrupt(InterruptReason.PARENT_CANCELLED);
        final Thread worker = workerThread;
        if (worker != null) {
            worker.interrupt();
        }
    }
}
