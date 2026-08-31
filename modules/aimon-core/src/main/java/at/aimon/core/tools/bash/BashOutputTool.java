package at.aimon.core.tools.bash;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Tool for retrieving output from background bash shells.
 *
 * <p>
 * This tools enables:
 *
 * <ul>
 * <li>Collecting the output of a long-running command
 * <li>Checking command status (running, completed, failed)
 * <li>Filtering output with regex patterns
 * <li>Blocking and non-blocking operation modes
 * </ul>
 *
 * <p>
 * Key features:
 *
 * <ul>
 * <li>Returns the output not yet consumed; each line is returned only once
 * <li>Filtered lines are discarded forever
 * <li>Blocking mode waits for completion
 * <li>Non-blocking mode returns immediately
 * </ul>
 *
 * <p>
 * <b>Non-blocking mode does not show progress.</b> A task's output buffer is filled in one batch when the command
 * finishes ({@link BackgroundBashTask} explains why), so {@code block=false} against a running task reports
 * {@code RUNNING} with an empty body — it answers "is it done yet", not "how far along is it". Reaching the output at
 * all means blocking, or polling until the status flips.
 *
 * <p>
 * Thread-safe for concurrent access.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     BackgroundBashManager manager = new BackgroundBashManager();
 *     BashOutputTool tools = new BashOutputTool(manager);
 *     ToolContext context = ToolContext.empty();
 *
 *     // Check progress (non-blocking)
 *     ToolInput checkInput = ToolInput.of(Map.of("taskId", "bash_abc123", "block", false));
 *     ToolResult result = tools.execute(checkInput, context);
 *
 *     // Wait for completion (blocking)
 *     ToolInput waitInput = ToolInput.of(Map.of("taskId", "bash_abc123", "block", true, "wait_up_to", 60));
 *     ToolResult finalResult = tools.execute(waitInput, context);
 * }
 * </pre>
 */
public class BashOutputTool extends AbstractTool {

    public static final String TOOL_NAME = "BashOutput";
    private static final int DEFAULT_WAIT_UP_TO = 150; // 150 seconds (2.5 minutes)
    private static final int MAX_WAIT_UP_TO = 300; // 300 seconds (5 minutes)

    /**
     * Told to the model when the shell dropped output at its capture cap, so a short result is not read as a short run.
     *
     * <p>
     * Borrowed from {@link BashTool} rather than restated: the condition is the same one, and the cap that produces it
     * is set there for background commands too. Two wordings for one condition would read to the model as two
     * different problems.
     */
    private static final String TRUNCATION_NOTICE = BashTool.CAPTURE_TRUNCATION_NOTICE;

    private final BackgroundBashManager backgroundManager;

    /**
     * Creates a new BashOutputTool.
     *
     * @param backgroundManager
     *            The background bash manager (must not be null)
     * @throws NullPointerException
     *             if backgroundManager is null
     */
    public BashOutputTool(BackgroundBashManager backgroundManager) {
        super(TOOL_NAME,
                "Retrieves output from running or completed background bash shells. "
                        + "Returns the output not yet returned by a previous call; each line is returned only once. "
                        + "Output becomes available when the command finishes, not while it runs — block=false on a "
                        + "still-running shell reports its status with no output, so use block=true to wait for "
                        + "completion and get the output. "
                        + "Filter output with regex patterns. Maximum wait time: 300 seconds.",
                ToolCategories.EXECUTION, createInputSchema());
        this.backgroundManager = Objects.requireNonNull(backgroundManager, "Background manager cannot be null");
    }

    /**
     * Creates the JSON Schema for BashOutput tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.ofEntries(Map.entry("type", "object"), Map.entry("additionalProperties", false),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("taskId",
                                Map.of("type", "string", "description",
                                        "The ID of the background shell to retrieve output from")),
                        Map.entry("block",
                                Map.of("type", "boolean", "description", "Whether to block until results are ready",
                                        "default", true)),
                        Map.entry("wait_up_to",
                                Map.of("type", "number", "description", "Maximum time to wait in seconds", "default",
                                        DEFAULT_WAIT_UP_TO, "minimum", 0, "maximum", MAX_WAIT_UP_TO)),
                        Map.entry("filter", Map.of("type", "string", "description",
                                "Optional regular expression to filter the output lines. "
                                        + "Only lines matching this regex will be included in the result. "
                                        + "Any lines that do not match will no longer be available to read.")))),
                Map.entry("required", List.of("taskId")));
    }

    /**
     * Executes the BashOutput tools to retrieve output from a background shell.
     *
     * <p>
     * The method performs the following operations:
     *
     * <ol>
     * <li>Validates the taskId parameter
     * <li>Extracts optional parameters (block, wait_up_to, filter)
     * <li>Checks if the task exists
     * <li>If blocking, waits for completion up to wait_up_to seconds
     * <li>Reads new output (optionally filtered)
     * <li>Returns formatted result with status and output
     * </ol>
     *
     * @param input
     *            The input parameters containing taskId and optional parameters
     * @param context
     *            The execution context (currently unused)
     * @return A success result with output and status if task exists, or an error result if the task is not found or
     *         parameters are invalid
     * @throws NullPointerException
     *             if input or context is null
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // Extract taskId parameter
            final String taskId = input.getRequiredString("taskId");

            // Validate taskId
            if (taskId.trim().isEmpty()) {
                return ToolResult.error("Task ID cannot be empty");
            }

            // Check if task exists
            if (!backgroundManager.hasTask(taskId)) {
                // No command lists background shells, so do not send the model looking for one. The id it needs was
                // handed to it by the Bash call that started the task.
                return ToolResult.error("Shell not found: " + taskId + "\n\n" + "The specified shell ID doesn't exist. "
                        + "Use the ID returned by the Bash call that started the background task.");
            }

            // Extract optional parameters
            final boolean block = input.getBoolean("block", true);
            final int waitUpTo = extractWaitUpTo(input);
            final String filter = input.getStringOrNull("filter");

            // Get task
            final Optional<BackgroundBashTask> taskOpt = backgroundManager.getTask(taskId);
            if (taskOpt.isEmpty()) {
                return ToolResult.error("Task not found: " + taskId);
            }

            final BackgroundBashTask task = taskOpt.get();

            // If blocking, wait for completion
            if (block) {
                final boolean completed = backgroundManager.awaitCompletion(taskId, waitUpTo);
                if (!completed && task.getStatus() == BashTaskStatus.RUNNING) {
                    // Timeout - read partial output
                    final String partialOutput = task.readNewOutput(filter);
                    return ToolResult.success(
                            "Status: Timeout\n" + "Message: Command still running after " + waitUpTo + " seconds\n\n"
                                    + (partialOutput.isEmpty()
                                            ? "No new output available"
                                            : "Partial Output:\n" + partialOutput));
                }
            }

            // Read new output
            final String output = task.readNewOutput(filter);

            // Format result based on status
            final BashTaskStatus status = task.getStatus();
            final StringBuilder result = new StringBuilder();

            switch (status) {
                case RUNNING :
                    result.append("Status: Running\n");
                    result.append("Command: ").append(task.getCommand()).append("\n\n");
                    if (output.isEmpty()) {
                        result.append("No new output available");
                    } else {
                        result.append("Output:\n").append(output);
                    }
                    break;

                case COMPLETED :
                    result.append("Status: Completed\n");
                    result.append("Exit Code: ").append(task.getExitCode()).append('\n');
                    result.append("Command: ").append(task.getCommand()).append("\n\n");
                    if (output.isEmpty()) {
                        result.append("No new output available");
                    } else {
                        result.append("Output:\n").append(output);
                    }
                    break;

                case FAILED :
                    result.append("Status: Failed\n");
                    result.append("Exit Code: ").append(task.getExitCode()).append('\n');
                    result.append("Command: ").append(task.getCommand()).append("\n\n");
                    if (task.getErrorMessage() != null) {
                        result.append("Error: ").append(task.getErrorMessage()).append("\n\n");
                    }
                    if (!output.isEmpty()) {
                        result.append("Output:\n").append(output);
                    }
                    break;

                case NOT_FOUND :
                    return ToolResult.error("Task not found: " + taskId);
                default :
                    return ToolResult.error("Unknown task status: " + status);
            }

            // Appended here, not stored as a line in the task's buffer: the caller's `filter` regex is applied to
            // buffered lines, so a notice living in there could be filtered out — hiding the fact that the output the
            // filter ran against was itself incomplete.
            if (task.isOutputTruncated()) {
                result.append("\n\n").append(TRUNCATION_NOTICE);
            }

            return ToolResult.success(result.toString());

        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Extracts and validates the wait_up_to parameter.
     *
     * @param input
     *            The input parameters
     * @return The wait_up_to in seconds
     */
    private int extractWaitUpTo(ToolInput input) {
        final int waitUpTo = input.getInteger("wait_up_to", DEFAULT_WAIT_UP_TO);

        // Validate range
        if (waitUpTo <= 0) {
            return DEFAULT_WAIT_UP_TO;
        }

        // Cap at maximum
        return Math.min(waitUpTo, MAX_WAIT_UP_TO);
    }
}
