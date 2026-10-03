package at.aimon.core.environment;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Resolves the {@link ExecutionEnvironment} an execution's tools and prompt assembly use.
 *
 * <p>
 * Executors call {@link #resolve} <b>once at the start of each execution</b>, before prompt assembly and before any
 * tool call. A provider owns the shells, filesystems and connections behind the environments it returns and decides
 * their lifetime; an {@code AgentRuntime} never closes them. A provider that also implements {@link AutoCloseable} is
 * closed by whoever assembled it — which is the runtime itself only when the runtime was built from a per-runtime
 * provider function ({@code OrcaAgentRuntimeFactory.withExecutionEnvironmentProviderFactory}), since that caller has
 * no other moment to close it.
 *
 * <p>
 * <b>One provider usually outlives many runtimes.</b> A provider shared by an application's runtimes picks the
 * workspace from {@link EnvironmentRequest#agentRuntimeId()}, and is closed when the application stops — not when one
 * tenant's runtime is evicted, which would cut off the background commands that runtime left running and every other
 * runtime besides. What such a provider holds for a single runtime it learns to release through {@link #bindRuntime}.
 *
 * <p>
 * A provider must not fall back to a host environment when it cannot answer: it throws, and the executor publishes an
 * {@link UnavailableExecutionEnvironment} carrying the cause.
 */
public interface ExecutionEnvironmentProvider {

    /**
     * Resolves the environment for one execution.
     *
     * @param request
     *            who is executing and, for forks and branches, the parent environment (must not be null)
     * @return the environment (never null)
     */
    ExecutionEnvironment resolve(EnvironmentRequest request);

    /**
     * Tells the provider that a runtime with this id is being built, and returns the handle whose
     * {@link RuntimeBinding#close() close} tells it that this runtime is gone.
     *
     * <p>
     * Called by the <em>assembly</em> that builds runtimes over a shared provider (the bootstrap stack), once per
     * runtime, before the runtime's first execution; the runtime itself never calls it. The same id may be bound more
     * than once at a time — see {@link RuntimeBinding}. A provider that keeps nothing per runtime inherits this
     * default. {@link #resolve} must keep working for a runtime id nobody bound: not every assembly binds.
     *
     * @param agentRuntimeId
     *            the runtime being built (must not be null)
     * @return the binding (never null)
     * @throws RuntimeException
     *             if what the runtime needs cannot be prepared; the assembly then fails to build the runtime
     */
    default RuntimeBinding bindRuntime(AgentRuntimeId agentRuntimeId) {
        return RuntimeBinding.NONE;
    }
}
