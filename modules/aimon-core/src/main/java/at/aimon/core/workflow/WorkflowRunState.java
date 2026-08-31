package at.aimon.core.workflow;

/**
 * Lifecycle state of a background workflow run.
 *
 * <p>
 * Run-scoped analog of {@code at.aimon.core.subagent.task.BackgroundTaskState}: it models the full state machine of a
 * detached {@link WorkflowScript} execution. The {@link #isTerminal()} predicate gates eviction, duplicate completion
 * notification, and idempotent state transitions — a terminal run never transitions again, and a {@code RunStore}'s
 * {@code transition} is a no-op once terminal.
 *
 * <p>
 * Valid transitions:
 *
 * <pre>
 * PENDING ─▶ RUNNING ─▶ COMPLETED
 *                    ├─▶ FAILED
 *                    └─▶ KILLED
 * PENDING ───────────▶ KILLED   (stopped before it started running)
 * PENDING ───────────▶ FAILED   (rejected by a saturated hosting pool, or abandoned by runner close())
 * </pre>
 */
public enum WorkflowRunState {

    /** Registered but the run-hosting worker has not started the script body yet. */
    PENDING,

    /** The {@link WorkflowScript} body is executing (fanning out {@code agent}/{@code parallel}/{@code pipeline}). */
    RUNNING,

    /** The run finished and the script returned a result. */
    COMPLETED,

    /** The run finished with an error, or the script body threw before returning. */
    FAILED,

    /** The run was stopped (cooperatively cancelled) before it could finish. */
    KILLED;

    /**
     * @return {@code true} if this is a terminal state ({@link #COMPLETED}, {@link #FAILED}, or {@link #KILLED}). A
     *         terminal run is never transitioned again — the store's {@code transition} is a no-op once terminal.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }
}
