/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.tools.scheduling;

import static at.aimon.core.tools.ToolContextKeys.PRINCIPAL;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.scheduling.exception.TaskNotFoundException;
import at.aimon.core.scheduling.exception.UnauthorizedTaskAccessException;

/**
 * Tool for cancelling a scheduled task.
 */
public class CancelScheduledTaskTool extends AbstractTool {

    public static final String TOOL_NAME = "cancel_scheduled_task";

    private static final Logger log = LoggerFactory.getLogger(CancelScheduledTaskTool.class);

    private final ScheduledTaskManager taskManager;
    private final Supplier<Principal> defaultPrincipalSupplier;

    /** Creates a new CancelScheduledTaskTool with default principal supplier. */
    public CancelScheduledTaskTool(ScheduledTaskManager taskManager) {
        this(taskManager, Principal::system);
    }

    /** Creates a new CancelScheduledTaskTool. */
    public CancelScheduledTaskTool(ScheduledTaskManager taskManager, Supplier<Principal> defaultPrincipalSupplier) {
        super(TOOL_NAME, createDescription(), ToolCategories.EXECUTION, createInputSchema());
        this.taskManager = Objects.requireNonNull(taskManager, "Task manager cannot be null");
        this.defaultPrincipalSupplier = Objects.requireNonNull(defaultPrincipalSupplier,
                "Default principal supplier cannot be null");
    }

    private static String createDescription() {
        return "Cancel a scheduled task by its ID. "
                + "This permanently removes the task and its execution history, and stops any run of it "
                + "already in progress on this node — a running routine unwinds at its next step rather than "
                + "finishing the steps that remain.";
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("task_id", Map.of("type", "string", "description", "The ID of the task to cancel")), "required",
                List.of("task_id"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            ScheduledTaskId taskId = ScheduledTaskId.of(input.getRequiredString("task_id"));

            Principal principal = context.get(PRINCIPAL).orElseGet(defaultPrincipalSupplier);

            taskManager.cancel(taskId, principal);

            log.info("Cancelled task '{}' by {}", taskId, principal);

            return ToolResult.success("Task '" + taskId + "' has been cancelled successfully.");

        } catch (TaskNotFoundException e) {
            log.warn("Task not found: {}", e.getTaskId());
            return ToolResult.error("Task not found: " + e.getTaskId());
        } catch (UnauthorizedTaskAccessException e) {
            log.warn("Unauthorized access to task: {}", e.getTaskId());
            return ToolResult.error("You don't have permission to cancel this task.");
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ToolResult.error("Invalid request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to cancel task: {}", e.getMessage(), e);
            return ToolResult.error("Failed to cancel task: " + e.getMessage());
        }
    }
}
