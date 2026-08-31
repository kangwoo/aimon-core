package at.aimon.core.tools.scheduling;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentDefinitionVersion;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;
import at.aimon.core.scheduling.exception.QuotaExceededException;
import at.aimon.core.tools.ToolContextKeys;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayName("ScheduleTaskTool Tests")
class ScheduleTaskToolTest {

    private ScheduledTaskManager taskManager;
    private ScheduleTaskTool tool;
    private AgentRuntimeId defaultBoundRuntimeId;

    @BeforeEach
    void setUp() {
        taskManager = mock(ScheduledTaskManager.class);
        defaultBoundRuntimeId = AgentRuntimeId.of("agent:test-1");
        tool = new ScheduleTaskTool(taskManager, () -> defaultBoundRuntimeId);
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinition {

        @Test
        @DisplayName("Should have correct tool name")
        void testToolName() {
            assertThat(ScheduleTaskTool.TOOL_NAME).isEqualTo("schedule_task");
            assertThat(tool.getDefinition().getName()).isEqualTo("schedule_task");
        }

        @Test
        @DisplayName("Should have description")
        void testToolDescription() {
            String description = tool.getDefinition().getDescription();

            assertThat(description).isNotNull();
            assertThat(description).contains("Schedule");
            assertThat(description).contains("routine");
            assertThat(description).contains("cron");
        }

        @Test
        @DisplayName("Should have valid input schema")
        @SuppressWarnings("unchecked")
        void testInputSchema() {
            Map<String, Object> schema = tool.getDefinition().getInputSchema();

            assertThat(schema).containsKey("type");
            assertThat(schema).containsKey("properties");
            assertThat(schema).containsKey("required");
            assertThat(schema).containsEntry("additionalProperties", false);

            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).containsKeys("name", "description", "schedule", "routine");

            List<String> required = (List<String>) schema.get("required");
            assertThat(required).containsExactlyInAnyOrder("name", "schedule", "routine");
        }

        @Test
        @DisplayName("Should have valid schedule schema")
        @SuppressWarnings("unchecked")
        void testScheduleSchema() {
            Map<String, Object> schema = tool.getDefinition().getInputSchema();
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            Map<String, Object> schedule = (Map<String, Object>) properties.get("schedule");

            assertThat(schedule.get("type")).isEqualTo("object");
            assertThat(schedule).containsEntry("additionalProperties", false);
            assertThat(schedule).containsKey("properties");
            assertThat(schedule).containsKey("required");

            Map<String, Object> scheduleProperties = (Map<String, Object>) schedule.get("properties");
            assertThat(scheduleProperties).containsKeys("type", "cron_expression", "timezone");

            List<String> scheduleRequired = (List<String>) schedule.get("required");
            assertThat(scheduleRequired).containsExactly("type", "cron_expression");
        }

        @Test
        @DisplayName("Should have routine items schema with required fields")
        @SuppressWarnings("unchecked")
        void testRoutineItemsSchema() {
            Map<String, Object> schema = tool.getDefinition().getInputSchema();
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            Map<String, Object> routine = (Map<String, Object>) properties.get("routine");

            assertThat(routine).containsEntry("minItems", 1);

            Map<String, Object> items = (Map<String, Object>) routine.get("items");

            assertThat(items.get("type")).isEqualTo("object");
            assertThat(items).containsEntry("additionalProperties", false);
            assertThat(items).containsKey("properties");
            assertThat(items).containsKey("required");

            Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
            assertThat(itemProperties).containsKeys("id", "tool", "tool_params", "max_retries", "timeout_ms");

            Map<String, Object> toolParamsSchema = (Map<String, Object>) itemProperties.get("tool_params");
            assertThat(toolParamsSchema).containsEntry("type", "string");

            List<String> itemRequired = (List<String>) items.get("required");
            assertThat(itemRequired).containsExactly("tool", "tool_params");
        }
    }

    @Nested
    @DisplayName("Successful Execution")
    class SuccessfulExecution {

        @Test
        @DisplayName("Should schedule task successfully with valid input")
        void testScheduleTaskSuccess() {
            ToolInput input = createValidInput();
            ToolContext context = ToolContext.empty();

            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Task scheduled successfully");
            assertThat(result.getContent()).contains("Task ID:");
            assertThat(result.getContent()).contains("Name: Daily Report");
            assertThat(result.getContent()).contains("Schedule: 0 9 * * *");

            verify(taskManager).register(any(ScheduledTask.class));
        }

        @Test
        @DisplayName("Should use Principal owner from ToolContext when available")
        void testUseOwnerFromToolContext() {
            Principal owner = Principal.user("user-42", "Alice");
            ToolInput input = createValidInput();
            ToolContext context = ToolContext.builder().put(ToolContextKeys.PRINCIPAL, owner).build();

            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> {
                ScheduledTask task = invocation.getArgument(0);
                assertThat(task.getOwner()).isEqualTo(owner);
                return task;
            });

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            verify(taskManager).register(argThat(task -> task.getOwner().equals(owner)));
        }

        @Test
        @DisplayName("Should default owner to system Principal when not in ToolContext")
        void testDefaultOwnerIsSystem() {
            ToolInput input = createValidInput();
            ToolContext context = ToolContext.empty();

            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            verify(taskManager).register(argThat(task -> task.getOwner().equals(Principal.system())));
        }

        @Test
        @DisplayName("Should bind to AGENT_RUNTIME_ID from ToolContext when available")
        void testBindToRuntimeIdFromToolContext() {
            AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-2");
            ToolInput input = createValidInput();
            ToolContext context = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, agentRuntimeId).build();

            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> {
                ScheduledTask task = invocation.getArgument(0);
                assertThat(task.getBoundRuntimeId()).isEqualTo(agentRuntimeId);
                return task;
            });

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            verify(taskManager).register(argThat(task -> task.getBoundRuntimeId().equals(agentRuntimeId)));
        }

        @Test
        @DisplayName("Should bind to default context id when not in ToolContext")
        void testUseDefaultBoundRuntimeId() {
            ToolInput input = createValidInput();
            ToolContext context = ToolContext.empty();

            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            verify(taskManager).register(argThat(task -> task.getBoundRuntimeId().equals(defaultBoundRuntimeId)));
        }

        @Test
        @DisplayName("Should schedule task with optional description")
        void testScheduleTaskWithDescription() {
            ToolInput input = ToolInput.of(Map.of("name", "Test Task", "description", "This is a test task description",
                    "schedule", Map.of("type", "cron", "cron_expression", "*/5 * * * *"), "routine",
                    List.of(Map.of("tool", "TestTool", "tool_params", "{}"))));
            ToolContext context = ToolContext.empty();

            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> {
                ScheduledTask task = invocation.getArgument(0);
                assertThat(task.getDescription()).hasValue("This is a test task description");
                return task;
            });

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should schedule task with multiple routine steps")
        void testScheduleTaskWithMultipleSteps() {
            ToolInput input = ToolInput.of(Map.of("name", "Multi-Step Task", "schedule",
                    Map.of("type", "cron", "cron_expression", "0 0 * * *"), "routine",
                    List.of(Map.of("id", "step1", "tool", "Step1Tool", "tool_params", "{\"key1\": \"value1\"}"),
                            Map.of("tool", "Step2Tool", "tool_params", "{\"input\": \"$step.0.result\"}"),
                            Map.of("tool", "Step3Tool", "tool_params", "{}", "max_retries", 5, "timeout_ms", 60000))));
            ToolContext context = ToolContext.empty();

            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> {
                ScheduledTask task = invocation.getArgument(0);
                assertThat(task.getRoutine()).hasSize(3);
                assertThat(task.getRoutine().get(0).getId()).isEqualTo("step1");
                assertThat(task.getRoutine().get(2).getTimeout().toMillis()).isEqualTo(60000);
                return task;
            });

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should return error for invalid cron expression")
        void testInvalidCronExpression() {
            ToolInput input = createValidInput();
            ToolContext context = ToolContext.empty();

            when(taskManager.register(any(ScheduledTask.class)))
                    .thenThrow(new InvalidCronExpressionException("invalid-cron", "invalid format"));

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContent()).contains("Invalid cron expression");
        }

        @Test
        @DisplayName("Should return error when quota exceeded")
        void testQuotaExceeded() {
            ToolInput input = createValidInput();
            ToolContext context = ToolContext.empty();

            when(taskManager.register(any(ScheduledTask.class)))
                    .thenThrow(new QuotaExceededException(Principal.system(), 10, 10));

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContent()).contains("Quota exceeded");
        }

        @Test
        @DisplayName("Should return error for missing required field - name")
        void testMissingName() {
            ToolInput input = ToolInput.of(Map.of("schedule", Map.of("type", "cron", "cron_expression", "0 9 * * *"),
                    "routine", List.of(Map.of("tool", "TestTool", "tool_params", "{}"))));
            ToolContext context = ToolContext.empty();

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContent()).containsIgnoringCase("name");
        }

        @Test
        @DisplayName("Should return error for missing required field - cron_expression")
        void testMissingCronExpression() {
            ToolInput input = ToolInput.of(Map.of("name", "Test Task", "schedule", Map.of("type", "cron"), "routine",
                    List.of(Map.of("tool", "TestTool", "tool_params", "{}"))));
            ToolContext context = ToolContext.empty();

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContent()).containsIgnoringCase("cron");
        }

        @Test
        @DisplayName("Should return error for missing required field - routine")
        void testMissingRoutine() {
            ToolInput input = ToolInput.of(
                    Map.of("name", "Test Task", "schedule", Map.of("type", "cron", "cron_expression", "0 9 * * *")));
            ToolContext context = ToolContext.empty();

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContent()).contains("Invalid request");
        }

        @Test
        @DisplayName("Should return error for empty routine")
        void testEmptyRoutine() {
            ToolInput input = ToolInput.of(Map.of("name", "Test Task", "schedule",
                    Map.of("type", "cron", "cron_expression", "0 9 * * *"), "routine", List.of()));
            ToolContext context = ToolContext.empty();

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContent()).contains("Invalid request");
        }

        @Test
        @DisplayName("Should reject legacy 'workflow' key with a rename hint")
        void testLegacyWorkflowKeyRejectedWithRenameHint() {
            ToolInput input = ToolInput
                    .of(Map.of("name", "Test Task", "schedule", Map.of("type", "cron", "cron_expression", "0 9 * * *"),
                            "workflow", List.of(Map.of("tool", "TestTool", "tool_params", "{}"))));
            ToolContext context = ToolContext.empty();

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContent()).contains("'workflow' has been renamed to 'routine'");
        }

        @Test
        @DisplayName("Should schedule task with timezone")
        void testScheduleTaskWithTimezone() {
            ToolInput input = ToolInput.of(Map.of("name", "Task with Timezone", "schedule",
                    Map.of("type", "cron", "cron_expression", "0 9 * * *", "timezone", "Asia/Seoul"), "routine",
                    List.of(Map.of("tool", "TestTool", "tool_params", "{}"))));
            ToolContext context = ToolContext.empty();

            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> {
                ScheduledTask task = invocation.getArgument(0);
                assertThat(task.getTimezone()).isEqualTo("Asia/Seoul");
                return task;
            });

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should return error for unexpected exception")
        void testUnexpectedException() {
            ToolInput input = createValidInput();
            ToolContext context = ToolContext.empty();

            when(taskManager.register(any(ScheduledTask.class))).thenThrow(new RuntimeException("Unexpected error"));

            ToolResult result = tool.execute(input, context);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContent()).contains("Failed to schedule task");
            assertThat(result.getContent()).contains("Unexpected error");
        }
    }

    @Nested
    @DisplayName("Null Safety")
    class NullSafety {

        @Test
        @DisplayName("Should throw NullPointerException for null input")
        void testNullInput() {
            assertThatThrownBy(() -> tool.execute(null, ToolContext.empty())).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Input cannot be null");
        }

        @Test
        @DisplayName("Should throw NullPointerException for null context")
        void testNullContext() {
            ToolInput input = createValidInput();
            assertThatThrownBy(() -> tool.execute(input, null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Context cannot be null");
        }
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw exception for null task manager")
        void testNullTaskManager() {
            assertThatThrownBy(() -> new ScheduleTaskTool(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Task manager cannot be null");
        }

        @Test
        @DisplayName("Should throw exception for null bound context ID supplier")
        void testNullBoundRuntimeIdSupplier() {
            assertThatThrownBy(() -> new ScheduleTaskTool(taskManager, null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Default bound context ID supplier cannot be null");
        }

        @Test
        @DisplayName("Should create tool with default bound context ID supplier")
        void testDefaultConstructor() {
            ScheduleTaskTool defaultTool = new ScheduleTaskTool(taskManager);

            assertThat(defaultTool).isNotNull();
            assertThat(defaultTool.getDefinition().getName()).isEqualTo("schedule_task");
        }

        @Test
        @DisplayName("execute_missingAgentRuntimeId_returnsErrorWithoutStacktrace")
        void execute_missingAgentRuntimeId_returnsErrorWithoutStacktrace() {
            // Wire a supplier that throws IllegalStateException, mirroring the no-arg constructor's default behaviour
            // when AGENT_RUNTIME_ID is missing from the ToolContext.
            ScheduleTaskTool throwingTool = new ScheduleTaskTool(taskManager, () -> {
                throw new IllegalStateException(
                        "AGENT_RUNTIME_ID is required in ToolContext but was not provided by the executor");
            });

            // Attach a ListAppender so we can assert the log level the tool used.
            Logger toolLogger = (Logger) LoggerFactory.getLogger(ScheduleTaskTool.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            toolLogger.addAppender(appender);
            try {
                ToolInput input = createValidInput();
                ToolContext context = ToolContext.empty();

                ToolResult result = throwingTool.execute(input, context);

                assertThat(result.isError()).isTrue();
                assertThat(result.getContent()).contains("ScheduleTask requires AGENT_RUNTIME_ID");

                // Configuration error must surface as WARN (no ERROR-with-stacktrace logging).
                assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
                assertThat(appender.list).anyMatch(
                        e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("AGENT_RUNTIME_ID"));

                // The taskManager must not have been invoked since the precondition failed.
                verify(taskManager, never()).register(any(ScheduledTask.class));
            } finally {
                toolLogger.detachAppender(appender);
            }
        }
    }

    @Nested
    @DisplayName("Agent Definition Version")
    class AgentDefinitionVersionRecording {

        private final Agent agent = DefaultAgent.builder().name("reporter").systemPrompt("You write reports.").build();

        @Test
        @DisplayName("Should record the scheduling agent's definition version")
        void testRecordsVersion() {
            ScheduleTaskTool agentTool = ScheduleTaskTool.forAgent(taskManager, agent);
            ToolContext context = ToolContext.builder()
                    .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.from(agent)).build();
            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ToolResult result = agentTool.execute(createValidInput(), context);

            assertThat(result.isSuccess()).isTrue();
            verify(taskManager).register(argThat(
                    task -> AgentDefinitionVersion.from(agent).equals(task.getAgentDefinitionVersion().orElse(null))));
        }

        @Test
        @DisplayName("Should record the version for a discriminated runtime id of the same agent")
        void testRecordsVersionForDiscriminatedRuntimeId() {
            // A discriminator splits runtimes, not definitions — the recorded version is the same either way.
            ScheduleTaskTool agentTool = ScheduleTaskTool.forAgent(taskManager, agent);
            ToolContext context = ToolContext.builder()
                    .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.from(agent, "tenant-a")).build();
            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ToolResult result = agentTool.execute(createValidInput(), context);

            assertThat(result.isSuccess()).isTrue();
            verify(taskManager).register(argThat(
                    task -> AgentDefinitionVersion.from(agent).equals(task.getAgentDefinitionVersion().orElse(null))));
        }

        @Test
        @DisplayName("Should record nothing when the task is bound to a different agent")
        void testRecordsNothingForAnotherAgent() {
            // Recording nothing only disables the drift check; recording another agent's version would make every
            // single fire report a change.
            ScheduleTaskTool agentTool = ScheduleTaskTool.forAgent(taskManager, agent);
            ToolContext context = ToolContext.builder()
                    .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.fromName("other")).build();
            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ToolResult result = agentTool.execute(createValidInput(), context);

            assertThat(result.isSuccess()).isTrue();
            verify(taskManager).register(argThat(task -> task.getAgentDefinitionVersion().isEmpty()));
        }

        @Test
        @DisplayName("Should record nothing when built without an agent")
        void testRecordsNothingWithoutAgent() {
            when(taskManager.register(any(ScheduledTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ToolResult result = tool.execute(createValidInput(), ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            verify(taskManager).register(argThat(task -> task.getAgentDefinitionVersion().isEmpty()));
        }

        @Test
        @DisplayName("Should reject null arguments")
        void testForAgentRejectsNulls() {
            assertThatNullPointerException().isThrownBy(() -> ScheduleTaskTool.forAgent(taskManager, null));
            assertThatNullPointerException().isThrownBy(() -> ScheduleTaskTool.forAgent(null, agent));
        }
    }

    private ToolInput createValidInput() {
        return ToolInput
                .of(Map.of("name", "Daily Report", "schedule", Map.of("type", "cron", "cron_expression", "0 9 * * *"),
                        "routine", List.of(Map.of("tool", "GenerateReport", "tool_params", "{\"format\": \"pdf\"}"))));
    }
}
