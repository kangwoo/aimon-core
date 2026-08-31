package at.aimon.core.hook.rewake;

import at.aimon.core.agent.Environment;
import at.aimon.core.hook.HookRegistry;

/**
 * Marker SPI implemented by {@link at.aimon.core.agent.AgentRuntime AgentRuntime} subtypes that can
 * be re-hydrated by a {@link RewakeFireListener} when a scheduled envelope fires.
 *
 * <p>
 * The base {@code AgentRuntime} interface deliberately stays minimal — exposing only the agent and tools.
 * Re-dispatching a hook on a rewake fire needs a couple more pieces: the {@link HookRegistry} (to locate the
 * originating hook) and the {@link Environment} (to thread runtime state back into the rebuilt hook context). Rather
 * than promote those concerns onto the base interface and force every existing impl / test stub to grow, the rewake
 * listener queries this opt-in SPI via
 * {@link at.aimon.core.agent.AgentRuntimeRegistry#getAs(at.aimon.core.agent.AgentRuntimeId, Class)
 * registry.getAs(...)} and skips contexts that do not support it (logged WARN).
 *
 * <p>
 * Production implementations (notably {@code OrcaAgentRuntime}) implement this interface; lightweight test
 * stubs may opt out and the rewake listener simply drops their fires — rewake is best-effort delivery, not a
 * guarantee.
 */
public interface RewakeCapableRuntime {

    /**
     * @return the agent's hook registry (never null) — used to look up the originating hook by id on a fire
     */
    HookRegistry getHookRegistry();

    /**
     * @return the agent's runtime environment (never null) — threaded into the rebuilt hook context so re-dispatched
     *         hooks observe the same environment as the original firing
     */
    Environment getEnvironment();
}
