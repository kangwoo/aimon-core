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
    HOLDER_LOST,

    /**
     * The execution was interrupted for a reason this build cannot name.
     *
     * <p>
     * <b>A reader's value, never a writer's.</b> Nothing interrupts an execution with this reason. It exists for the
     * decoders that read a reason another node wrote ({@link #fromWireName(String)}): during a rolling upgrade a
     * newer node may send a name this build does not define, and the frame carrying it — a terminal
     * {@code InterruptedAt} — still has to be delivered, or the subscriber never hears that the turn stopped. No
     * other value is a truthful stand-in: each of them says something specific happened, and it did not.
     *
     * <p>
     * A consumer that branches on the reason should treat this like any reason it has no special handling for. The
     * name that could not be read is not kept.
     *
     * <p>
     * Added after the first release of this enum. A peer on a build that predates it cannot read the name
     * {@code UNKNOWN} either, which is harmless as long as the first paragraph holds — no node originates it. The
     * entry points where a caller chooses a reason enforce that rather than ask for it: {@code LiveSession.interrupt}
     * and {@code SessionRouter.interrupt} refuse this value ({@link #requireGivable(InterruptReason)}).
     */
    UNKNOWN;

    /**
     * Refuses {@link #UNKNOWN} as a reason a caller gives for an interrupt.
     *
     * <p>
     * For the entry points where application code chooses the reason. A reason given here ends up in the terminal
     * frame other nodes read, and a node on a build that predates {@code UNKNOWN} drops a frame carrying that name.
     *
     * @param reason
     *            the reason a caller supplied (must not be null)
     * @return {@code reason}, for chaining
     * @throws IllegalArgumentException
     *             if {@code reason} is {@link #UNKNOWN}
     */
    public static InterruptReason requireGivable(InterruptReason reason) {
        if (reason == UNKNOWN) {
            throw new IllegalArgumentException("UNKNOWN is what a decoder reads a peer's unrecognised reason as; it"
                    + " cannot be given as the reason for an interrupt");
        }
        return reason;
    }

    /**
     * Reads a reason another node wrote, without failing on one this build does not define.
     *
     * <p>
     * For decoders of names a peer wrote. Not for parsing a caller's input into a reason to interrupt with: the
     * forgiving result for a misspelled name is {@link #UNKNOWN}, which the interrupt entry points refuse.
     *
     * @param name
     *            the name as written by {@link #name()}; may be null, or a name this build does not define
     * @return the named reason, or {@link #UNKNOWN} when the name is absent or unknown (never null)
     */
    public static InterruptReason fromWireName(String name) {
        if (name != null) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                // Fall through: a name from a newer build is an expected input, not corruption.
            }
        }
        return UNKNOWN;
    }
}
