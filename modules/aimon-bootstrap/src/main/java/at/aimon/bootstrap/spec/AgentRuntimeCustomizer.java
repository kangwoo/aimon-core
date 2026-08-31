package at.aimon.bootstrap.spec;

import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;

/**
 * Adjusts a freshly created {@link OrcaAgentRuntime} before anything can execute against it.
 *
 * <p>
 * A front end almost always has something to add that the stack has no business knowing about: tools whose
 * output goes to a terminal, hooks that render tool calls as they happen, an interactive display of subagent
 * launches. All of it is registered on the runtime's own registries, and all of it is specific to the shell the
 * agent is embedded in.
 *
 * <p>
 * The stack calls this at the one point where that is safe — after {@code OrcaAgentRuntimeFactory.create(...)}
 * returns, and before the runtime is published to the {@code AgentRuntimeRegistry}. Registering a tool after
 * publication is a race: a scheduled task or another node's session can resolve the runtime and start a turn
 * against a half-configured tool registry.
 *
 * <p>
 * Throwing from here aborts assembly. Everything the stack has built so far is torn down in
 * {@link at.aimon.bootstrap.TeardownPhase} order before the exception surfaces, so a customizer that fails does
 * not leak a runtime, a thread pool, or a file system.
 */
@FunctionalInterface
public interface AgentRuntimeCustomizer {

    /**
     * Customizes the runtime.
     *
     * @param runtime
     *            the newly created runtime, not yet registered (never null)
     */
    void customize(OrcaAgentRuntime runtime);
}
