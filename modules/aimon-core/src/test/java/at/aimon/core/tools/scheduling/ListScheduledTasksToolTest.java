package at.aimon.core.tools.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskManager;

@DisplayName("ListScheduledTasksTool Tests")
class ListScheduledTasksToolTest {

    private ScheduledTaskManager taskManager;
    private ListScheduledTasksTool tool;
    private Principal defaultPrincipal;

    @BeforeEach
    void setUp() {
        taskManager = mock(ScheduledTaskManager.class);
        defaultPrincipal = Principal.user("test-user", "Test User");
        tool = new ListScheduledTasksTool(taskManager, () -> defaultPrincipal);
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinitionTest {

        @Test
        @DisplayName("Should have correct tool name")
        void testToolName() {
            assertThat(tool.getDefinition().getName()).isEqualTo("list_scheduled_tasks");
        }
    }

    @Nested
    @DisplayName("Execution")
    class Execution {

        @Test
        @DisplayName("Should return no tasks message when empty")
        void testNoTasks() {
            when(taskManager.listByOwner(defaultPrincipal)).thenReturn(List.of());

            ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("No scheduled tasks found");
        }

        @Test
        @DisplayName("Should list tasks with formatted output")
        void testListTasks() {
            ScheduledTask task = ScheduledTask.builder().id(ScheduledTaskId.generate()).name("Test Task")
                    .cronExpression("0 9 * * *").routine(List.of(RoutineStep.of("echo", "{\"message\":\"hello\"}")))
                    .owner(defaultPrincipal).boundRuntimeId(AgentRuntimeId.of("agent:test-1")).enabled(true).build();
            when(taskManager.listByOwner(defaultPrincipal)).thenReturn(List.of(task));

            ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Found 1 scheduled task(s)");
            assertThat(result.getContent()).contains("Test Task");
            assertThat(result.getContent()).contains("0 9 * * *");
            assertThat(result.getContent()).contains("Enabled");
        }

        @Test
        @DisplayName("Should return error on unexpected exception")
        void testUnexpectedException() {
            when(taskManager.listByOwner(defaultPrincipal)).thenThrow(new RuntimeException("DB error"));

            ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Failed to list tasks");
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
            assertThatThrownBy(() -> tool.execute(ToolInput.of(), null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Context cannot be null");
        }
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw exception for null task manager")
        void testNullTaskManager() {
            assertThatThrownBy(() -> new ListScheduledTasksTool(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
