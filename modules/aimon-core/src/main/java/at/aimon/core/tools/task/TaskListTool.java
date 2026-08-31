package at.aimon.core.tools.task;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Constants;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.subagent.ScopedSubagentTaskController;
import at.aimon.core.subagent.SubagentTaskController;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.TaskQuery;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Lists background subagent tasks and their lifecycle state.
 *
 * <p>
 * Backed by the {@link SubagentTaskController} control plane, which reads from the multi-instance-ready
 * {@code BackgroundTaskStore}. Callers can optionally filter by {@code state}
 * ({@code pending|running|completed|failed|killed}); with no filter every known task is returned.
 *
 * <p>
 * Read-only and declared {@link ConcurrencyBehavior#CONCURRENT_SAFE}: it never mutates task state and the underlying
 * store
 * is thread-safe, so it may run alongside other concurrent-safe tools in a batch.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * Tool taskList = new TaskListTool(controller);
 * // All tasks
 * ToolResult all = taskList.execute(ToolInput.of(), ToolContext.empty());
 * // Only running tasks
 * ToolResult running = taskList.execute(ToolInput.of("state", "running"), ToolContext.empty());
 * }
 * </pre>
 */
public class TaskListTool extends AbstractTool {

    public static final String TOOL_NAME = "TaskList";

    private static final Logger log = LoggerFactory.getLogger(TaskListTool.class);

    private final SubagentTaskController taskController;

    /**
     * Creates a new TaskListTool.
     *
     * @param taskController
     *            The background task control plane (must not be null)
     * @throws NullPointerException
     *             if taskController is null
     */
    public TaskListTool(SubagentTaskController taskController) {
        super(TOOL_NAME,
                "Lists background agent tasks with their state (pending, running, completed, failed, killed). "
                        + "Optionally filter by 'state'. Use this to see what background tasks exist before retrieving "
                        + "their output with " + AgentOutputTool.TOOL_NAME + " or stopping them with "
                        + TaskStopTool.TOOL_NAME + ".",
                ToolCategories.EXECUTION, createInputSchema());
        this.taskController = Objects.requireNonNull(taskController, "Task controller cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("state",
                        Map.of("type", "string", "description",
                                "Optional lifecycle state filter. One of: pending, running, completed, failed, killed. "
                                        + "Omit to list all tasks.",
                                "enum", List.of("pending", "running", "completed", "failed", "killed"))),
                "required", List.of());
    }

    @Override
    public ConcurrencyBehavior getConcurrencyBehavior() {
        // Read-only over a thread-safe store; safe to run alongside other CONCURRENT_SAFE tools.
        return ConcurrencyBehavior.CONCURRENT_SAFE;
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        // Lists task records; never creates, kills, or reaps one.
        return SideEffectLevel.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            final String stateFilter = input.getStringOrNull("state");

            final TaskQuery query;
            if (stateFilter != null && !stateFilter.isBlank()) {
                final BackgroundTaskState state = parseState(stateFilter);
                if (state == null) {
                    return ToolResult.error("Invalid parameter: unknown state '" + stateFilter
                            + "'. Expected one of: pending, running, completed, failed, killed.");
                }
                query = TaskQuery.byState(state);
            } else {
                query = TaskQuery.all();
            }

            // Confine the listing to the calling agent's runtime so concurrently-running agents that share a
            // BackgroundTaskStore never enumerate each other's tasks. Non-Orca call paths without the context id fall
            // back to the unscoped controller (see ScopedSubagentTaskController#scopeOrPassThrough).
            final SubagentTaskController scoped = ScopedSubagentTaskController.scopeOrPassThrough(taskController,
                    context.get(ToolContextKeys.AGENT_RUNTIME_ID));

            final List<BackgroundTask> tasks = scoped.list(query);
            log.debug("Listing background tasks: filter={}, matched={}", stateFilter, tasks.size());

            return ToolResult.success(format(tasks, stateFilter));

        } catch (Exception e) {
            log.error("Unexpected error listing background tasks: {}", e.getMessage(), e);
            return ToolResult.error("Failed to list tasks: " + e.getMessage());
        }
    }

    private static BackgroundTaskState parseState(String raw) {
        try {
            return BackgroundTaskState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String format(List<BackgroundTask> tasks, String stateFilter) {
        final StringBuilder out = new StringBuilder();
        out.append("=== Background Tasks");
        if (stateFilter != null && !stateFilter.isBlank()) {
            out.append(" (state=").append(stateFilter.trim().toLowerCase(Locale.ROOT)).append(')');
        }
        out.append(" ===").append(Constants.NEWLINE);

        if (tasks.isEmpty()) {
            out.append("No matching background tasks.");
            return out.toString();
        }

        out.append("Total: ").append(tasks.size()).append(Constants.DOUBLE_NEWLINE);
        for (BackgroundTask task : tasks) {
            out.append("- ").append(task.getTaskId()).append(Constants.NEWLINE);
            out.append("    subagent : ").append(task.getSubagentName()).append(Constants.NEWLINE);
            out.append("    state    : ").append(task.getState()).append(Constants.NEWLINE);
            if (!task.getDescription().isEmpty()) {
                out.append("    task     : ").append(task.getDescription()).append(Constants.NEWLINE);
            }
            out.append("    age      : ").append(formatAge(task)).append(Constants.NEWLINE);
        }
        return out.toString();
    }

    /**
     * Renders a coarse wall-clock age for the task: elapsed to end for terminal tasks, elapsed to now otherwise. Uses
     * no
     * external clock beyond {@link Instant#now()} and never throws.
     */
    private static String formatAge(BackgroundTask task) {
        final Instant end = task.getEndTime().orElseGet(Instant::now);
        final Duration elapsed = Duration.between(task.getStartTime(), end);
        final long seconds = Math.max(0, elapsed.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }
}
