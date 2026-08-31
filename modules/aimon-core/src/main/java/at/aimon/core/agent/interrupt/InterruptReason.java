package at.aimon.core.agent.interrupt;

/**
 * Classifies why an execution was interrupted. Carried alongside the {@link CancellationSignal} for observability and
 * ultimately surfaced through {@link at.aimon.core.agent.budget.CompletionReason} on the execution result.
 */
public enum InterruptReason {
    /** The user pressed Ctrl+C (SIGINT on the CLI host) or equivalent. */
    USER_SIGINT,

    /**
     * A queued input with {@code QueuedInputPriority.NOW} arrived and preempted the current turn so the user's
     * higher-priority message can be processed without waiting for the current work to finish.
     */
    NOW_PRIORITY_INPUT,

    /** The execution budget (iterations, tokens, or wall-clock) was exhausted mid-execution. */
    BUDGET_EXCEEDED,

    /** A parent agent or session cancelled this execution via cascade. */
    PARENT_CANCELLED,

    /**
     * The scheduled task this run belongs to was cancelled by its owner, or its in-flight run was explicitly
     * interrupted while the schedule itself was left in place. Distinct from {@link #PARENT_CANCELLED}: nothing
     * cascaded into this run from an enclosing execution &mdash; the request named this run's own task.
     */
    TASK_CANCELLED,

    /** The host runtime is shutting down (container stop, JVM shutdown hook, managed shutdown, ...). */
    SYSTEM_SHUTDOWN,

    /**
     * The session's distributed lock lease could not be renewed in time, so the holder node must surrender the
     * turn before another node may legitimately take over (web session manager, routing design §7.4).
     */
    LEASE_LOST,

    /**
     * An external caller (typically the web session manager) explicitly released the session, requesting that any
     * in-flight turn surrender promptly so cached resources can be evicted (design §6.3 A).
     */
    SESSION_RELEASED,

    /**
     * A peer node detected via the idempotency store's holder-loss sweeper that the original holder has gone silent,
     * triggering a takeover. Surfaced to subscribers so the failed turn ends with a visible terminal event (design
     * §6.3 D).
     */
    HOLDER_LOST
}
