package at.aimon.core.agent.impl.orca;

import at.aimon.core.hook.HookRegistry;

/**
 * Strategy interface for registering hooks into an agent runtime's {@link HookRegistry}.
 *
 * <p>
 * Implementations register one or more hooks (OnStart, PreTool, PostTool, OnStop) during agent runtime creation.
 * This allows decoupling hook registration logic from the context manager itself.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentRuntimeHookRegistrar registrar = hookRegistry -> {
 *         hookRegistry.register(HookEventType.ON_START, new AuditOnStartHook());
 *         hookRegistry.register(HookEventType.POST_TOOL, new LoggingPostToolHook());
 *     };
 * }
 * </pre>
 */
@FunctionalInterface
public interface AgentRuntimeHookRegistrar {

    /**
     * Registers hooks into the given hook registry.
     *
     * @param hookRegistry
     *            the hook registry to register hooks into (never null)
     */
    void register(HookRegistry hookRegistry);
}
