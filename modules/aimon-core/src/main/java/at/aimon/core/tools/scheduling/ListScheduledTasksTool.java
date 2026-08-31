/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.tools.scheduling;

import static at.aimon.core.tools.ToolContextKeys.PRINCIPAL;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskManager;

/**
 * Tool for listing scheduled tasks.
 */
public class ListScheduledTasksTool extends AbstractTool {

    public static final String TOOL_NAME = "list_scheduled_tasks";

    private static final Logger log = LoggerFactory.getLogger(ListScheduledTasksTool.class);

    private final ScheduledTaskManager taskManager;
    private final Supplier<Principal> defaultPrincipalSupplier;

    /** ListScheduledTasksTool을 생성한다. */
    public ListScheduledTasksTool(ScheduledTaskManager taskManager) {
        this(taskManager, Principal::system);
    }

    /** ListScheduledTasksTool을 생성한다. */
    public ListScheduledTasksTool(ScheduledTaskManager taskManager, Supplier<Principal> defaultPrincipalSupplier) {
        super(TOOL_NAME, createDescription(), ToolCategories.EXECUTION, createInputSchema());
        this.taskManager = Objects.requireNonNull(taskManager, "Task manager cannot be null");
        this.defaultPrincipalSupplier = Objects.requireNonNull(defaultPrincipalSupplier,
                "Default principal supplier cannot be null");
    }

    private static String createDescription() {
        return "List all scheduled tasks for the current user. " + "Returns task IDs, names, schedules, and status.";
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("enabled_only",
                        Map.of("type", "boolean", "description", "If true, only list enabled tasks (default: false)")),
                "required", List.of());
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        // Lists registered tasks; scheduling and cancelling them are separate tools.
        return SideEffectLevel.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            boolean enabledOnly = input.getBoolean("enabled_only", false);

            Principal owner = context.get(PRINCIPAL).orElseGet(defaultPrincipalSupplier);

            List<ScheduledTask> tasks = enabledOnly
                    ? taskManager.listEnabledByOwner(owner)
                    : taskManager.listByOwner(owner);

            if (tasks.isEmpty()) {
                return ToolResult.success("No scheduled tasks found.");
            }

            String result = tasks.stream().map(this::formatTask).collect(Collectors.joining("\n\n"));

            log.debug("Listed {} tasks for owner {}", tasks.size(), owner);

            return ToolResult.success("Found " + tasks.size() + " scheduled task(s):\n\n" + result);

        } catch (Exception e) {
            log.error("Failed to list tasks: {}", e.getMessage(), e);
            return ToolResult.error("Failed to list tasks: " + e.getMessage());
        }
    }

    private String formatTask(ScheduledTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task ID: ").append(task.getId()).append("\n");
        sb.append("Name: ").append(task.getName()).append("\n");
        task.getDescription().ifPresent(desc -> sb.append("Description: ").append(desc).append("\n"));
        sb.append("Schedule: ").append(task.getCronExpression()).append("\n");
        sb.append("Status: ").append(task.isEnabled() ? "Enabled" : "Disabled").append("\n");
        sb.append("Steps: ").append(task.getRoutine().size());
        task.getLastExecutedAt().ifPresent(time -> sb.append("\nLast Executed: ").append(time));
        return sb.toString();
    }
}
