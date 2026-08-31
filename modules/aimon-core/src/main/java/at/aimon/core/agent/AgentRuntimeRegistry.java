package at.aimon.core.agent;

import java.util.Optional;

/**
 * Registry for managing agent-scoped {@link AgentRuntime} instances.
 *
 * <p>
 * Acts as the application-wide lookup table for agent-scoped runtimes. Each entry corresponds to a single
 * {@code (Agent, discriminator)} pair and is registered <b>once</b> at bootstrap (or on agent hot-reload) and
 * unregistered <b>only</b> when the agent is removed or the application shuts down — never on per-session /
 * per-session lifecycle events.
 *
 * <p>
 * Primary consumers are:
 * <ul>
 * <li>the scheduling engine, which resolves the agent context bound to a {@code ScheduledTask} on cron re-fires (the
 * agent-scoped contract is what makes those re-fires safe),</li>
 * <li>the wiki / knowledge layer, which resolves the agent context to obtain its
 * {@link at.aimon.core.filesystem.VirtualFileSystem
 * VirtualFileSystem}, and</li>
 * <li>session bootstraps, which look up the shared context rather than creating per-session instances.</li>
 * </ul>
 *
 * <p>
 * Implementations must be thread-safe to support concurrent registration and lookup from different threads. That
 * guarantee is <b>per call</b> and does not extend to the get-then-register pair every caller has to write, since
 * this interface offers no atomic "register if absent" — see {@link #register(AgentRuntime)} for what a caller
 * owes as a result.
 *
 * @see AgentRuntime
 * @see AgentRuntimeId
 */
public interface AgentRuntimeRegistry {

    /**
     * Registers an agent runtime.
     *
     * <p>
     * <b>Registering an id that is already present replaces the previous entry and does not close it.</b> The
     * registry is a lookup table, not an owner — it never closes what it hands out or what it drops, so an
     * overwritten runtime keeps whatever it holds open (MCP clients, pools) with nobody left holding a reference
     * to release them.
     *
     * <p>
     * There is no atomic register-if-absent here, so <b>the caller is responsible for making its own
     * get-then-register sequence exclusive for a given id.</b> The three in-tree callers do it three different
     * ways, and only two of them do it with a lock:
     * <ul>
     * <li>{@code OrcaAgentRuntimeManager} runs the pair under a per-id monitor,</li>
     * <li>{@code AgentRuntimeResolver} reserves the id under its own lock before provisioning, and</li>
     * <li>{@code AimonStack.startRuntimes()} holds no lock at all. It is exclusive because of <i>when</i> it runs:
     * it registers every declared runtime before the host opens its inbound port and before the scheduler starts,
     * so nothing else has asked for those ids yet — and the resolver refuses to create a declared id on demand, so
     * nothing else can take one while the window is open.</li>
     * </ul>
     *
     * <p>
     * A new registrant must bring its own exclusion — the thread-safety of the individual calls does not give it
     * any, and neither does the fact that today's three do not collide.
     *
     * <p>
     * It has to bring its own way of being listed, too. <b>This interface has no enumeration, and nothing that
     * lists a deployment's runtimes goes through it:</b> a host asks {@code AimonStack.agentDescriptors()} (or the
     * starter's {@code AimonAgents.list()}) for the agents it declared, and {@code AgentRuntimeResolver.trackedIds()}
     * for the tenant runtimes a node is holding right now. Between them those two cover everything a stack
     * registers, which is why the missing method here has never blocked anyone. A registrant outside that pair
     * lands in this registry and in neither view, so what it registers is invisible to every operational surface
     * built over them — publish it somewhere before assuming an operator can see it.
     *
     * @param context
     *            the agent runtime to register
     * @throws NullPointerException
     *             if context is null
     */
    void register(AgentRuntime context);

    /**
     * Unregisters an agent runtime by its ID.
     *
     * @param agentRuntimeId
     *            the ID of the context to unregister
     * @throws NullPointerException
     *             if agentRuntimeId is null
     */
    void unregister(AgentRuntimeId agentRuntimeId);

    /**
     * Looks up an agent runtime by its ID.
     *
     * @param agentRuntimeId
     *            the ID of the context to look up
     * @return the agent runtime, or empty if not found
     * @throws NullPointerException
     *             if agentRuntimeId is null
     */
    Optional<AgentRuntime> get(AgentRuntimeId agentRuntimeId);

    /**
     * Looks up an agent runtime by its ID and casts it to the specified type.
     *
     * <p>
     * Returns empty if the context is not found or is not an instance of the specified type.
     *
     * @param <T>
     *            the expected context type
     * @param agentRuntimeId
     *            the ID of the context to look up
     * @param type
     *            the expected class of the context
     * @return the agent runtime cast to the specified type, or empty if not found or not assignable
     * @throws NullPointerException
     *             if agentRuntimeId or type is null
     */
    default <T extends AgentRuntime> Optional<T> getAs(AgentRuntimeId agentRuntimeId, Class<T> type) {
        return get(agentRuntimeId).filter(type::isInstance).map(type::cast);
    }
}
