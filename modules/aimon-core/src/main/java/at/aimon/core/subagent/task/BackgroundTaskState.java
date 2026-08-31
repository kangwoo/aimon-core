package at.aimon.core.subagent.task;

/**
 * Lifecycle state of a background subagent task.
 *
 * <p>
 * Models the full state machine of a detached subagent execution. It replaced a thin status derived from the state of a
 * node-local {@code CompletableFuture}, which could not express {@code PENDING} or {@code KILLED} and disappeared with
 * the JVM that produced it. The {@link #isTerminal()} predicate gates eviction, duplicate completion notification, and
 * idempotent state transitions — a terminal task never transitions again.
 *
 * <p>
 * A terminal state is also the readiness signal for {@link TaskResultStore}: the result is saved <em>before</em> the
 * transition that publishes it, so a reader that observes a terminal state can already read what produced it.
 *
 * <p>
 * Valid transitions:
 *
 * <pre>
 * PENDING ─▶ RUNNING ─▶ COMPLETED
 *                    ├─▶ FAILED
 *                    └─▶ KILLED
 * PENDING ───────────▶ KILLED   (stopped before it started running)
 * </pre>
 */
public enum BackgroundTaskState {

    /** Registered but the worker thread has not started the ReAct loop yet. */
    PENDING,

    /** The subagent's ReAct loop (or code behavior) is executing. */
    RUNNING,

    /** The subagent finished and produced a successful result. */
    COMPLETED,

    /** The subagent finished with an error, or execution threw before producing a result. */
    FAILED,

    /** The task was stopped (cooperatively cancelled) before it could finish. */
    KILLED;

    /**
     * @return {@code true} if this is a terminal state ({@link #COMPLETED}, {@link #FAILED}, or {@link #KILLED}). A
     *         terminal task is never transitioned again — the store's {@code transition} is a no-op once terminal.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }
}
