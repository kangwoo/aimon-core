package at.aimon.core.hook.execution;

/**
 * Status values for hook execution.
 *
 * <p>
 * This extends the legacy two-state outcome with an explicit {@link #ASK} state. The status is now a
 * derived projection of {@link Decision}:
 *
 * <ul>
 * <li><b>SUCCESS</b> &harr; {@link Decision#ALLOW} — Hook executed successfully, continue execution.
 * <li><b>ASK</b> &harr; {@link Decision#ASK} — Hook defers to the user. Resolved at dispatch time by the CLI /
 * interactive layer; falls back to {@code AIMON_HOOK_ASK_DEFAULT} (default {@code deny}) when no interactive surface
 * exists.
 * <li><b>BLOCKED</b> &harr; {@link Decision#DENY} — Hook blocked the operation. Only applicable for PreToolHook;
 * causes the tools execution to be skipped and an error message to be sent to the LLM.
 * </ul>
 */
public enum HookStatus {
    /** Hook executed successfully, continue execution. */
    SUCCESS,

    /**
     * Hook deferred the decision to the user.
     *
     * <p>
     * Only applicable for PreToolHook. The dispatcher promotes ASK to {@link #SUCCESS} (allow) or
     * {@link #BLOCKED} (deny) via the configured ask-prompt handler.
     */
    ASK,

    /**
     * Hook blocked the operation.
     *
     * <p>
     * Only applicable for PreToolHook. Causes the tools execution to be skipped and an error message to be sent to the
     * LLM.
     */
    BLOCKED
}
