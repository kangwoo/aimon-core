package at.aimon.core.agent.tool;

import java.util.List;

import at.aimon.core.agent.tool.exception.ToolPermissionViolationException;
import at.aimon.core.agent.tool.execution.ToolExecutionResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.ToolUse;

/**
 * Manages the execution of tools with permission validation.
 *
 * <p>
 * The ToolExecutionManager is responsible for orchestrating tool execution, including permission validation, tool
 * lookup, and execution. It provides a high-level API for executing tools safely with proper error handling.
 *
 * <h2>Design Principles</h2>
 * <ul>
 * <li><b>Single Responsibility:</b> Only responsible for coordinating tool execution and permission validation
 * <li><b>Dependency Inversion:</b> Depends on ToolRegistry, ToolExecutor, and ToolPermissionValidator abstractions
 * <li><b>Facade Pattern:</b> Provides a simplified interface to the complex tool execution subsystem
 * </ul>
 *
 * <h2>Execution Flow</h2>
 * <ol>
 * <li><b>Permission Validation:</b> Check if the tool is allowed to be executed
 * <li><b>Tool Lookup:</b> Find the tool in the registry
 * <li><b>Execution:</b> Execute the tool with the provided input and context
 * <li><b>Error Handling:</b> Catch and convert exceptions to ToolExecutionResult
 * </ol>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Execute Single Tool</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolExecutionManager manager = new DefaultToolExecutionManager();
 *     ToolRegistry registry = new DefaultToolRegistry();
 *     registry.register(new BashTool(shell));
 *
 *     ToolUse toolUse = ToolUse.of("tool_123", "bash", Map.of("command", "ls -la"));
 *     ToolContext context = ToolContext.builder().put("environment", environment).build();
 *
 *     // Execute without permission restrictions
 *     ToolExecutionResult result = manager.execute(toolUse, context, registry);
 *
 *     // Execute with permission restrictions
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.of("bash", Map.of("command", "ls*")));
 *     ToolExecutionResult result = manager.execute(toolUse, context, registry, allowedTools);
 * }
 * </pre>
 *
 * <h3>Execute Multiple Tools</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     List<ToolUse> toolUses = List.of(ToolUse.of("tool_1", "bash", Map.of("command", "ls")),
 *             ToolUse.of("tool_2", "read", Map.of("file_path", "/tmp/file.txt")));
 *
 *     List<ToolExecutionResult> results = manager.executeAll(registry, context, toolUses, allowedTools);
 *
 *     // Process results
 *     for (ToolExecutionResult result : results) {
 *         if (result.isSuccess()) {
 *             System.out.println("Tool output: " + result.getContent());
 *         } else {
 *             System.err.println("Tool error: " + result.getError());
 *         }
 *     }
 * }
 * </pre>
 *
 * @see Tool
 * @see ToolRegistry
 * @see ToolExecutionResult
 * @see AllowedTool
 */
public interface ToolExecutionManager {
    /**
     * Executes multiple tools in sequence.
     *
     * <p>
     * This method validates permissions for all tools before executing any of them. If any permission validation fails,
     * an exception is thrown and no tools are executed. If permission validation succeeds, each tool is executed in
     * order, and errors are captured as ToolExecutionResult instead of being thrown.
     *
     * @param toolRegistry
     *            The tool registry for looking up tools (must not be null)
     * @param toolContext
     *            The execution context for tools (must not be null)
     * @param toolUses
     *            The list of tool uses to execute (must not be null, may be empty)
     * @param allowedTools
     *            The list of allowed tools for permission validation (must not be null, empty list means no
     *            restrictions)
     * @return A list of execution results, one per tool use (never null)
     * @throws NullPointerException
     *             if any parameter is null
     * @throws ToolPermissionViolationException
     *             if any tool fails permission validation
     */
    List<ToolExecutionResult> executeAll(ToolRegistry toolRegistry, ToolContext toolContext, List<ToolUse> toolUses,
            List<AllowedTool> allowedTools);

    /**
     * Executes a single tool with permission validation.
     *
     * <p>
     * This method validates permissions, looks up the tool in the registry, and executes it. Any errors during
     * execution are captured and returned as ToolExecutionResult instead of being thrown.
     *
     * @param toolUse
     *            The tool use to execute (must not be null)
     * @param toolContext
     *            The execution context for the tool (must not be null)
     * @param toolRegistry
     *            The tool registry for looking up the tool (must not be null)
     * @param allowedTools
     *            The list of allowed tools for permission validation (must not be null, empty list means no
     *            restrictions)
     * @return The execution result (never null)
     * @throws NullPointerException
     *             if any parameter is null
     * @throws ToolPermissionViolationException
     *             if the tool fails permission validation
     */
    ToolExecutionResult execute(ToolUse toolUse, ToolContext toolContext, ToolRegistry toolRegistry,
            List<AllowedTool> allowedTools);

    /**
     * Executes a single tool without permission restrictions.
     *
     * <p>
     * This is a convenience method that calls {@link #execute(ToolUse, ToolContext, ToolRegistry, List)} with an empty
     * list of allowed tools, meaning no permission restrictions are applied.
     *
     * @param toolUse
     *            The tool use to execute (must not be null)
     * @param toolContext
     *            The execution context for the tool (must not be null)
     * @param toolRegistry
     *            The tool registry for looking up the tool (must not be null)
     * @return The execution result (never null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    default ToolExecutionResult execute(ToolUse toolUse, ToolContext toolContext, ToolRegistry toolRegistry) {
        return execute(toolUse, toolContext, toolRegistry, List.of());
    }

    /**
     * Returns the highest {@link SideEffectLevel} this manager will execute — the ceiling it refuses above.
     *
     * <p>
     * Exposed so that whoever assembles the {@link at.aimon.core.llm.ToolDefinition} list for an LLM call can withhold
     * what this manager would go on to refuse. Deriving the filter from the manager rather than configuring it
     * separately is deliberate: the two cannot then disagree, and a model shown a tool it is not allowed to run spends
     * an iteration discovering that.
     *
     * <p>
     * The default is {@link SideEffectLevel#MUTATING}, the unrestricted ceiling, so an implementation that does not
     * restrict anything needs no override and callers filtering by this value change nothing.
     *
     * @return the most permissive level this manager will execute (never null)
     */
    default SideEffectLevel getMaxSideEffectLevel() {
        return SideEffectLevel.MUTATING;
    }
}
