package at.aimon.core.agent.tool.execution;

import java.util.Objects;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.llm.ToolUse;

/**
 * Encapsulates a tool execution request with tool use and context.
 *
 * <p>
 * This immutable value object combines the tool invocation parameters with the execution context into a single request
 * object. It ensures that all tool execution requests contain the necessary information and maintains immutability
 * through defensive copying.
 *
 * <h2>Design Characteristics</h2>
 * <ul>
 * <li><strong>Immutability:</strong> All fields are final and the input map is defensively copied to prevent external
 * modification
 * <li><strong>Thread-Safety:</strong> Safe to share across multiple threads
 * <li><strong>Value Object:</strong> Equality based on content, not identity
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create request from components
 *     ToolInput input = ToolInput.of(Map.of("command", "ls -la", "timeout", 5000));
 *     ToolContext context = ToolContext.builder().put("workingDir", "/home/user").build();
 *
 *     ToolExecutionRequest request = ToolExecutionRequest.of("tool_123", input, context);
 *
 *     // Alternative: create from raw map
 *     ToolInput input2 = ToolInput.of(Map.of("file_path", "/tmp/test.txt"));
 *     ToolExecutionRequest request2 = ToolExecutionRequest.of("tool_456", input2, context);
 * }
 * </pre>
 *
 * @see ToolUse
 * @see ToolContext
 * @see ToolExecutionContext
 * @see ToolExecutionResult
 */
public final class ToolExecutionRequest {

    private final String id;
    private final ToolInput input;
    private final ToolContext toolContext;

    private ToolExecutionRequest(String id, ToolInput input, ToolContext toolContext) {
        this.id = Objects.requireNonNull(id, "toolUseId cannot be null");
        this.input = Objects.requireNonNull(input, "toolInput cannot be null");
        this.toolContext = Objects.requireNonNull(toolContext, "ToolContext cannot be null");
    }

    /**
     * Gets the tool use ID (execution ID).
     *
     * <p>
     * This ID uniquely identifies this tool invocation request and is used to track the execution result.
     *
     * @return The tool use ID (never null)
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the tool input parameters.
     *
     * <p>
     * The returned ToolInput is immutable and provides type-safe access to the parameters that the LLM provided for
     * tool execution.
     *
     * @return The tool input parameters (never null)
     */
    public ToolInput getInput() {
        return input;
    }

    /**
     * Gets the tool context.
     *
     * <p>
     * The context provides additional runtime information for tool execution, such as working directory, environment
     * variables, and other execution metadata.
     *
     * @return The tool context (never null)
     */
    public ToolContext getToolContext() {
        return toolContext;
    }

    /**
     * Creates a new ToolExecutionRequest.
     *
     * <p>
     * This factory method creates an immutable request with the provided tool input.
     *
     * @param toolUseId
     *            The tool use ID (must not be null)
     * @param toolInput
     *            The tool input parameters (must not be null)
     * @param toolContext
     *            The tool context (must not be null)
     * @return A new ToolExecutionRequest
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static ToolExecutionRequest of(String toolUseId, ToolInput toolInput, ToolContext toolContext) {
        return new ToolExecutionRequest(toolUseId, toolInput, toolContext);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ToolExecutionRequest that = (ToolExecutionRequest) o;
        return Objects.equals(id, that.id) && Objects.equals(input, that.input)
                && Objects.equals(toolContext, that.toolContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, input, toolContext);
    }

    @Override
    public String toString() {
        return "ToolExecutionRequest{id='" + id + "', inputKeys=" + input.keys() + ", toolContext=" + toolContext + '}';
    }

}
