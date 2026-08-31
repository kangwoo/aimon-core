package at.aimon.core.command.execution;

/**
 * Executes commands with the given context and request.
 *
 * <p>
 * This interface defines the core contract for command execution. Implementations are responsible for:
 *
 * <ul>
 * <li>Processing command content and metadata
 * <li>Handling argument interpolation
 * <li>Managing execution context
 * <li>Returning execution results
 * </ul>
 *
 * @see CommandExecutionResult
 * @see CommandExecutionContext
 * @see CommandExecutionRequest
 */
public interface CommandExecutor {

    /**
     * Executes a command with the given context and request.
     *
     * @param context
     *            The execution context containing model config and available tools (must not be null)
     * @param request
     *            The execution request containing command and arguments (must not be null)
     * @return The execution result (never null)
     * @throws NullPointerException
     *             if context or request is null
     */
    CommandExecutionResult execute(CommandExecutionContext context, CommandExecutionRequest request);
}
