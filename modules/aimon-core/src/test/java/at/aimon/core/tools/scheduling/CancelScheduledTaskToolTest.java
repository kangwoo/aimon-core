package at.aimon.core.tools.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.scheduling.exception.TaskNotFoundException;
import at.aimon.core.scheduling.exception.UnauthorizedTaskAccessException;

@DisplayName("CancelScheduledTaskTool Tests")
class CancelScheduledTaskToolTest {

    private ScheduledTaskManager taskManager;
    private CancelScheduledTaskTool tool;
    private Principal defaultPrincipal;

    @BeforeEach
    void setUp() {
        taskManager = mock(ScheduledTaskManager.class);
        defaultPrincipal = Principal.user("test-user", "Test User");
        tool = new CancelScheduledTaskTool(taskManager, () -> defaultPrincipal);
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinitionTest {

        @Test
        @DisplayName("Should have correct tool name")
        void testToolName() {
            assertThat(tool.getDefinition().getName()).isEqualTo("cancel_scheduled_task");
        }
    }

    @Nested
    @DisplayName("Execution")
    class Execution {

        @Test
        @DisplayName("Should cancel task successfully")
        void testCancelSuccess() {
            String taskId = ScheduledTaskId.generate().toString();
            ToolInput input = ToolInput.of(Map.of("task_id", taskId));
            doNothing().when(taskManager).cancel(any(ScheduledTaskId.class), eq(defaultPrincipal));

            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("cancelled successfully");
        }

        @Test
        @DisplayName("Should return error for non-existent task")
        void testTaskNotFound() {
            String taskId = ScheduledTaskId.generate().toString();
            ToolInput input = ToolInput.of(Map.of("task_id", taskId));
            doThrow(new TaskNotFoundException(taskId)).when(taskManager).cancel(any(ScheduledTaskId.class),
                    eq(defaultPrincipal));

            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Task not found");
        }

        @Test
        @DisplayName("Should return error for unauthorized access")
        void testUnauthorizedAccess() {
            String taskId = ScheduledTaskId.generate().toString();
            ToolInput input = ToolInput.of(Map.of("task_id", taskId));
            doThrow(new UnauthorizedTaskAccessException(taskId, defaultPrincipal)).when(taskManager)
                    .cancel(any(ScheduledTaskId.class), eq(defaultPrincipal));

            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("permission");
        }

        @Test
        @DisplayName("Should return error on unexpected exception")
        void testUnexpectedException() {
            String taskId = ScheduledTaskId.generate().toString();
            ToolInput input = ToolInput.of(Map.of("task_id", taskId));
            doThrow(new RuntimeException("DB error")).when(taskManager).cancel(any(ScheduledTaskId.class),
                    eq(defaultPrincipal));

            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Failed to cancel task");
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
            ToolInput input = ToolInput.of(Map.of("task_id", "test-id"));
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
            assertThatThrownBy(() -> new CancelScheduledTaskTool(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
