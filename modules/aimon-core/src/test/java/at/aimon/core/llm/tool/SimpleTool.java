package at.aimon.core.llm.tool;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.ToolDefinition;

/**
 * Simple tools implementation for testing purposes.
 *
 * <p>
 * This class wraps a ToolDefinition and provides a no-op execute method. Useful for tests that need Tool objects but
 * don't actually execute them.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolDefinition definition = ToolDefinition.of("bash", "Execute command", schema);
 *     Tool tools = SimpleTool.of(definition);
 * }
 * </pre>
 */
public class SimpleTool implements Tool {
    private final ToolDefinition definition;

    private SimpleTool(ToolDefinition definition) {
        this.definition = definition;
    }

    /**
     * Creates a simple tools from a tools definition.
     *
     * @param definition
     *            The tools definition (must not be null)
     * @return A new SimpleTool
     */
    public static SimpleTool of(ToolDefinition definition) {
        return new SimpleTool(definition);
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        return ToolResult.success("Mock execution result");
    }
}
