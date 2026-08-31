package at.aimon.core.tools.todo;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.ToolDefinition;

/** Tests for {@link TodoWriteTool}. */
class TodoWriteToolTest {

    private TodoRepository repository;
    private TodoWriteTool tool;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTodoRepository();
        tool = new TodoWriteTool(repository);
    }

    @Test
    void test_Constructor_NullRepository_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> new TodoWriteTool(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Repository cannot be null");
    }

    @Test
    void test_Constructor_NullContextKey_ThrowsException() {
        // Arrange
        TodoRepository repo = new InMemoryTodoRepository();

        // Act & Assert
        assertThatThrownBy(() -> new TodoWriteTool(repo, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Context key cannot be null");
    }

    @Test
    void test_Constructor_WithCustomContextKey_Success() {
        // Arrange
        TodoRepository repo = new InMemoryTodoRepository();
        ToolContextKey<String> customKey = ToolContextKey.of("sessionId", String.class);

        // Act & Assert - Should not throw
        assertThatCode(() -> new TodoWriteTool(repo, customKey)).doesNotThrowAnyException();
    }

    @Test
    void test_GetDefinition_ReturnsValidDefinition() {
        // Act
        ToolDefinition definition = tool.getDefinition();

        // Assert
        assertThat(definition.getName()).isEqualTo("TodoWrite");
        assertThat(definition.getDescription()).contains("structured task list");
        assertThat(definition.getInputSchema()).containsKey("properties");
    }

    @Test
    void test_Execute_ValidTodos_Success() {
        // Arrange
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "completed", "activeForm", "Running tests"),
                        Map.of("content", "Fix bugs", "status", "in_progress", "activeForm", "Fixing bugs"),
                        Map.of("content", "Update docs", "status", "pending", "activeForm", "Updating docs")));
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("3 total tasks");
        assertThat(result.getContent()).contains("Completed: 1");
        assertThat(result.getContent()).contains("In Progress: Fixing bugs");
        assertThat(result.getContent()).contains("Pending: 1");

        // Verify saved to repository
        assertThat(repository.exists("default")).isTrue();
    }

    @Test
    void test_Execute_WithContextId_SavesWithCorrectContext() {
        // Arrange
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "in_progress", "activeForm", "Running tests")));
        ToolContext context = ToolContext.builder().put(TodoWriteTool.CONTEXT_ID_KEY, "session-123").build();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(repository.exists("session-123")).isTrue();
        assertThat(repository.exists("default")).isFalse();
    }

    @Test
    void test_Execute_MissingTodos_Error() {
        // Arrange
        Map<String, Object> toolUse = Map.of();
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Missing required parameter: 'todos'");
    }

    @Test
    void test_Execute_NoInProgressTask_NotAllCompleted_Error() {
        // Arrange - some completed, some pending, but none in_progress
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "completed", "activeForm", "Running tests"),
                        Map.of("content", "Fix bugs", "status", "pending", "activeForm", "Fixing bugs")));
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("in_progress");
    }

    @Test
    void test_Execute_MultipleInProgressTasks_Error() {
        // Arrange
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "in_progress", "activeForm", "Running tests"),
                        Map.of("content", "Fix bugs", "status", "in_progress", "activeForm", "Fixing bugs")));
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("At most ONE task can be 'in_progress'");
        assertThat(result.getContent()).contains("found 2");
    }

    @Test
    void test_Execute_InvalidStatus_Error() {
        // Arrange
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "invalid_status", "activeForm", "Running tests")));
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Failed to parse todos");
    }

    @Test
    void test_Execute_MissingContent_Error() {
        // Arrange
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("status", "in_progress", "activeForm", "Running tests")));
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Failed to parse todos");
    }

    @Test
    void test_Execute_BlankContent_Error() {
        // Arrange
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "  ", "status", "in_progress", "activeForm", "Running tests")));
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isError()).isTrue();
    }

    @Test
    void test_Execute_EmptyTodoList_Error() {
        // Arrange - Empty list is not allowed
        Map<String, Object> toolUse = Map.of("todos", List.of());
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Todo list cannot be empty");
    }

    @Test
    void test_Execute_SingleInProgressTask_Success() {
        // Arrange
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Fix bugs", "status", "in_progress", "activeForm", "Fixing bugs")));
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("1 total tasks");
        assertThat(result.getContent()).contains("Completed: 0");
        assertThat(result.getContent()).contains("In Progress: Fixing bugs");
        assertThat(result.getContent()).contains("Pending: 0");
    }

    @Test
    void test_Execute_NullToolUse_ThrowsException() {
        // Arrange
        ToolContext context = ToolContext.empty();

        // Act & Assert
        assertThatThrownBy(() -> tool.execute(null, context)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Input cannot be null");
    }

    @Test
    void test_Execute_NullContext_ThrowsException() {
        // Arrange
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "in_progress", "activeForm", "Running tests")));

        // Act & Assert
        assertThatThrownBy(() -> tool.execute(ToolInput.of(toolUse), null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Context cannot be null");
    }

    @Test
    void test_Execute_UpdateExistingTodos_Success() {
        // Arrange - First save
        Map<String, Object> toolUse1 = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "in_progress", "activeForm", "Running tests")));
        ToolContext context = ToolContext.empty();
        tool.execute(ToolInput.of(toolUse1), context);

        // Act - Update
        Map<String, Object> toolUse2 = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "completed", "activeForm", "Running tests"),
                        Map.of("content", "Fix bugs", "status", "in_progress", "activeForm", "Fixing bugs")));
        ToolResult result = tool.execute(ToolInput.of(toolUse2), context);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("2 total tasks");
        assertThat(result.getContent()).contains("Completed: 1");
        assertThat(result.getContent()).contains("In Progress: Fixing bugs");
    }

    @Test
    void test_Execute_AllTasksCompleted_Success() {
        // Arrange - All tasks completed, no in_progress (final update)
        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "completed", "activeForm", "Running tests"),
                        Map.of("content", "Fix bugs", "status", "completed", "activeForm", "Fixing bugs"),
                        Map.of("content", "Update docs", "status", "completed", "activeForm", "Updating docs")));
        ToolContext context = ToolContext.empty();

        // Act
        ToolResult result = tool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("3 total tasks");
        assertThat(result.getContent()).contains("Completed: 3 (all done)");
    }

    @Test
    void test_Execute_WithCustomContextKey_Success() {
        // Arrange
        TodoRepository customRepo = new InMemoryTodoRepository();
        ToolContextKey<String> sessionKey = ToolContextKey.of("sessionId", String.class);
        TodoWriteTool customTool = new TodoWriteTool(customRepo, sessionKey);

        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "in_progress", "activeForm", "Running tests")));
        ToolContext context = ToolContext.builder().put(sessionKey, "session-xyz").build();

        // Act
        ToolResult result = customTool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(customRepo.exists("session-xyz")).isTrue();
        assertThat(customRepo.exists("default")).isFalse();
    }

    @Test
    void test_Execute_WithCustomContextKey_DefaultsToDefault() {
        // Arrange
        TodoRepository customRepo = new InMemoryTodoRepository();
        ToolContextKey<String> sessionKey = ToolContextKey.of("sessionId", String.class);
        TodoWriteTool customTool = new TodoWriteTool(customRepo, sessionKey);

        Map<String, Object> toolUse = Map.of("todos",
                List.of(Map.of("content", "Run tests", "status", "in_progress", "activeForm", "Running tests")));
        ToolContext context = ToolContext.empty(); // No sessionId provided

        // Act
        ToolResult result = customTool.execute(ToolInput.of(toolUse), context);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(customRepo.exists("default")).isTrue();
    }
}
