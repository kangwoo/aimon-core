package at.aimon.core.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import at.aimon.core.agent.tool.execution.DefaultToolExecutor;
import at.aimon.core.agent.tool.execution.ToolExecutor;
import at.aimon.core.llm.ToolDefinition;

/**
 * Registry interface for managing tool instances and their definitions.
 *
 * <p>
 * This interface provides a centralized registry contract for tools. Implementations manage complete {@link Tool}
 * instances and provide access to both tool definitions (for LLM consumption) and tool instances (for execution).
 *
 * <p>
 * The registry supports:
 *
 * <ul>
 * <li>Tool registration and unregistration by name
 * <li>Retrieving tool instances for execution
 * <li>Querying registered tools with filtering capabilities
 * <li>Registry lifecycle management (size, isEmpty, clear)
 * </ul>
 *
 * <p>
 * <b>Implementation Notes:</b>
 *
 * <ul>
 * <li>Thread-safety is NOT required but implementations MAY provide it
 * <li>All query methods should return immutable collections to prevent external modification
 * <li>Tool instances are stored by reference; modifications to Tool objects will be reflected in the registry
 * <li>Tool names are determined by {@link Tool#getDefinition()} and must be unique
 * </ul>
 *
 * <p>
 * <b>Example usage:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create registry and register tools
 *     ToolRegistry registry = new DefaultToolRegistry();
 *     registry.register(new BashTool(shell));
 *     registry.register(new ReadTool(fileSystem));
 *
 *     // Query registered tools
 *     List<Tool> allTools = registry.getTools();
 *     Optional<Tool> bashTool = registry.getTool("bash");
 *     List<Tool> toolsExceptBash = registry.getToolsExcept("bash");
 *
 *     // Use with tool executor
 *     bashTool.ifPresent(tool -> {
 *         ToolExecutor executor = new DefaultToolExecutor();
 *         ToolExecutionContext context = ToolExecutionContext.of(tool);
 *         ToolExecutionRequest request = ToolExecutionRequest.of("tool_123", Map.of("command", "ls"),
 *                 ToolContext.empty());
 *         ToolExecutionResult result = executor.execute(context, request);
 *     });
 *
 *     // Lifecycle management
 *     int toolCount = registry.size();
 *     registry.unregister("bash");
 *     registry.clear(); // Remove all tools
 * }
 * </pre>
 *
 * @see Tool
 * @see ToolDefinition
 * @see ToolExecutor
 * @see DefaultToolExecutor
 */
public interface ToolRegistry {
    /**
     * Registers a tool in the registry.
     *
     * <p>
     * If a tool with the same name already exists, it will be replaced. The tool name is determined by
     * {@link Tool#getDefinition()}.
     *
     * @param tool
     *            The tool to register (must not be null)
     * @throws NullPointerException
     *             if tool is null or tool definition is null
     */
    void register(Tool tool);

    /**
     * Unregisters a tool from the registry.
     *
     * <p>
     * If no tool with the specified name exists, this method does nothing.
     *
     * @param toolName
     *            The name of the tool to unregister (must not be null)
     * @throws NullPointerException
     *             if toolName is null
     */
    void unregister(String toolName);

    /**
     * Gets a tool by name.
     *
     * <p>
     * This method is useful for direct access to tools without executing them.
     *
     * @param toolName
     *            The name of the tool (must not be null)
     * @return An Optional containing the tool if registered, empty otherwise
     * @throws NullPointerException
     *             if toolName is null
     */
    java.util.Optional<Tool> findByName(String toolName);

    /**
     * Gets all registered tools.
     *
     * <p>
     * This method returns a list of all registered Tool objects. This is useful for contexts that need complete Tool
     * instances rather than just their definitions.
     *
     * <p>
     * The returned list is immutable. The order of tools is not guaranteed.
     *
     * @return An immutable list of tools (never null, may be empty)
     */
    List<Tool> findAll();

    /**
     * Gets all registered tools except the specified tools.
     *
     * <p>
     * This method returns a list of all registered Tool objects excluding the tools with the specified names. If any of
     * the excluded names don't exist, they are silently ignored. If no tool names are provided, all tools are returned.
     *
     * <p>
     * The returned list is immutable. The order of tools is not guaranteed.
     *
     * @param excludedToolNames
     *            The names of the tools to exclude (varargs, may be empty)
     * @return An immutable list of tools (never null, may be empty)
     * @throws NullPointerException
     *             if excludedToolNames array is null
     */
    List<Tool> findAllExcept(String... excludedToolNames);

    /**
     * Gets the set of categories present among registered tools.
     *
     * <p>
     * The returned set preserves the order in which categories first appeared during tool registration. This is useful
     * for displaying tools grouped by category in a stable order driven by registration sequence.
     *
     * @return An immutable set of category strings (never null, may be empty)
     */
    Set<String> findCategories();

    /**
     * Gets all registered tools whose category equals the given value.
     *
     * <p>
     * Tools are returned in registration order. The returned list is immutable.
     *
     * @param category
     *            The category to filter by (must not be null)
     * @return An immutable list of tools (never null, may be empty)
     * @throws NullPointerException
     *             if category is null
     */
    List<Tool> findAllByCategory(String category);

    /**
     * Gets all registered tools grouped by category.
     *
     * <p>
     * The returned map preserves category insertion order (driven by tool registration order). Within each category,
     * tools are listed in registration order. The returned map and its lists are immutable.
     *
     * @return An immutable map of category to tools (never null, may be empty)
     */
    Map<String, List<Tool>> findAllGroupedByCategory();

    /**
     * Gets the number of registered tools.
     *
     * @return The tool count (never negative)
     */
    int size();

    /**
     * Checks if the registry is empty.
     *
     * @return true if no tools are registered, false otherwise
     */
    boolean isEmpty();

    /**
     * Removes all tools from the registry.
     *
     * <p>
     * After calling this method, the registry will be empty ({@link #isEmpty()} returns {@code true}) and
     * {@link #size()} returns 0. All previously registered tools will be removed.
     *
     * <p>
     * This operation is useful for:
     *
     * <ul>
     * <li>Resetting the registry to initial state
     * <li>Cleaning up before registering a new set of tools
     * <li>Testing scenarios that require an empty registry
     * </ul>
     *
     * <p>
     * Note that this method only removes tool references from the registry. It does not affect the Tool objects
     * themselves, which may still be referenced elsewhere.
     */
    void clear();

}
