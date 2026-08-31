package at.aimon.core.command.execution.direct;

import java.time.Instant;
import java.util.Objects;

import at.aimon.core.command.Command;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionRequest;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.CommandExecutor;
import at.aimon.core.command.execution.CompositeCommandExecutor;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.llm.TokenUsage;

/**
 * Executor for {@link DirectExecutable} implementations.
 *
 * <p>
 * This executor handles commands that execute directly using Java code without LLM interaction. It provides a
 * consistent execution interface and handles common concerns like error handling and validation.
 *
 * <p>
 * Execution flow:
 *
 * <ol>
 * <li>Validate command implements DirectExecutable
 * <li>Delegate to {@link DirectExecutable#execute(CommandExecutionContext, DirectCommandExecutionRequest)}
 * <li>Return execution result
 * </ol>
 *
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     CommandExecutor executor = new DirectCommandExecutor();
 *
 *     // Execute a direct command (e.g., /help)
 *     Command helpCommand = new HelpCommand();
 *     CommandExecutionResult result = executor.execute(helpCommand, "");
 *
 *     // Prints help output immediately without LLM call
 *     System.out.println(result.getResponse());
 * }
 * </pre>
 *
 * @see DirectExecutable
 * @see CompositeCommandExecutor
 */
public final class DirectCommandExecutor implements CommandExecutor {

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, CommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        // Track execution start time
        final Instant startTime = Instant.now();

        final Command command = context.getCommand();

        // Validate command type
        if (!(command instanceof DirectExecutable directExecutable)) {
            final String message = String.format(
                    "Command '%s' is not a DirectExecutable. Expected DirectExecutable but got %s", command.getName(),
                    command.getClass().getSimpleName());
            final Instant endTime = Instant.now();
            final ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(0)
                    .tokenUsage(TokenUsage.empty()).timestamps(startTime, endTime).build();
            return CommandExecutionResult.failure(message, new IllegalArgumentException(message), metadata);
        }

        try {
            // Build DirectCommandExecutionRequest from CommandExecutionRequest
            // Convert List<String> arguments to single String (for direct commands)
            final String argsString = request.getArguments().isEmpty()
                    ? null
                    : String.join(" ", request.getArguments());

            final DirectCommandExecutionRequest directRequest = DirectCommandExecutionRequest.builder()
                    .arguments(argsString).principal(request.getPrincipal().orElse(null))
                    .previousSnapshot(request.getPreviousSnapshot().orElse(null)).build();

            // Execute the direct command
            final CommandExecutionResult result = directExecutable.execute(context, directRequest);

            // Add metadata if not already present
            final Instant endTime = Instant.now();
            final ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(0)
                    .tokenUsage(TokenUsage.empty()).timestamps(startTime, endTime).build();

            // If result already has metadata (rare), preserve it; otherwise add ours
            if (result.getMetadata().isEmpty()) {
                // Wrap result with metadata
                if (result.isSuccess()) {
                    return CommandExecutionResult.success(result.getResponse(), metadata);
                } else {
                    return CommandExecutionResult.failure(result.getResponse(),
                            result.getError().orElse(new RuntimeException("Unknown error")), metadata);
                }
            }

            return result; // Already has metadata

        } catch (Exception e) {
            // Handle unexpected errors during execution
            final String message = String.format("Failed to execute direct command '%s': %s", command.getName(),
                    e.getMessage());
            final Instant endTime = Instant.now();
            final ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(0)
                    .tokenUsage(TokenUsage.empty()).timestamps(startTime, endTime).build();
            return CommandExecutionResult.failure(message, e, metadata);
        }
    }

    @Override
    public String toString() {
        return "DirectCommandExecutor{}";
    }
}
