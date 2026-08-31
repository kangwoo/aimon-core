/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.tools.scheduling;

import static at.aimon.core.tools.ToolContextKeys.AGENT_RUNTIME_ID;
import static at.aimon.core.tools.ToolContextKeys.PRINCIPAL;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentDefinitionVersion;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;
import at.aimon.core.scheduling.exception.QuotaExceededException;

/**
 * Tool for scheduling a new task.
 *
 * <p>
 * The bound agent runtime id captured on each {@link ScheduledTask} comes from the
 * {@link at.aimon.core.tools.ToolContextKeys#AGENT_RUNTIME_ID AGENT_RUNTIME_ID} tool context entry &mdash;
 * the
 * <i>current agent's</i> agent-scoped {@link AgentRuntimeId}. Because that id is derived deterministically
 * from the agent (e.g., {@code agent:<name>}), it resolves to the same {@code AgentRuntime} on every cron
 * re-fire, even after the originating session has ended.
 *
 * <p>
 * What that id does <i>not</i> promise is that the agent behind it still says the same thing. Definitions are loaded
 * from markdown and can be edited between the schedule and the fire. When the tool is built through
 * {@link #forAgent(ScheduledTaskManager, Agent)} it therefore stamps each task with the agent's
 * {@link AgentDefinitionVersion} as of scheduling, which {@code RoutineExecutor} compares at fire time and logs. The
 * old definition is deliberately not pinned &mdash; see {@link ScheduledTask#getAgentDefinitionVersion()}.
 */
public class ScheduleTaskTool extends AbstractTool {

    public static final String TOOL_NAME = "schedule_task";

    private static final Logger log = LoggerFactory.getLogger(ScheduleTaskTool.class);

    private final ScheduledTaskManager taskManager;
    private final Supplier<AgentRuntimeId> defaultBoundRuntimeIdSupplier;
    private final Agent agent;
    private final AgentDefinitionVersion agentDefinitionVersion;

    /**
     * Creates a new ScheduleTaskTool that requires the caller to inject the bound context id via the tool context's
     * {@code AGENT_RUNTIME_ID} key. If the key is missing at execution time the tool fails with
     * {@link IllegalStateException} surfaced as a {@code ToolResult.error}.
     *
     * <p>
     * Production wiring (Orca executor) always populates {@code AGENT_RUNTIME_ID} with the current agent's
     * agent-scoped context id, so this is the path used at runtime.
     *
     * <p>
     * Tasks created through this constructor carry no definition version. Prefer
     * {@link #forAgent(ScheduledTaskManager, Agent)} where the agent is known.
     */
    public ScheduleTaskTool(ScheduledTaskManager taskManager) {
        this(taskManager, requireRuntimeIdFromContext(), null);
    }

    /**
     * Creates a new ScheduleTaskTool with an explicit fallback supplier for the bound context id. Intended for tests
     * that exercise the tool without a fully-wired executor context.
     */
    public ScheduleTaskTool(ScheduledTaskManager taskManager, Supplier<AgentRuntimeId> defaultBoundRuntimeIdSupplier) {
        this(taskManager, defaultBoundRuntimeIdSupplier, null);
    }

    /**
     * Creates a ScheduleTaskTool that records the scheduling agent's definition version on every task it creates.
     *
     * <p>
     * This is the production wiring. The version is computed once here rather than per call, because an {@link Agent}
     * is an immutable definition &mdash; a changed definition arrives as a new agent and a rebuilt runtime, which
     * builds a new tool alongside it.
     *
     * @param taskManager
     *            The manager tasks are registered with (must not be null)
     * @param agent
     *            The agent this tool is registered for (must not be null)
     * @return A tool that stamps the definition version
     * @throws NullPointerException
     *             if either argument is null
     */
    public static ScheduleTaskTool forAgent(ScheduledTaskManager taskManager, Agent agent) {
        Objects.requireNonNull(agent, "Agent cannot be null");
        return new ScheduleTaskTool(taskManager, requireRuntimeIdFromContext(), agent);
    }

    private ScheduleTaskTool(ScheduledTaskManager taskManager, Supplier<AgentRuntimeId> defaultBoundRuntimeIdSupplier,
            Agent agent) {
        super(TOOL_NAME, createDescription(), ToolCategories.EXECUTION, createInputSchema());
        this.taskManager = Objects.requireNonNull(taskManager, "Task manager cannot be null");
        this.defaultBoundRuntimeIdSupplier = Objects.requireNonNull(defaultBoundRuntimeIdSupplier,
                "Default bound context ID supplier cannot be null");
        this.agent = agent;
        agentDefinitionVersion = agent != null ? AgentDefinitionVersion.from(agent) : null;
    }

    private static Supplier<AgentRuntimeId> requireRuntimeIdFromContext() {
        return () -> {
            throw new IllegalStateException(
                    "AGENT_RUNTIME_ID is required in ToolContext but was not provided by the executor");
        };
    }

    private static String createDescription() {
        return "Schedule a new recurring task. The task will execute a routine (an ordered "
                + "sequence of tool steps) according to the specified cron schedule. Returns the task ID on success. "
                + "Each step's tool_params is a JSON string that will be parsed as tool input. "
                + "Use $step.index.result template syntax (0-based index) to reference "
                + "previous step results. Example: $step.0.result for first step's result.";
    }

    private static Map<String, Object> createInputSchema() {
        return Map.ofEntries(Map.entry("type", "object"), Map.entry("additionalProperties", false),
                Map.entry("properties",
                        Map.of("name",
                                Map.of("type", "string", "minLength", 1, "description",
                                        "Task name (human-friendly, stable identifier)."),
                                "description", Map.of("type", "string", "description", "Optional description."),
                                "schedule", createScheduleSchema(), "routine", createRoutineSchema())),
                Map.entry("required", List.of("name", "schedule", "routine")));
    }

    private static Map<String, Object> createScheduleSchema() {
        return Map.ofEntries(Map.entry("type", "object"), Map.entry("additionalProperties", false), Map.entry(
                "properties",
                Map.of("type", Map.of("type", "string", "enum", List.of("cron"), "description", "Schedule type."),
                        "cron_expression",
                        Map.of("type", "string", "minLength", 1, "description",
                                "Cron expression with exactly 5 fields: minute hour day-of-month month day-of-week. "
                                        + "No seconds field. Day-of-week is 0-6 with 0 = Sunday (7 also means Sunday), "
                                        + "or names such as MON. Supports *, ranges (1-5), steps (*/15) and lists "
                                        + "(1,3,5). Does not support ?, L, W, # or @daily-style nicknames. "
                                        + "Restricting day-of-month and day-of-week at the same time means 'either "
                                        + "day' and is not supported by every scheduler backend - use two tasks.",
                                "examples", List.of("*/5 * * * *", "0 0 * * *", "30 9 * * MON-FRI", "0 3 1 * *")),
                        "timezone", Map.of("type", "string", "description", "IANA timezone, e.g., Asia/Seoul.",
                                "examples", List.of("Asia/Seoul")))),
                Map.entry("required", List.of("type", "cron_expression")));
    }

    private static Map<String, Object> createRoutineSchema() {
        return Map.of("type", "array", "minItems", 1, "description", "Routine steps executed in order.", "items",
                createRoutineItemSchema());
    }

    private static Map<String, Object> createRoutineItemSchema() {
        return Map.ofEntries(Map.entry("type", "object"), Map.entry("additionalProperties", false), Map.entry(
                "properties",
                Map.of("id", Map.of("type", "string", "description", "Optional step id for tracing/logging."), "tool",
                        Map.of("type", "string", "minLength", 1, "description", "Tool name to execute."), "tool_params",
                        Map.of("type", "string", "description",
                                "Tool parameters as a JSON string. Not checked when scheduling — "
                                        + "a mismatch surfaces at step execution time as a step failure. "
                                        + "Can use $step.index.result (0-based index) to reference "
                                        + "previous step results. "
                                        + "Example: \"{\\\"message\\\": \\\"Result: $step.0.result\\\"}\""),
                        "max_retries",
                        Map.of("type", "integer", "minimum", 0, "default", 0, "description",
                                "Max retries for this step."),
                        "timeout_ms",
                        Map.of("type", "integer", "minimum", 0, "description", "Optional per-step timeout."))),
                Map.entry("required", List.of("tool", "tool_params")));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final AgentRuntimeId boundRuntimeId;
        try {
            boundRuntimeId = context.get(AGENT_RUNTIME_ID).orElseGet(defaultBoundRuntimeIdSupplier);
        } catch (IllegalStateException e) {
            // Expected configuration error — the executor wiring did not populate AGENT_RUNTIME_ID. Surface this
            // as a tool error at WARN level (not ERROR with stacktrace) per .claude/rules/error-handling.md.
            log.warn("AGENT_RUNTIME_ID is missing in ToolContext: {}", e.getMessage());
            return ToolResult.error("ScheduleTask requires AGENT_RUNTIME_ID in context: " + e.getMessage());
        }

        try {
            final ScheduleTaskRequest request = ScheduleTaskRequest.fromMap(input.toMap());

            final Principal owner = context.get(PRINCIPAL).orElseGet(Principal::system);

            final ScheduledTaskId taskId = ScheduledTaskId.generate();

            final ScheduledTask task = ScheduledTask.builder().id(taskId).name(request.getName())
                    .description(request.getDescription()).cronExpression(request.getCronExpression())
                    .timezone(request.getTimezone()).routine(request.getRoutine()).owner(owner)
                    .boundRuntimeId(boundRuntimeId).agentDefinitionVersion(versionFor(boundRuntimeId)).enabled(true)
                    .build();

            final ScheduledTask registered = taskManager.register(task);

            log.info("Scheduled task '{}' with ID '{}' for owner {} (bound to {})", request.getName(), taskId, owner,
                    boundRuntimeId);

            return ToolResult.success("Task scheduled successfully.\n" + "Task ID: " + registered.getId() + '\n'
                    + "Name: " + registered.getName() + '\n' + "Schedule: " + registered.getCronExpression());

        } catch (InvalidCronExpressionException e) {
            log.warn("Invalid cron expression: {}", e.getMessage());
            return ToolResult.error("Invalid cron expression: " + e.getMessage());
        } catch (QuotaExceededException e) {
            log.warn("Quota exceeded: {}", e.getMessage());
            return ToolResult.error("Quota exceeded: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ToolResult.error("Invalid request: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to schedule task: {}", e.getMessage(), e);
            return ToolResult.error("Failed to schedule task: " + e.getMessage());
        }
    }

    /**
     * Returns the definition version to stamp on a task bound to the given runtime id, or {@code null} when there is
     * nothing trustworthy to stamp.
     *
     * <p>
     * The agent-name guard is not ceremony. This tool holds one agent's definition, while the bound runtime id arrives
     * from the tool context and is only <i>normally</i> that same agent's. Stamping unconditionally would write agent
     * A's version onto a task that fires agent B, and the drift check would then report a change on every single run.
     * Recording nothing is the documented, harmless case &mdash; recording the wrong thing is not.
     *
     * <p>
     * The comparison is on the agent-name segment alone, so a discriminated runtime id
     * ({@code agent:<name>:<tenant>}) still matches: the definition is the same either way, since the discriminator
     * splits runtimes, not definitions.
     */
    private AgentDefinitionVersion versionFor(AgentRuntimeId boundRuntimeId) {
        if (agent == null) {
            return null;
        }
        if (!agent.getName().equals(boundRuntimeId.agentName())) {
            log.debug("Not recording a definition version for a task bound to '{}': this tool serves agent '{}'",
                    boundRuntimeId, agent.getName());
            return null;
        }
        return agentDefinitionVersion;
    }
}
