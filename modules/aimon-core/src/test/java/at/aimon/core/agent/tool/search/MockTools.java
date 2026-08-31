package at.aimon.core.agent.tool.search;

import java.util.List;
import java.util.Map;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Test utility for creating mock tools.
 */
final class MockTools {

    private MockTools() {
    }

    static Tool create(String name, String description) {
        return new MockTool(name, description, Map.of("type", "object", "properties", Map.of()));
    }

    static Tool create(String name, String description, Map<String, Object> inputSchema) {
        return new MockTool(name, description, inputSchema);
    }

    static Tool createWithParams(String name, String description, Map<String, Object> properties) {
        return new MockTool(name, description,
                Map.of("type", "object", "properties", properties, "required", List.of()));
    }

    private static class MockTool extends AbstractTool {
        MockTool(String name, String description, Map<String, Object> inputSchema) {
            super(name, description, inputSchema);
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("mock");
        }
    }

}
