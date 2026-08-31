package at.aimon.core.command.execution.direct;

import at.aimon.core.command.Command;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;

/**
 * Marker interface for commands that execute directly without LLM interaction.
 *
 * <p>
 * DirectExecutable represents the execution strategy for commands that:
 *
 * <ul>
 * <li>Execute immediately using Java code
 * <li>Do not require LLM processing
 * <li>Return results synchronously
 * <li>Have full access to execution context (transcript, configuration, etc.)
 * </ul>
 *
 * <p>
 * This interface defines HOW a command executes, not WHAT kind of command it is. Commands implement this interface
 * alongside {@link Command} to indicate they use direct execution rather than LLM-based execution.
 *
 * <p>
 * Typical use cases include:
 *
 * <ul>
 * <li>System operations: /clear, /help, /version
 * <li>Configuration commands: /config set, /config get
 * <li>State management: /history, /reset
 * </ul>
 *
 * <p>
 * Example implementation:
 *
 * <pre>
 * {
 *     &#64;code
 *     public final class HelpCommand extends SystemCommand implements DirectExecutable {
 *         private final CommandRegistry registry;
 *
 *         public HelpCommand(CommandRegistry registry) {
 *             this.registry = registry;
 *         }
 *
 *         &#64;Override
 *         public CommandExecutionResult execute(CommandExecutionContext context,
 *                 DirectCommandExecutionRequest request) {
 *             // Generate help text directly
 *             String helpText = generateHelpText(registry);
 *             return CommandExecutionResult.success(helpText);
 *         }
 *
 *         private String generateHelpText(CommandRegistry registry) {
 *             // Access command registry, build help output
 *             // ...
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>
 * Thread-safety: Implementations should be thread-safe or explicitly document their threading requirements.
 *
 * @see Command
 * @see SystemCommand
 * @see CommandExecutionContext
 * @see DirectCommandExecutionRequest
 */
public interface DirectExecutable {

    /**
     * Executes the command directly using Java code.
     *
     * <p>
     * This method is called instead of LLM processing when the command implements DirectExecutable. It receives:
     *
     * <ul>
     * <li>Context: Runtime execution context (model config, tools, iterations)
     * <li>Request: Command arguments, user info, session snapshot
     * </ul>
     *
     * <p>
     * Dependencies like CommandRegistry, TranscriptBuffer, etc. should be injected through the command's constructor,
     * not passed at execution time.
     *
     * <p>
     * Implementation guidelines:
     *
     * <ul>
     * <li>Parse arguments appropriately (may be empty)
     * <li>Perform the command logic synchronously
     * <li>Return success or failure with appropriate messages
     * <li>Handle exceptions gracefully
     * </ul>
     *
     * <p>
     * Example:
     *
     * <pre>
     * {@code
     * &#64;Override
     * public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
     *     try {
     *         // Parse arguments
     *         String arguments = request.getArguments().orElse("");
     *         String[] args = arguments.trim().split("\\s+");
     *
     *         // Execute logic (using constructor-injected dependencies)
     *         String result = performOperation(args);
     *
     *         // Return success
     *         return CommandExecutionResult.success(result);
     *
     *     } catch (IllegalArgumentException e) {
     *         // Invalid arguments
     *         return CommandExecutionResult.failure("Invalid arguments: " + e.getMessage());
     *
     *     } catch (Exception e) {
     *         // Unexpected error
     *         return CommandExecutionResult.failure("Command failed: " + e.getMessage(), e);
     *     }
     * }
     * }
     * </pre>
     *
     * @param context
     *            The execution context with runtime configuration (must not be null)
     * @param request
     *            The execution request with arguments and user info (must not be null)
     * @return The execution result containing output or error information (never null)
     * @throws NullPointerException
     *             if context or request is null
     */
    CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request);
}
