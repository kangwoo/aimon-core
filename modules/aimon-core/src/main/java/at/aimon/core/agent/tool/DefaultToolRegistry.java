package at.aimon.core.agent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.agent.tool.execution.DefaultToolExecutor;
import at.aimon.core.agent.tool.execution.ToolExecutor;
import at.aimon.core.llm.ToolDefinition;

/**
 * Default implementation of {@link ToolRegistry} using in-memory HashMap storage.
 *
 * <p>
 * This class provides a simple registry for tools. It manages complete {@link Tool} implementations and provides access
 * to tool definitions and tool instances.
 *
 * <p>
 * The registry supports:
 *
 * <ul>
 * <li>Tool registration and unregistration
 * <li>Retrieving tool definitions for LLM consumption
 * <li>Retrieving tool instances for execution
 * <li>Listing available tools
 * </ul>
 *
 * <p>
 * <b>Thread-safety:</b> This implementation is NOT thread-safe. If multiple threads need to access the registry
 * concurrently, external synchronization is required or use a thread-safe implementation.
 *
 * <p>
 * <b>Immutability:</b> All query methods ({@link #findAll()}, {@link #findAllExcept(String...)},
 * {@link #getToolNames()}) return immutable collections. The internal tool storage cannot be modified except through
 * the provided mutation methods ({@link #register(Tool)}, {@link #unregister(String)}, {@link #clear()}).
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create registry
 *     ToolRegistry registry = new DefaultToolRegistry();
 *
 *     // Register tools
 *     registry.register(new BashTool(shell));
 *     registry.register(new ReadTool(fileSystem));
 *
 *     // Get tools for execution
 *     List<Tool> tools = registry.getTools();
 *
 *     // Create executor for tool execution
 *     ToolExecutor executor = new DefaultToolExecutor(registry);
 *     ToolUse toolUse = ToolUse.of("tool_123", "bash", Map.of("command", "ls"));
 *     ToolContext context = ToolContext.empty();
 *     ToolResult result = executor.execute(toolUse, context);
 * }
 * </pre>
 *
 * @see ToolRegistry
 * @see Tool
 * @see ToolDefinition
 * @see ToolExecutor
 * @see DefaultToolExecutor
 */
public final class DefaultToolRegistry implements ToolRegistry {

    private static final String FUNCTIONS_PREFIX = "functions.";

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final boolean stripFunctionsPrefix;

    /**
     * Creates a new empty DefaultToolRegistry with functions prefix stripping enabled.
     */
    public DefaultToolRegistry() {
        this(true);
    }

    /**
     * Creates a new empty DefaultToolRegistry with configurable functions prefix stripping.
     *
     * @param stripFunctionsPrefix
     *            if true, tool lookups will strip "functions." prefix from tool names
     */
    public DefaultToolRegistry(boolean stripFunctionsPrefix) {
        this.stripFunctionsPrefix = stripFunctionsPrefix;
    }

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
    @Override
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "Tool cannot be null");
        ToolDefinition definition = Objects.requireNonNull(tool.getDefinition(), "Tool definition cannot be null");
        String name = Objects.requireNonNull(definition.getName(), "Tool name cannot be null");
        tools.put(name, tool);
    }

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
    @Override
    public void unregister(String toolName) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        tools.remove(toolName);
    }

    /**
     * Gets a tool by name.
     *
     * <p>
     * This method is useful for direct access to tools without executing them.
     *
     * <p>
     * If {@code stripFunctionsPrefix} is enabled (default) and the tool name starts with "functions.", the prefix will
     * be stripped before looking up the tool.
     *
     * @param toolName
     *            The name of the tool (must not be null)
     * @return An Optional containing the tool if registered, empty otherwise
     * @throws NullPointerException
     *             if toolName is null
     */
    @Override
    public Optional<Tool> findByName(String toolName) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        final String normalizedName = normalizeName(toolName);
        return Optional.ofNullable(tools.get(normalizedName));
    }

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
    @Override
    public List<Tool> findAll() {
        return List.copyOf(tools.values());
    }

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
    @Override
    public List<Tool> findAllExcept(String... excludedToolNames) {
        Objects.requireNonNull(excludedToolNames, "Excluded tools names cannot be null");
        final Set<String> excludedSet = Set.of(excludedToolNames);
        return tools.values().stream().filter(tool -> !excludedSet.contains(tool.getDefinition().getName())).toList();
    }

    @Override
    public Set<String> findCategories() {
        final Set<String> categories = new LinkedHashSet<>();
        for (Tool tool : tools.values()) {
            categories.add(tool.getDefinition().getCategory());
        }
        return Collections.unmodifiableSet(categories);
    }

    @Override
    public List<Tool> findAllByCategory(String category) {
        Objects.requireNonNull(category, "Category cannot be null");
        return tools.values().stream().filter(tool -> category.equals(tool.getDefinition().getCategory())).toList();
    }

    @Override
    public Map<String, List<Tool>> findAllGroupedByCategory() {
        final Map<String, List<Tool>> grouped = new LinkedHashMap<>();
        for (Tool tool : tools.values()) {
            final String category = tool.getDefinition().getCategory();
            grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(tool);
        }
        final Map<String, List<Tool>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Tool>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Gets the names of all registered tools.
     *
     * <p>
     * The returned set is immutable. The order of tool names is not guaranteed.
     *
     * @return An immutable set of tool names (never null, may be empty)
     */
    public Set<String> getToolNames() {
        return Set.copyOf(tools.keySet());
    }

    /**
     * Checks if a tool with the specified name is registered.
     *
     * <p>
     * If {@code stripFunctionsPrefix} is enabled (default) and the tool name starts with "functions.", the prefix will
     * be stripped before checking.
     *
     * @param toolName
     *            The tool name to check (must not be null)
     * @return true if a tool with that name is registered, false otherwise
     * @throws NullPointerException
     *             if toolName is null
     */
    public boolean hasToolNamed(String toolName) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        final String normalizedName = normalizeName(toolName);
        return tools.containsKey(normalizedName);
    }

    /**
     * Gets the number of registered tools.
     *
     * @return The tool count (never negative)
     */
    @Override
    public int size() {
        return tools.size();
    }

    /**
     * Checks if the registry is empty.
     *
     * @return true if no tools are registered, false otherwise
     */
    @Override
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /** Removes all tools from the registry. */
    @Override
    public void clear() {
        tools.clear();
    }

    /**
     * Normalizes a tool name by stripping the "functions." prefix if enabled.
     *
     * @param toolName
     *            The tool name to normalize
     * @return The normalized tool name
     */
    private String normalizeName(String toolName) {
        if (stripFunctionsPrefix && toolName.startsWith(FUNCTIONS_PREFIX)) {
            return toolName.substring(FUNCTIONS_PREFIX.length());
        }
        return toolName;
    }
}
