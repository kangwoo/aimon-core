package at.aimon.spring.boot;

import java.util.List;

import at.aimon.bootstrap.spec.AgentDescriptor;

/**
 * What agents this deployment has, and how to make a tenant's runtime be rebuilt.
 *
 * <p>
 * The companion to {@link AimonSessions}: that one runs turns, this one answers questions about the agents the
 * turns run on. Two beans rather than one because the audiences differ — a controller injects
 * {@code AimonSessions} and never needs this, while an admin endpoint or a tenant-provisioning listener needs
 * this and often submits nothing.
 *
 * <h2>Listing is not the same as enumerating runtimes</h2>
 *
 * <p>
 * {@link #list()} answers "which agents does this deployment have", which is finite and comes from the
 * configuration. It deliberately does not report tenant runtimes: that set grows with traffic, has no
 * upper bound a UI can render, and is a capacity fact rather than a configuration one. Reach through to
 * {@code AimonStack.agentRuntimes()} for that.
 *
 * <h2>Invalidation is for facts that changed underneath a runtime</h2>
 *
 * <p>
 * A tenant runtime caches what it was built from — its tool registry, its MCP connections, whatever credentials
 * its customizers baked in. When one of those changes outside the process (a tenant rotates a key, an operator
 * revokes an integration), the runtime keeps serving the old one until something says otherwise, and that is
 * what {@link #invalidate(String, String)} is. It is not an eviction knob: idle runtimes are already reclaimed
 * by {@code aimon.agent-runtime.*}, and calling this to free memory just makes the next request pay for a
 * rebuild.
 *
 * <p>
 * Invalidating never interrupts a turn. A runtime with work in flight is unregistered immediately — so the next
 * submit builds a fresh one — and closed when its last holder releases.
 *
 * <p>
 * The startup runtimes of the declared agents are not invalidatable, and a call naming one is a no-op rather
 * than an error. They are what the stack's fail-fast check ran against; rebuilding one on demand would put a
 * runtime into service that never faced that check.
 */
public interface AimonAgents {

    /**
     * Returns what each configured agent was built from, in declaration order.
     *
     * <p>
     * These are the same descriptors every {@code AimonAgentCustomizer} was asked about, so a host that lists
     * agents sees the values its customizers matched on rather than a second rendering of the configuration
     * that could disagree with them.
     *
     * @return one descriptor per configured agent; empty only when {@code aimon.enabled=false}
     */
    List<AgentDescriptor> list();

    /**
     * Drops one tenant's runtime for one agent, so the next request for it is served by a freshly built one.
     *
     * @param agentRef
     *            the agent ref — the map key under {@code aimon.agents} (must not be null or blank)
     * @param discriminator
     *            the tenant (must not be null or blank)
     */
    void invalidate(String agentRef, String discriminator);

    /**
     * Drops every tenant runtime of one agent.
     *
     * <p>
     * For changes that are the agent's rather than a tenant's — a redeployed bundle, a rotated shared
     * credential. The startup runtime of that agent is left alone.
     *
     * @param agentRef
     *            the agent ref (must not be null or blank)
     */
    void invalidate(String agentRef);
}
