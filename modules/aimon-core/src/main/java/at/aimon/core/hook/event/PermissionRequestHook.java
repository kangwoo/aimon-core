package at.aimon.core.hook.event;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Hook fired before a tool dispatcher checks whether the caller is allowed to use a tool.
 *
 * <p>
 * Permission hooks complement {@link PreToolHook} — both run before tool execution, but permission hooks model an
 * authorization layer that fires earlier and may surface an interactive {@code ASK} prompt before the
 * input-mutation-capable PreTool chain runs. They return:
 * <ul>
 * <li>{@link at.aimon.core.hook.execution.HookResult#allow()} — pass through to the rest of the dispatch.
 * <li>{@link at.aimon.core.hook.execution.HookResult#ask(String)} — defer to the configured
 * {@link at.aimon.core.hook.execution.AskPromptHandler} for an interactive confirmation.
 * <li>{@link at.aimon.core.hook.execution.HookResult#deny(String)} — short-circuit the dispatch and fire any registered
 * {@link PermissionDeniedHook} chain.
 * </ul>
 *
 * <p>
 * Multiple permission hooks merge with the standard {@code DENY > ASK > ALLOW} precedence.
 *
 * <p>
 * Implementations must be thread-safe and side-effect free outside of logging / metric emission.
 */
@FunctionalInterface
public interface PermissionRequestHook extends ExecutionHook<PermissionRequestContext> {
    // Inherits execute(PermissionRequestContext) from ExecutionHook
}
