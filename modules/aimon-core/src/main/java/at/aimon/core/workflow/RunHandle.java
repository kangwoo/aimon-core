package at.aimon.core.workflow;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A node-local handle to a background workflow run: its {@link RunId} and the {@link CompletableFuture} carrying
 * the script's typed result {@code T} (design §5.1).
 *
 * <p>
 * The typed result is <b>owning-node only</b> — it lives in an in-JVM future and is never serialized. Cross-node
 * observers use the store-backed {@link WorkflowRunController#status(RunId) status}/{@code list} plane for run
 * state, and recover a typed result by re-executing the script over the {@code StepResultCache}. {@link #await} is
 * therefore a same-JVM convenience for the submitting node.
 *
 * @param <T>
 *            the script's result type
 */
public final class RunHandle<T> {

    private final RunId runId;
    private final CompletableFuture<T> future;

    /**
     * Wraps a run's id and result future. Normally produced by the runner's {@code runInBackground} (constructing one
     * by hand yields a handle the runner does not drive).
     *
     * <p>
     * The handle stores a <b>defensive copy</b> of {@code future}: the copy observes the original's outcome, but
     * completing or cancelling it (via {@link #future()}) never propagates back — so a caller can never complete,
     * cancel, or obtrude the runner's own future and desynchronize the run's tracked state.
     *
     * @param runId
     *            the run id (must not be null)
     * @param future
     *            the future completing with the script's result, or completing exceptionally on failure (must not be
     *            null)
     */
    public RunHandle(RunId runId, CompletableFuture<T> future) {
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.future = Objects.requireNonNull(future, "future cannot be null").copy();
    }

    /**
     * @return the run id (never null)
     */
    public RunId runId() {
        return runId;
    }

    /**
     * Returns a view of the run's result future: it completes with the script's result (or exceptionally on failure),
     * but it is decoupled from the run itself — completing or cancelling it affects only this handle's view, never the
     * running script or the runner's tracked state. Use {@code WorkflowRunController#stop(RunId)} to actually stop
     * a run.
     *
     * @return the result-future view (never null)
     */
    public CompletableFuture<T> future() {
        return future;
    }

    /**
     * @return {@code true} if the run has finished (normally or exceptionally)
     */
    public boolean isDone() {
        return future.isDone();
    }

    /**
     * Waits up to {@code timeout} for the run to finish and returns its result (owning-node only).
     *
     * @param timeout
     *            the maximum time to wait (must not be null)
     * @return the script's result
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     * @throws ExecutionException
     *             if the run failed (the cause is the thrown error)
     * @throws TimeoutException
     *             if the run did not finish within {@code timeout}
     */
    public T await(Duration timeout) throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
