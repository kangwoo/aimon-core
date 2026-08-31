package at.aimon.core.tools.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;

class ConsoleOutputToolTest {

    private List<String> infoMessages;
    private List<String> errorMessages;
    private ConsoleOutputTool tool;

    @BeforeEach
    void setUp() {
        infoMessages = new ArrayList<>();
        errorMessages = new ArrayList<>();
        tool = new ConsoleOutputTool(infoMessages::add, errorMessages::add);
    }

    @Test
    void testToolName() {
        assertEquals("ConsoleOutput", ConsoleOutputTool.TOOL_NAME);
        assertEquals("ConsoleOutput", tool.getDefinition().getName());
    }

    @Test
    void testToolDescription() {
        String description = tool.getDefinition().getDescription();
        assertNotNull(description);
        assertTrue(description.contains("console"));
    }

    @Test
    void testExecuteWithValidMessage() {
        ToolInput input = ToolInput.of(Map.of("message", "Hello, World!"));
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertTrue(result.isSuccess());
        assertEquals(1, infoMessages.size());
        assertEquals("Hello, World!", infoMessages.get(0));
        assertTrue(errorMessages.isEmpty());
    }

    @Test
    void testExecuteWithInfoType() {
        ToolInput input = ToolInput.of(Map.of("message", "Info message", "type", "info"));
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertTrue(result.isSuccess());
        assertEquals(1, infoMessages.size());
        assertEquals("Info message", infoMessages.get(0));
        assertTrue(errorMessages.isEmpty());
    }

    @Test
    void testExecuteWithErrorType() {
        ToolInput input = ToolInput.of(Map.of("message", "Error message", "type", "error"));
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertTrue(result.isSuccess());
        assertTrue(infoMessages.isEmpty());
        assertEquals(1, errorMessages.size());
        assertEquals("Error message", errorMessages.get(0));
    }

    @Test
    void testExecuteWithMultilineMessage() {
        String multilineMessage = "Line 1\nLine 2\nLine 3";
        ToolInput input = ToolInput.of(Map.of("message", multilineMessage));
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertTrue(result.isSuccess());
        assertEquals(1, infoMessages.size());
        assertEquals(multilineMessage, infoMessages.get(0));
    }

    @Test
    void testExecuteWithEmptyMessage() {
        ToolInput input = ToolInput.of(Map.of("message", ""));
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertTrue(result.isSuccess());
        assertEquals(1, infoMessages.size());
        assertEquals("", infoMessages.get(0));
    }

    @Test
    void testExecuteWithMissingMessage() {
        ToolInput input = ToolInput.of(Map.of());
        ToolResult result = tool.execute(input, ToolContext.empty());

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("Invalid input"));
    }

    @Test
    void testDefaultConstructor() {
        ConsoleOutputTool defaultTool = new ConsoleOutputTool();
        assertNotNull(defaultTool);
        assertEquals("ConsoleOutput", defaultTool.getDefinition().getName());
    }

    @Test
    void testDefaultTypeIsInfo() {
        ToolInput input = ToolInput.of(Map.of("message", "Default type message"));
        tool.execute(input, ToolContext.empty());

        assertEquals(1, infoMessages.size());
        assertTrue(errorMessages.isEmpty());
    }
}
