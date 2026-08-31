package at.aimon.core.hook.execution;

/**
 * Coarse flow-control flag carried by a {@link HookResult}.
 *
 * <p>
 * Independent of {@link Decision}: a hook may {@link #BLOCK} the surrounding chain (e.g. shortcut parallel evaluation
 * once a deny is observed) without itself being a {@link Decision#DENY} (a fail-closed timeout, for example).
 *
 * <p>
 * Merge precedence is {@code BLOCK > CONTINUE} &mdash; see {@link HookResult#merge(HookResult...)}.
 */
public enum FlowControl {
    /** Continue with the rest of the hook chain. */
    CONTINUE,

    /** Short-circuit the chain. */
    BLOCK;

    /**
     * Merges two flow-control values using {@code BLOCK > CONTINUE} precedence.
     *
     * @param a
     *            left-hand value (must not be null)
     * @param b
     *            right-hand value (must not be null)
     * @return the more restrictive of the two (never null)
     */
    public static FlowControl max(FlowControl a, FlowControl b) {
        return (a == BLOCK || b == BLOCK) ? BLOCK : CONTINUE;
    }
}
