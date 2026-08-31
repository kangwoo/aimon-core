package at.aimon.cli.hook;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.task.TaskTool;

/**
 * Hook that displays subagent task results in the CLI after tools execution.
 *
 * <p>
 * This hook parses and displays formatted subagent results when the Task tools completes successfully. It extracts:
 *
 * <ul>
 * <li>Subagent name and description
 * <li>Execution status (success/failure)
 * <li>Performance metrics (iterations, tokens)
 * <li>Result summary
 * </ul>
 */
public class SubagentResultDisplayHook implements PostToolHook {
    private final OutputFormatter outputFormatter;

    // Pattern to parse subagent task result
    private static final Pattern SUBAGENT_RESULT_PATTERN = Pattern.compile(
            "=== Subagent Task Result ===\\s+" + "Subagent: (.+)\\s+" + "Task: (.+)\\s+" + "Status: (\\w+)\\s+"
                    + "Iterations: (\\d+)\\s+" + "Tokens: (\\d+)\\s+" + "\\s+Result:\\s+" + "([\\s\\S]+)",
            Pattern.MULTILINE);

    /**
     * Creates a new SubagentResultDisplayHook.
     *
     * @param outputFormatter
     *            The formatter for displaying subagent results (must not be null)
     * @throws NullPointerException
     *             if outputFormatter is null
     */
    public SubagentResultDisplayHook(OutputFormatter outputFormatter) {
        this.outputFormatter = Objects.requireNonNull(outputFormatter, "OutputFormatter cannot be null");
    }

    @Override
    public HookResult execute(PostToolContext context) {
        ToolUse toolUse = context.getToolUse();
        ToolUseResult result = context.getCurrentToolUseResult();
        InvokerType invokerType = context.getInvokerType();

        // Only process successful Task tools executions
        if (!result.isSuccess() || !TaskTool.TOOL_NAME.equals(toolUse.getName())) {
            return HookResult.success();
        }

        displaySubagentResultIfPresent(result.getContent(), invokerType);
        return HookResult.success();
    }

    /**
     * Displays subagent result if the content contains a subagent task result.
     *
     * @param content
     *            The tools result content
     * @param invokerType
     *            The executor type for indentation
     */
    private void displaySubagentResultIfPresent(String content, InvokerType invokerType) {
        if (content == null || content.isEmpty()) {
            return;
        }

        Matcher matcher = SUBAGENT_RESULT_PATTERN.matcher(content);
        if (matcher.find()) {
            String subagentName = matcher.group(1).trim();
            String description = matcher.group(2).trim();
            String status = matcher.group(3).trim();
            int iterations = Integer.parseInt(matcher.group(4).trim());
            int tokens = Integer.parseInt(matcher.group(5).trim());
            String summary = matcher.group(6).trim();

            // Create indentation based on executor type depth
            String indent = "  ".repeat(invokerType.getDisplayDepth());

            // Display formatted subagent result
            outputFormatter.displaySubagentResult(indent, subagentName, description, status, iterations, tokens,
                    summary);
        }
    }
}
