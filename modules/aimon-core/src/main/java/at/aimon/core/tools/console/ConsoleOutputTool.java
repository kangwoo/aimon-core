package at.aimon.core.tools.console;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Tool for outputting text to the console.
 *
 * <p>
 * This tool allows the agent to display messages directly to the user via the console. It is useful for providing
 * status updates, informational messages, or any text that should be visible to the user during agent execution.
 *
 * <p>
 * The tool supports different output types:
 * <ul>
 * <li>{@code info} - Informational messages (default)
 * <li>{@code error} - Error messages with appropriate formatting
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Simple usage with System.out
 *     Tool outputTool = new ConsoleOutputTool();
 *
 *     // Custom output handlers
 *     Tool outputTool = new ConsoleOutputTool(formatter::displayInfo, formatter::displayError);
 *
 *     ToolInput input = ToolInput.of(Map.of("message", "Processing complete!"));
 *     ToolResult result = outputTool.execute(input, ToolContext.empty());
 * }
 * </pre>
 */
public class ConsoleOutputTool extends AbstractTool {

    public static final String TOOL_NAME = "ConsoleOutput";

    private static final String TYPE_INFO = "info";
    private static final String TYPE_ERROR = "error";

    private final Consumer<String> infoOutput;
    private final Consumer<String> errorOutput;

    /**
     * Creates a new ConsoleOutputTool that writes to System.out and System.err.
     */
    public ConsoleOutputTool() {
        this(System.out::println, System.err::println);
    }

    /**
     * Creates a new ConsoleOutputTool with custom output consumers.
     *
     * <p>
     * This constructor allows integration with custom output mechanisms such as formatters or loggers.
     *
     * @param infoOutput
     *            consumer for informational messages
     * @param errorOutput
     *            consumer for error messages
     */
    public ConsoleOutputTool(Consumer<String> infoOutput, Consumer<String> errorOutput) {
        super(TOOL_NAME, createDescription(), ToolCategories.WORKFLOW, createInputSchema());
        this.infoOutput = Objects.requireNonNull(infoOutput, "Info output consumer cannot be null");
        this.errorOutput = Objects.requireNonNull(errorOutput, "Error output consumer cannot be null");
    }

    private static String createDescription() {
        return "Output a message to the console for the user to see. "
                + "Use this tool when you need to display information, status updates, "
                + "or any text that should be visible to the user. "
                + "Supports 'info' (default) and 'error' output types.";
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("message", Map.of("type", "string", "description", "The message to display to the user"), "type",
                        Map.of("type", "string", "description",
                                "The type of output: 'info' (default) for informational messages, "
                                        + "'error' for error messages",
                                "enum", List.of("info", "error"))),
                "required", List.of("message"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        try {
            String message = input.getRequiredString("message");
            String type = input.getString("type", TYPE_INFO);

            if (TYPE_ERROR.equals(type)) {
                errorOutput.accept(message);
            } else {
                infoOutput.accept(message);
            }

            return ToolResult.success("Message displayed to console.");
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid input: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Failed to output message: " + e.getMessage());
        }
    }
}
