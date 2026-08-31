package at.aimon.bootstrap.runtime;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Builds one agent runtime on demand.
 *
 * <p>
 * There are exactly two places a runtime is created: the stack builder's startup pass over the declared agents,
 * and an {@link AgentRuntimeResolver} on the first request for a tenant. This interface is what makes them the
 * same code. A separate lazy path would be a second place where tool providers, skill registries and hooks are
 * assembled, and the failure mode of two such places is not a crash — it is a deployment where a feature works
 * for the agents named in configuration and is quietly missing for every tenant.
 *
 * <p>
 * Implementations must be thread-safe: several requests for the same new tenant can arrive at once. They do not
 * need to deduplicate, though — the resolver guarantees one provision call per id, so an implementation may
 * assume it is building something that does not exist yet.
 *
 * <p>
 * Failure is expected to be reported by throwing. The resolver drops the entry so a later request retries rather
 * than caching the failure; a provisioner that returns a half-built runtime instead would have that runtime
 * serve turns.
 */
@FunctionalInterface
public interface AgentRuntimeProvisioner {

    /**
     * Builds the runtime for {@code agentRuntimeId} and everything that must be closed with it.
     *
     * @param agentRuntimeId
     *            the id to build for; carries both the agent name and, for a tenant runtime, the discriminator
     * @return the runtime and its dependents, never null
     * @throws at.aimon.bootstrap.exception.UnknownAgentRuntimeException
     *             if the id names an agent this stack was not configured with
     */
    ProvisionedAgentRuntime provision(AgentRuntimeId agentRuntimeId);
}
