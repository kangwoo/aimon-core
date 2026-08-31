package at.aimon.core.hook.execution;

/**
 * Tri-state decision carried by a {@link HookResult}.
 *
 * <p>
 * Pre-tool hooks emit a decision describing whether the wrapped tool invocation should proceed:
 * <ul>
 * <li>{@link #ALLOW} &mdash; let the tool execute as-is.
 * <li>{@link #ASK} &mdash; defer to the user (interactive confirm). When no interactive surface exists, the
 * dispatcher falls back to {@code AIMON_HOOK_ASK_DEFAULT} (default {@code deny}).
 * <li>{@link #DENY} &mdash; block the tool invocation; the result feedback carries the deny reason.
 * </ul>
 *
 * <p>
 * Decisions from multiple hooks merge with strict precedence {@code DENY > ASK > ALLOW} &mdash; see
 * {@link HookResult#merge(HookResult...)}.
 *
 * <p>
 * Post-tool / lifecycle hooks always emit {@link #ALLOW} &mdash; their decision field is informational only.
 */
public enum Decision {
    /** Let the tool execute. */
    ALLOW,

    /** Ask the user to confirm. Resolved at dispatch time by the CLI / interactive layer. */
    ASK,

    /** Block the tool invocation; the dispatcher must surface the deny reason to the LLM. */
    DENY;

    /**
     * Merges two decisions using {@code DENY > ASK > ALLOW} precedence.
     *
     * @param a
     *            left-hand decision (must not be null)
     * @param b
     *            right-hand decision (must not be null)
     * @return the more restrictive of the two (never null)
     */
    public static Decision max(Decision a, Decision b) {
        if (a == DENY || b == DENY) {
            return DENY;
        }
        if (a == ASK || b == ASK) {
            return ASK;
        }
        return ALLOW;
    }
}
