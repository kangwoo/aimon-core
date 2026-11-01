package at.aimon.core.agent.tool;

/**
 * Whether calling a {@link Tool} again with the same input is equivalent to calling it once, for a tool that writes.
 *
 * <p>
 * This is the second question the removed {@code IDEMPOTENT} rung of {@link SideEffectLevel} used to fold into the
 * ladder: not <em>does</em> the tool write, but is a <em>replay</em> of its write safe. Like
 * {@link DestructiveBehavior} it is an unordered trait, and the two are independent of each other — {@code rm -rf} is
 * idempotent and destructive, scheduling a task is neither (see {@code docs/design/tool/side-effect-axes.md} §2).
 * Idempotency is convergence, not safety.
 *
 * <h2>Only meaningful for a tool that writes</h2>
 *
 * <p>
 * Consumers read this declaration <b>only when the tool declares {@link SideEffectLevel#MUTATING}</b>. A
 * {@link SideEffectLevel#READ_ONLY} tool changes nothing, so repeating it changes nothing either, and is always safe to
 * repeat.
 *
 * <h2>The default is the conservative end</h2>
 *
 * <p>
 * {@link #NON_IDEMPOTENT} is the default, matching MCP's {@code idempotentHint} (default {@code false}) and the
 * conservative defaults of the other two axes: a writing tool nobody has audited is assumed to do its write again each
 * time it is called.
 *
 * <h2>Who reads it</h2>
 *
 * <p>
 * {@code RoutineExecutor} does: a scheduled step whose tool is {@code MUTATING} and {@code NON_IDEMPOTENT} is run once
 * and never retried, whatever its {@code max_retries} says. An attempt that timed out or threw may still have done its
 * write, so retrying it can do that write again — a second message sent, a second change applied.
 *
 * @see Tool#getIdempotencyBehavior()
 * @see SideEffectLevel
 * @see DestructiveBehavior
 */
public enum IdempotencyBehavior {

    /**
     * The default. Calling the tool again with the same input may do its write again — or nobody has checked.
     */
    NON_IDEMPOTENT,

    /**
     * Calling the tool again with the same input leaves the world as one call would have: an upsert by key, a delete by
     * id, setting a value rather than incrementing it.
     *
     * <p>
     * Like {@link DestructiveBehavior#NON_DESTRUCTIVE} this is a claim about the tool, not about one call's arguments.
     * A tool that is idempotent only for some inputs is {@link #NON_IDEMPOTENT}.
     */
    IDEMPOTENT
}
