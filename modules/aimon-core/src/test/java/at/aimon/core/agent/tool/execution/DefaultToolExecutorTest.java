package at.aimon.core.agent.tool.execution;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolExecutionException;
import at.aimon.core.agent.tool.schema.DefaultToolInputSchemaValidator;
import at.aimon.core.agent.tool.schema.SchemaValidationMode;

/**
 * Unit tests for {@link DefaultToolExecutor}.
 *
 * <p>
 * DefaultToolExecutor is a stateless executor that delegates tool execution to the Tool instance provided in the
 * ToolExecutionContext. These tests verify the core execution flow and error handling.
 */
@DisplayName("DefaultToolExecutor Tests")
class DefaultToolExecutorTest {

    @Nested
    @DisplayName("Constructor")
    class Constructor {
        @Test
        @DisplayName("Should create executor successfully")
        void testConstructor() {
            DefaultToolExecutor executor = new DefaultToolExecutor();

            assertThat(executor).isNotNull();
        }
    }

    @Nested
    @DisplayName("Tool Execution")
    class ToolExecution {
        @Test
        @DisplayName("Should execute tool successfully")
        void testExecuteTool_ValidInput_Success() {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor();
            Tool tool = new MockTool("test-tool", "Test tool");
            ToolExecutionContext context = ToolExecutionContext.of(tool);
            ToolExecutionRequest request = ToolExecutionRequest.of("use1", ToolInput.of(Map.of("key", "value")),
                    ToolContext.empty());

            // Act
            ToolExecutionResult result = executor.execute(context, request);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getToolExecutionId()).isEqualTo("use1");
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("test-tool");
            assertThat(result.getContent()).contains("key=value");
        }

        @Test
        @DisplayName("Should execute tool with empty input")
        void testExecuteTool_EmptyInput_Success() {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor();
            Tool tool = new MockTool("empty-tool", "Empty test");
            ToolExecutionContext context = ToolExecutionContext.of(tool);
            ToolExecutionRequest request = ToolExecutionRequest.of("use2", ToolInput.of(Map.of()), ToolContext.empty());

            // Act
            ToolExecutionResult result = executor.execute(context, request);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getToolExecutionId()).isEqualTo("use2");
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should pass tool context to tool")
        void testExecuteTool_WithContext_ContextPassed() {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor();
            Tool tool = new MockTool("context-tool", "Context test");
            ToolExecutionContext executionContext = ToolExecutionContext.of(tool);
            ToolContext toolContext = ToolContext.builder().put("contextKey", "contextValue").build();
            ToolExecutionRequest request = ToolExecutionRequest.of("use3", ToolInput.of(Map.of()), toolContext);

            // Act
            ToolExecutionResult result = executor.execute(executionContext, request);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should execute different tools sequentially")
        void testExecuteTool_MultipleCalls_AllSucceed() {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor();
            Tool tool1 = new MockTool("tool1", "First tool");
            Tool tool2 = new MockTool("tool2", "Second tool");

            ToolExecutionContext context1 = ToolExecutionContext.of(tool1);
            ToolExecutionRequest request1 = ToolExecutionRequest.of("use1", ToolInput.of(Map.of()),
                    ToolContext.empty());

            ToolExecutionContext context2 = ToolExecutionContext.of(tool2);
            ToolExecutionRequest request2 = ToolExecutionRequest.of("use2", ToolInput.of(Map.of()),
                    ToolContext.empty());

            // Act
            ToolExecutionResult result1 = executor.execute(context1, request1);
            ToolExecutionResult result2 = executor.execute(context2, request2);

            // Assert
            assertThat(result1.getContent()).contains("tool1");
            assertThat(result2.getContent()).contains("tool2");
        }

        @Test
        @DisplayName("Should propagate tool execution exception")
        void testExecuteTool_ToolThrowsException_ExceptionPropagated() {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor();
            Tool failingTool = new FailingMockTool("failing-tool", "Failing tool");
            ToolExecutionContext context = ToolExecutionContext.of(failingTool);
            ToolExecutionRequest request = ToolExecutionRequest.of("use1", ToolInput.of(Map.of()), ToolContext.empty());

            // Act & Assert
            assertThatThrownBy(() -> executor.execute(context, request)).isInstanceOf(ToolExecutionException.class)
                    .hasMessageContaining("Tool execution failed");
        }

        @Test
        @DisplayName("Should reject null execution context")
        void testExecuteTool_NullContext_ThrowsException() {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor();
            ToolExecutionRequest request = ToolExecutionRequest.of("use1", ToolInput.of(Map.of()), ToolContext.empty());

            // Act & Assert
            assertThatThrownBy(() -> executor.execute(null, request)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ToolExecutionContext cannot be null");
        }

        @Test
        @DisplayName("Should reject null request")
        void testExecuteTool_NullRequest_ThrowsException() {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor();
            Tool tool = new MockTool("test-tool", "Test tool");
            ToolExecutionContext context = ToolExecutionContext.of(tool);

            // Act & Assert
            assertThatThrownBy(() -> executor.execute(context, null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ToolExecutionRequest cannot be null");
        }

        @Test
        @DisplayName("Should be thread-safe for concurrent execution")
        void testExecuteTool_ConcurrentExecution_ThreadSafe() throws InterruptedException {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor();
            Tool tool = new MockTool("concurrent-tool", "Concurrent test");
            ToolExecutionContext context = ToolExecutionContext.of(tool);

            // Act - Execute concurrently from multiple threads
            Thread thread1 = new Thread(() -> {
                ToolExecutionRequest request = ToolExecutionRequest.of("use1", ToolInput.of(Map.of("thread", "1")),
                        ToolContext.empty());
                ToolExecutionResult result = executor.execute(context, request);
                assertThat(result.isSuccess()).isTrue();
            });

            Thread thread2 = new Thread(() -> {
                ToolExecutionRequest request = ToolExecutionRequest.of("use2", ToolInput.of(Map.of("thread", "2")),
                        ToolContext.empty());
                ToolExecutionResult result = executor.execute(context, request);
                assertThat(result.isSuccess()).isTrue();
            });

            thread1.start();
            thread2.start();

            thread1.join();
            thread2.join();

            // Assert - No exceptions thrown
        }
    }

    @Nested
    @DisplayName("Schema Validation")
    class SchemaValidation {

        @Test
        @DisplayName("ENFORCE must not run the tool when the input does not match its schema")
        void testEnforce_ViolatingInput_ToolNotExecuted() {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor(new DefaultToolInputSchemaValidator(),
                    SchemaValidationMode.ENFORCE);
            RecordingTool tool = new RecordingTool();
            ToolExecutionContext context = ToolExecutionContext.of(tool);
            ToolExecutionRequest request = ToolExecutionRequest.of("use1", ToolInput.of(Map.of()), ToolContext.empty());

            // Act
            ToolExecutionResult result = executor.execute(context, request);

            // Assert
            assertThat(tool.executed).isFalse();
            assertThat(result.getToolExecutionId()).isEqualTo("use1");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getContent()).contains("Invalid input for tool 'recording-tool'")
                    .contains("Parameter 'file_path' is required (type: string). The tool was not executed.");
        }

        @Test
        @DisplayName("WARN runs the tool anyway when the input does not match its schema")
        void testWarn_ViolatingInput_ToolExecuted() {
            // Arrange — the no-arg constructor is the mode this feature ships in
            DefaultToolExecutor executor = new DefaultToolExecutor();
            RecordingTool tool = new RecordingTool();
            ToolExecutionContext context = ToolExecutionContext.of(tool);
            ToolExecutionRequest request = ToolExecutionRequest.of("use1", ToolInput.of(Map.of()), ToolContext.empty());

            // Act
            ToolExecutionResult result = executor.execute(context, request);

            // Assert
            assertThat(tool.executed).isTrue();
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("OFF runs the tool without consulting the validator at all")
        void testOff_ViolatingInput_ValidatorNotConsulted() {
            // Arrange — a validator that would blow up if it were reached
            DefaultToolExecutor executor = new DefaultToolExecutor((inputSchema, input) -> {
                throw new IllegalStateException("Validator must not be consulted in OFF mode");
            }, SchemaValidationMode.OFF);
            RecordingTool tool = new RecordingTool();
            ToolExecutionContext context = ToolExecutionContext.of(tool);
            ToolExecutionRequest request = ToolExecutionRequest.of("use1", ToolInput.of(Map.of()), ToolContext.empty());

            // Act
            ToolExecutionResult result = executor.execute(context, request);

            // Assert
            assertThat(tool.executed).isTrue();
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("ENFORCE runs the tool when the input matches its schema")
        void testEnforce_ValidInput_ToolExecuted() {
            // Arrange
            DefaultToolExecutor executor = new DefaultToolExecutor(new DefaultToolInputSchemaValidator(),
                    SchemaValidationMode.ENFORCE);
            RecordingTool tool = new RecordingTool();
            ToolExecutionContext context = ToolExecutionContext.of(tool);
            ToolExecutionRequest request = ToolExecutionRequest.of("use1",
                    ToolInput.of(Map.of("file_path", "/tmp/a.txt")), ToolContext.empty());

            // Act
            ToolExecutionResult result = executor.execute(context, request);

            // Assert
            assertThat(tool.executed).isTrue();
            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should reject null validator and null mode")
        void testConstructor_NullArguments_ThrowsException() {
            assertThatThrownBy(() -> new DefaultToolExecutor(null, SchemaValidationMode.WARN))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ToolInputSchemaValidator cannot be null");
            assertThatThrownBy(() -> new DefaultToolExecutor(new DefaultToolInputSchemaValidator(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SchemaValidationMode cannot be null");
        }
    }

    /** Mock tool with a strict schema that records whether it was reached. */
    private static class RecordingTool extends AbstractTool {

        private boolean executed;

        RecordingTool() {
            super("recording-tool", "Records whether it was reached",
                    Map.of("type", "object", "additionalProperties", false, "properties",
                            Map.of("file_path", Map.of("type", "string")), "required", List.of("file_path")));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            executed = true;
            return ToolResult.success("Executed");
        }
    }

    /** Mock tool implementation for testing. */
    private static class MockTool extends AbstractTool {
        private final String description;

        public MockTool(String name, String description) {
            super(name, description, Map.of("type", "object"));
            this.description = description;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult
                    .success("Executed " + getDefinition().getName() + " (" + description + ") with input " + input);
        }
    }

    /** Mock tool that always fails for testing error handling. */
    private static class FailingMockTool extends AbstractTool {

        public FailingMockTool(String name, String description) {
            super(name, description, Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            throw new ToolExecutionException("Tool execution failed");
        }
    }
}
