package at.aimon.core.tools.task;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.subagent.ScopedSubagentTaskController;
import at.aimon.core.subagent.SubagentTaskController;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Requests cooperative cancellation (stop / kill) of a running background subagent task.
 *
 * <p>
 * Delegates to the {@link SubagentTaskController} control plane, which trips the task's cancellation signal and
 * interrupts its worker thread; the task then settles to {@link at.aimon.core.subagent.task.BackgroundTaskState#KILLED
 * KILLED}. Stopping is cooperative: a well-behaved subagent unwinds at its next ReAct/tool checkpoint, so the task may
 * still show {@code RUNNING} briefly after this returns. Retrieve final output with {@link AgentOutputTool}.
 *
 * <p>
 * Node-locality: a stop can only be honoured on the node that owns the running task. In a scale-out deployment a task
 * running on another node reports that no running task was found here.
 *
 * <p>
 * Declared {@code SEQUENTIAL} (the default) because it mutates task state.
 */
public class TaskStopTool extends AbstractTool {

    public static final String TOOL_NAME = "TaskStop";

    private static final Logger log = LoggerFactory.getLogger(TaskStopTool.class);

    private final SubagentTaskController taskController;

    /**
     * Creates a new TaskStopTool.
     *
     * @param taskController
     *            The background task control plane (must not be null)
     * @throws NullPointerException
     *             if taskController is null
     */
    public TaskStopTool(SubagentTaskController taskController) {
        super(TOOL_NAME,
                "Stops (kills) a running background agent task by task ID. Cancellation is cooperative: the task "
                        + "unwinds at its next checkpoint and settles to KILLED. Use " + TaskListTool.TOOL_NAME
                        + " to find task IDs and " + AgentOutputTool.TOOL_NAME + " to retrieve any final output.",
                ToolCategories.EXECUTION, createInputSchema());
        this.taskController = Objects.requireNonNull(taskController, "Task controller cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("taskId", Map.of("type", "string", "description", "The task ID of the background task to stop")),
                "required", List.of("taskId"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            final String taskId = input.getRequiredString("taskId");

            // Confine stop/status to the calling agent's runtime: a task owned by another
            // concurrently-running
            // agent is treated as not found, so one agent can never cancel another's background task through a shared
            // BackgroundTaskStore. Non-Orca call paths without the context id fall back to the unscoped controller.
            final SubagentTaskController scoped = ScopedSubagentTaskController.scopeOrPassThrough(taskController,
                    context.get(ToolContextKeys.AGENT_RUNTIME_ID));

            final boolean requested = scoped.stop(taskId);
            if (requested) {
                log.debug("Stop requested for background task: {}", taskId);
                return ToolResult.success(
                        "Stop requested for task " + taskId + ". The task will settle to KILLED once it unwinds; use "
                                + AgentOutputTool.TOOL_NAME + " to retrieve any final output.");
            }

            // No live handle: distinguish "already finished" from "never existed" for a clearer message.
            final Optional<BackgroundTask> snapshot = scoped.status(taskId);
            if (snapshot.isPresent()) {
                return ToolResult.success("Task " + taskId + " is not running (state: " + snapshot.get().getState()
                        + "); nothing to stop.");
            }
            return ToolResult.error("Task not found: " + taskId + ". It may never have existed, already completed "
                    + "and been removed, or be running on another node.");

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error stopping background task: {}", e.getMessage(), e);
            return ToolResult.error("Failed to stop task: " + e.getMessage());
        }
    }
}
