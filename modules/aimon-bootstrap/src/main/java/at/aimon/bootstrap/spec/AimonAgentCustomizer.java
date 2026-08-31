package at.aimon.bootstrap.spec;

import java.util.List;

import at.aimon.core.agent.impl.orca.command.OrcaCommandProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.hook.HookRegistry;

/**
 * Contributes tools, commands and hooks to the agents it selects.
 *
 * <p>
 * This is the answer to "agent A needs the ticketing tools and agent B does not". The other answer — assembling
 * a provider list per agent at the call site — is what this exists to prevent: in a host with a session path and
 * a scheduling path, per-agent lists get written twice, and the two copies drift into a deployment where a tool
 * works in chat and is missing from the cron run. Customizers are collected once, asked
 * {@link #supports(AgentDescriptor)} for every runtime the stack builds, and the base provider list is assembled
 * in exactly one place.
 *
 * <h2>They run for tenant runtimes too</h2>
 *
 * <p>
 * A customizer is consulted for {@code agent:ops} at startup and again for {@code agent:ops:acme} the first time
 * that tenant appears — the whole point being that the two cannot end up with different tools. That also means
 * {@link #supports(AgentDescriptor)} is called on request threads, and possibly for several tenants at once:
 * implementations must be thread-safe and must not assume they see each agent once.
 *
 * <h2>Order is not incidental</h2>
 *
 * <p>
 * Hooks run in registration order, so the order customizers are applied in decides which hook sees a tool call
 * first. Injection order of a {@code List<AimonAgentCustomizer>} is not stable enough to rely on — it follows
 * bean definition order, which changes when a configuration class is renamed — so the stack sorts by
 * {@link #getOrder()} and leaves equal orders in the order they were given.
 */
public interface AimonAgentCustomizer {

    /**
     * Decides whether this customizer applies to the runtime being built.
     *
     * <p>
     * Match on {@link AgentDescriptor#getAgentRef()} for "this agent", on
     * {@link AgentDescriptor#getBundleName()} for "every agent built from this bundle", and read
     * {@link AgentDescriptor#getDiscriminator()} when the contribution is tenant-specific — remembering that it
     * is absent for the startup runtime.
     *
     * @param agent
     *            the runtime being built (never null)
     * @return {@code true} to contribute to this runtime
     */
    boolean supports(AgentDescriptor agent);

    /**
     * Returns tool providers to add to the base list for this runtime.
     *
     * <p>
     * Called once per runtime, and the providers are appended to the stack's own — nothing is replaced, so a
     * customizer cannot remove a tool another one added.
     *
     * @param agent
     *            the runtime being built (never null)
     * @return the providers, never null
     */
    default List<OrcaToolProvider> toolProviders(AgentDescriptor agent) {
        return List.of();
    }

    /**
     * Returns slash-command providers to add to the base list for this runtime.
     *
     * @param agent
     *            the runtime being built (never null)
     * @return the providers, never null
     */
    default List<OrcaCommandProvider> commandProviders(AgentDescriptor agent) {
        return List.of();
    }

    /**
     * Registers hooks on the new runtime's registry.
     *
     * <p>
     * Called after the runtime is built and before it is reachable, so a hook registered here cannot miss a turn
     * that started in between. The registry belongs to the runtime — registering on it does not affect any other
     * agent or tenant.
     *
     * @param agent
     *            the runtime being built (never null)
     * @param hooks
     *            that runtime's hook registry (never null)
     */
    default void registerHooks(AgentDescriptor agent, HookRegistry hooks) {
        // Most customizers contribute tools only.
    }

    /**
     * Returns the sort key. Lower runs first; equal orders keep the order they were supplied in.
     *
     * @return the order, 0 by default
     */
    default int getOrder() {
        return 0;
    }
}
