package at.aimon.cli.hook;

import java.util.Map;
import java.util.Objects;

import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.ToolUse;

/**
 * Hook that displays tools call information in the CLI before tools execution.
 *
 * <p>
 * This hook provides visual feedback to users by showing:
 *
 * <ul>
 * <li>Tool name
 * <li>Tool input parameters (truncated for readability)
 * <li>Executor type (main agent vs subagent) for proper indentation
 * </ul>
 */
public class ToolCallDisplayHook implements PreToolHook {
    private final OutputFormatter outputFormatter;

    /**
     * Creates a new ToolCallDisplayHook.
     *
     * @param outputFormatter
     *            The formatter for displaying tools calls (must not be null)
     * @throws NullPointerException
     *             if outputFormatter is null
     */
    public ToolCallDisplayHook(OutputFormatter outputFormatter) {
        this.outputFormatter = Objects.requireNonNull(outputFormatter, "OutputFormatter cannot be null");
    }

    @Override
    public HookResult execute(PreToolContext context) {
        ToolUse toolUse = context.getCurrentToolUse();
        InvokerType invokerType = context.getInvokerType();

        String inputStr = formatInput(toolUse.getInput());
        outputFormatter.displayToolCall(toolUse.getName(), inputStr, invokerType);

        return HookResult.success();
    }

    /**
     * Formats tools input parameters for display.
     *
     * @param input
     *            The tools input map
     * @return Formatted string representation
     */
    private String formatInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("{");
        int count = 0;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (count > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(": ");

            Object value = entry.getValue();
            if (value instanceof String strValue) {
                // Truncate long strings
                if (strValue.length() > 100) {
                    sb.append("\"").append(strValue.substring(0, 97)).append("...\"");
                } else {
                    sb.append("\"").append(strValue).append("\"");
                }
            } else {
                sb.append(value);
            }
            count++;
        }
        sb.append("}");
        return sb.toString();
    }
}
