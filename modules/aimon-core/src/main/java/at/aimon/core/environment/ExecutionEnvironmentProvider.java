package at.aimon.core.environment;

/**
 * Resolves the {@link ExecutionEnvironment} an execution's tools and prompt assembly use.
 *
 * <p>
 * Executors call {@link #resolve} <b>once at the start of each execution</b>, before prompt assembly and before any
 * tool call. A provider owns the shells, filesystems and connections behind the environments it returns and decides
 * their lifetime; an {@code AgentRuntime} never closes them. A provider that also implements {@link AutoCloseable} is
 * closed by whoever assembled it.
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
}
