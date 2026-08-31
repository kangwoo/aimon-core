package at.aimon.core.agent.queue;

/**
 * Priority tier assigned to a {@link QueuedInput}.
 *
 * <p>
 * Within a tier, ordering is FIFO (insertion order). Across tiers, the repository surfaces higher-priority entries
 * first. The declaration order of this enum encodes that ordering: {@link #NOW} is the highest priority, {@link #LATER}
 * is the lowest. Do not reorder the constants — callers rely on {@link Enum#ordinal()} for "at most priority X"
 * comparisons.
 */
public enum QueuedInputPriority {

    /**
     * Inject as soon as possible — typically at the next iteration boundary of the currently running agent.
     *
     * <p>
     * Use for inputs the user considers urgent (e.g. corrections, stop-this-and-do-that instructions).
     */
    NOW,

    /**
     * Deliver at the end of the current turn, before the agent signals completion to the user.
     *
     * <p>
     * This is the default priority for messages typed by the user while the agent is still mid-response. The agent
     * finishes the in-flight step, then consumes {@code NEXT}-tier messages as fresh user turns.
     */
    NEXT,

    /**
     * Deferred — only consumed when a caller explicitly asks for it (for example by passing {@link #LATER} as the
     * {@code maxPriority} parameter to
     * {@link MessageQueueRepository#listByMaxPriority(QueuedInputPriority, java.util.function.Predicate)}).
     *
     * <p>
     * Appropriate for background reminders or low-priority follow-ups that should not pre-empt an active turn.
     */
    LATER
}
