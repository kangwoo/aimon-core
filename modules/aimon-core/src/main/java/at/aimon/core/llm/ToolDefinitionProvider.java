package at.aimon.core.llm;

/**
 * Provides tool definitions for LLM function calling.
 *
 * <p>
 * This interface abstracts the creation of {@link ToolDefinition} objects, allowing both static and dynamic tool
 * definitions. Static providers return the same definition every time, while dynamic providers can generate definitions
 * based on runtime context (e.g., available subagents, system state).
 *
 * <p>
 * This pattern follows the Single Responsibility Principle (SRP) by separating tool definition creation from tool
 * execution logic.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Static definition
 *     ToolDefinitionProvider staticProvider = new StaticToolDefinitionProvider(
 *             ToolDefinition.of("bash", "Execute a bash command", schema));
 *
 *     // Dynamic definition
 *     ToolDefinitionProvider dynamicProvider = new DynamicToolDefinitionProvider("task",
 *             () -> "Available subagents: " + registry.getAllSubagents(), schema);
 *
 *     // Use provider
 *     ToolDefinition definition = provider.getDefinition();
 * }
 * </pre>
 *
 * @see ToolDefinition
 * @see StaticToolDefinitionProvider
 * @see DynamicToolDefinitionProvider
 */
public interface ToolDefinitionProvider {
    /**
     * Gets the tool definition.
     *
     * <p>
     * This method may return the same definition each time (static) or generate a new definition based on current
     * context (dynamic).
     *
     * @return The tool definition (never null)
     */
    ToolDefinition getDefinition();
}
