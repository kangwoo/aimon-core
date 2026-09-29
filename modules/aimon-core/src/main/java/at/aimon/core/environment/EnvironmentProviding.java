package at.aimon.core.environment;

/**
 * Implemented by an agent runtime that carries an {@link ExecutionEnvironmentProvider}.
 *
 * <p>
 * The accessor lives here rather than on {@code AgentRuntime} so that the agent core does not depend on this package;
 * code that holds only an {@code AgentRuntime} (scheduled routines) checks for this interface. A runtime that does not
 * implement it gets an {@link UnavailableExecutionEnvironment}.
 */
public interface EnvironmentProviding {

    /**
     * Returns the provider that resolves this runtime's execution environments.
     *
     * @return the provider, or {@code null} if none is configured
     */
    ExecutionEnvironmentProvider getExecutionEnvironmentProvider();
}
