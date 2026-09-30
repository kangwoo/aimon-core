package at.aimon.core.tools;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;

/**
 * Static accessors that tools use to read the execution's {@link ExecutionEnvironment} out of a {@link ToolContext}.
 *
 * <p>
 * Tools never hold a filesystem or shell from their constructors; they call {@link #require(ToolContext)} inside
 * {@code execute()} and turn the {@link IllegalStateException} it throws into a {@code ToolResult.error}. There is no
 * fallback to a default environment.
 */
public final class ExecutionEnvironmentAccess {

    /** The message of the exception {@link #require(ToolContext)} throws when the key is absent. */
    public static final String NO_ENVIRONMENT_MESSAGE = "No execution environment in tool context";

    private ExecutionEnvironmentAccess() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the execution's environment.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the environment
     * @throws IllegalStateException
     *             if the context carries no environment
     */
    public static ExecutionEnvironment require(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(ToolContextKeys.EXECUTION_ENVIRONMENT)
                .orElseThrow(() -> new IllegalStateException(NO_ENVIRONMENT_MESSAGE));
    }

    /**
     * Returns the execution's environment, if the context carries one.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the environment, or empty
     */
    public static Optional<ExecutionEnvironment> of(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(ToolContextKeys.EXECUTION_ENVIRONMENT);
    }

    /**
     * Returns the provider that resolved the execution's environment, for code that spawns a fork.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the provider, or empty
     */
    public static Optional<ExecutionEnvironmentProvider> providerOf(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(ToolContextKeys.EXECUTION_ENVIRONMENT_PROVIDER);
    }
}
