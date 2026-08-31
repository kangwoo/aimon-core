package at.aimon.core.agent.tool.execution;

import java.util.Objects;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Encapsulates contextual information for tool execution.
 *
 * <p>
 * This class wraps a {@link Tool} instance along with any additional context information that may be needed during tool
 * execution. It serves as a container that can be extended in the future to include more execution-related metadata
 * without breaking existing code.
 *
 * <p>
 * The context is immutable and thread-safe if the contained Tool implementation is thread-safe.
 *
 * <p>
 * <b>Design Rationale:</b>
 *
 * <ul>
 * <li>Encapsulation: Provides a clean separation between tool instances and their execution context
 * <li>Extensibility: The Builder pattern allows adding more context fields in the future without breaking changes
 * <li>Type Safety: Ensures that tool execution always has the required context information
 * <li>Immutability: Prevents unintended modifications during execution
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create a tool instance
 *     Tool bashTool = new BashTool(shell);
 *
 *     // Wrap it in execution context
 *     ToolExecutionContext context = ToolExecutionContext.of(bashTool);
 *
 *     // Or use builder for more control
 *     ToolExecutionContext context = ToolExecutionContext.builder().tool(bashTool).build();
 *
 *     // Access the tool when needed
 *     Tool tool = context.getTool();
 *     ToolResult result = tool.execute(toolUse, toolContext);
 * }
 * </pre>
 *
 * @see Tool
 * @see ToolResult
 * @see ToolContext
 */
public final class ToolExecutionContext {

    private final Tool tool;

    /**
     * Creates a new ToolExecutionContext with the specified tool.
     *
     * @param tool
     *            The tool to execute (must not be null)
     * @throws NullPointerException
     *             if tool is null
     */
    private ToolExecutionContext(Tool tool) {
        this.tool = Objects.requireNonNull(tool, "Tool cannot be null");
    }

    /**
     * Creates a new ToolExecutionContext with the specified tool.
     *
     * <p>
     * This is a convenience factory method for simple cases where only a tool is needed. For more complex scenarios or
     * when future extensibility is desired, use {@link #builder()}.
     *
     * @param tool
     *            The tool to execute (must not be null)
     * @return A new ToolExecutionContext instance
     * @throws NullPointerException
     *             if tool is null
     */
    public static ToolExecutionContext of(Tool tool) {
        return new ToolExecutionContext(tool);
    }

    /**
     * Returns a new builder for constructing ToolExecutionContext instances.
     *
     * <p>
     * The builder pattern is provided for extensibility. Future versions may add additional context fields that can be
     * configured through the builder without breaking existing code.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the tool to be executed.
     *
     * @return The tool (never null)
     */
    public Tool getTool() {
        return tool;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ToolExecutionContext that = (ToolExecutionContext) o;
        return Objects.equals(tool, that.tool);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tool);
    }

    @Override
    public String toString() {
        return "ToolExecutionContext{" + "tool=" + tool + '}';
    }

    /**
     * Builder for constructing ToolExecutionContext instances.
     *
     * <p>
     * This builder provides a fluent API for creating context instances and allows for future extensibility without
     * breaking existing code.
     */
    public static final class Builder {
        private Tool tool;

        private Builder() {
        }

        /**
         * Sets the tool to be executed.
         *
         * @param tool
         *            The tool (must not be null)
         * @return This builder for method chaining
         */
        public Builder tool(Tool tool) {
            this.tool = tool;
            return this;
        }

        /**
         * Builds a new ToolExecutionContext instance.
         *
         * @return A new ToolExecutionContext
         * @throws NullPointerException
         *             if tool has not been set
         */
        public ToolExecutionContext build() {
            return new ToolExecutionContext(tool);
        }
    }
}
