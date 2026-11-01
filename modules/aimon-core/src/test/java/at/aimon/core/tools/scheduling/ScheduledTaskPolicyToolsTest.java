/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.tools.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskPolicy;
import at.aimon.core.scheduling.SchedulingEngine;
import at.aimon.core.scheduling.SchedulingEngineBuilder;
import at.aimon.core.tools.ToolContextKeys;

/**
 * The scheduling tools consult the manager's {@link ScheduledTaskPolicy} (S6) and refuse a task bound to a runtime
 * nobody registered (S1), all through a real engine.
 */
class ScheduledTaskPolicyToolsTest {

    private static final Principal ALICE = Principal.user("alice");
    private static final AgentRuntimeId CALLER = AgentRuntimeId.fromName("chat");
    private static final AgentRuntimeId SCHEDULER = AgentRuntimeId.fromName("chat", "scheduled");

    private final DefaultAgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
    private SchedulingEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("an unregistered caller runtime makes schedule_task fail instead of reporting a task that never runs")
    void unresolvableRuntimeIsAnError() {
        start(ScheduledTaskPolicy.PERMIT_ALL);

        final ToolResult result = new ScheduleTaskTool(engine.getTaskManager()).execute(scheduleInput("Bash"),
                context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Task not scheduled").contains("agent:chat");
        assertThat(engine.getTaskManager().listAll()).isEmpty();
    }

    @Test
    @DisplayName("the policy chooses the bound runtime, and that runtime is the one checked")
    void policyChoosesTheBoundRuntime() {
        registerRuntime(SCHEDULER);
        start(new ScheduledTaskPolicy() {
            @Override
            public AgentRuntimeId resolveBoundRuntimeId(AgentRuntimeId callerRuntimeId, ToolContext context) {
                return SCHEDULER;
            }
        });

        final ToolResult result = new ScheduleTaskTool(engine.getTaskManager()).execute(scheduleInput("Bash"),
                context());

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        assertThat(engine.getTaskManager().listAll()).singleElement()
                .satisfies(task -> assertThat(task.getBoundRuntimeId()).isEqualTo(SCHEDULER));
    }

    @Test
    @DisplayName("checkSchedule sees the routine's tools and can refuse; nothing is stored")
    void policyRefusesARoutine() {
        registerRuntime(CALLER);
        start(new ScheduledTaskPolicy() {
            @Override
            public Decision checkSchedule(ScheduledTask candidate, ToolContext context) {
                return candidate.getRoutine().stream().anyMatch(step -> step.getTool().equals("Bash"))
                        ? Decision.deny("Bash may not run unattended")
                        : Decision.allow();
            }
        });

        final ToolResult refused = new ScheduleTaskTool(engine.getTaskManager()).execute(scheduleInput("Bash"),
                context());
        final ToolResult allowed = new ScheduleTaskTool(engine.getTaskManager()).execute(scheduleInput("Read"),
                context());

        assertThat(refused.isError()).isTrue();
        assertThat(refused.getContent()).contains("Bash may not run unattended");
        assertThat(allowed.isSuccess()).isTrue();
        assertThat(engine.getTaskManager().listAll()).hasSize(1);
    }

    @Test
    @DisplayName("a policy that throws refuses rather than letting the task through")
    void throwingPolicyFailsClosed() {
        registerRuntime(CALLER);
        start(new ScheduledTaskPolicy() {
            @Override
            public Decision checkSchedule(ScheduledTask candidate, ToolContext context) {
                throw new IllegalStateException("policy store down");
            }
        });

        final ToolResult result = new ScheduleTaskTool(engine.getTaskManager()).execute(scheduleInput("Read"),
                context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("policy store down");
        assertThat(engine.getTaskManager().listAll()).isEmpty();
    }

    @Test
    @DisplayName("checkAccess narrows listing and cancelling, never widens them")
    void policyNarrowsListAndCancel() {
        registerRuntime(CALLER);
        start(new ScheduledTaskPolicy() {
            @Override
            public Decision checkAccess(ScheduledTask task, Access access, ToolContext context) {
                return task.getName().startsWith("hidden") ? Decision.deny("managed by ops") : Decision.allow();
            }
        });
        final ScheduleTaskTool schedule = new ScheduleTaskTool(engine.getTaskManager());
        schedule.execute(scheduleInput("Read", "visible"), context());
        schedule.execute(scheduleInput("Read", "hidden-1"), context());

        final ToolResult listed = new ListScheduledTasksTool(engine.getTaskManager()).execute(ToolInput.of(Map.of()),
                context());
        final ScheduledTask hidden = engine.getTaskManager().listByOwner(ALICE).stream()
                .filter(task -> task.getName().equals("hidden-1")).findFirst().orElseThrow();
        final ToolResult cancel = new CancelScheduledTaskTool(engine.getTaskManager())
                .execute(ToolInput.of(Map.of("task_id", hidden.getId().value())), context());

        assertThat(listed.getContent()).contains("visible").doesNotContain("hidden-1");
        assertThat(cancel.isError()).isTrue();
        assertThat(cancel.getContent()).contains("managed by ops");
        assertThat(engine.getTaskManager().listAll()).hasSize(2);
    }

    @Test
    @DisplayName("the advertised max_retries default is the real one")
    @SuppressWarnings("unchecked")
    void schemaDefaultMatchesRoutineStep() {
        start(ScheduledTaskPolicy.PERMIT_ALL);
        final Map<String, Object> schema = new ScheduleTaskTool(engine.getTaskManager()).getDefinition()
                .getInputSchema();
        final Map<String, Object> routine = (Map<String, Object>) ((Map<String, Object>) schema.get("properties"))
                .get("routine");
        final Map<String, Object> item = (Map<String, Object>) routine.get("items");
        final Map<String, Object> maxRetries = (Map<String, Object>) ((Map<String, Object>) item.get("properties"))
                .get("max_retries");

        assertThat(maxRetries.get("default")).isEqualTo(RoutineStep.DEFAULT_MAX_RETRIES);
    }

    private void start(ScheduledTaskPolicy policy) {
        engine = SchedulingEngineBuilder.create().agentRuntimeRegistry(registry).taskPolicy(policy).build();
        engine.start();
    }

    private void registerRuntime(AgentRuntimeId id) {
        final Agent agent = DefaultAgent.builder().name("chat").systemPrompt("test").build();
        registry.register(new AgentRuntime() {
            @Override
            public AgentRuntimeId getId() {
                return id;
            }

            @Override
            public Agent getAgent() {
                return agent;
            }

            @Override
            public List<Tool> getAvailableTools() {
                return List.of();
            }
        });
    }

    private static ToolContext context() {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, CALLER).put(ToolContextKeys.PRINCIPAL, ALICE)
                .build();
    }

    private static ToolInput scheduleInput(String tool) {
        return scheduleInput(tool, "nightly-" + tool);
    }

    private static ToolInput scheduleInput(String tool, String name) {
        return ToolInput.of(Map.of("name", name, "schedule", Map.of("type", "cron", "cron_expression", "0 3 * * *"),
                "routine", List.of(Map.of("tool", tool, "tool_params", "{}"))));
    }
}
