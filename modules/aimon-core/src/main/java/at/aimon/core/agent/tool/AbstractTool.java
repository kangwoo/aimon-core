package at.aimon.core.agent.tool;

import java.util.Map;
import java.util.Objects;

import at.aimon.core.llm.DynamicToolDefinitionProvider;
import at.aimon.core.llm.StaticToolDefinitionProvider;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolDefinitionProvider;

/**
 * Abstract base class for tools implementations.
 *
 * <p>
 * This class provides convenient infrastructure for creating tools by managing the {@link ToolDefinition} creation and
 * storage via {@link ToolDefinitionProvider}. Subclasses can choose between static and dynamic tools definitions:
 *
 * <ul>
 * <li>Static definitions: Use the constructor with name, description, and schema
 * <li>Dynamic definitions: Use the constructor with {@link ToolDefinitionProvider}
 * </ul>
 *
 * <p>
 * Static definitions are suitable for tools whose metadata never changes, while dynamic definitions allow descriptions
 * to reflect runtime context (e.g., available subagents, system state).
 *
 * <p>
 * Thread-safe as long as the ToolDefinitionProvider is thread-safe.
 *
 * <p>
 * Example usage (static definition):
 *
 * <pre>
 * {
 *     &#64;code
 *     public class BashTool extends AbstractTool {
 *         public BashTool() {
 *             super("bash", "Execute a bash command",
 *                     Map.of("type", "object", "properties",
 *                             Map.of("command",
 *                                     Map.of("type", "string", "description", "The bash command to execute")),
 *                             "required", List.of("command")));
 *         }
 *
 *         &#64;Override
 *         public ToolResult execute(ToolInput input, ToolContext context) {
 *             // Implementation here
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>
 * Example usage (dynamic definition):
 *
 * <pre>
 * {
 *     &#64;code
 *     public class TaskTool extends AbstractTool {
 *         public TaskTool(SubagentRegistry registry) {
 *             super(new DynamicToolDefinitionProvider("task", () -> buildDescription(registry), createInputSchema()));
 *         }
 *
 *         private static String buildDescription(SubagentRegistry registry) {
 *             return "Available subagents: " + registry.getAllSubagents();
 *         }
 *
 *         &#64;Override
 *         public ToolResult execute(ToolInput input, ToolContext context) {
 *             // Implementation here
 *         }
 *     }
 * }
 * </pre>
 *
 * @see Tool
 * @see ToolDefinition
 * @see ToolDefinitionProvider
 */
public abstract class AbstractTool implements Tool {
    private final ToolDefinitionProvider definitionProvider;

    /**
     * Creates a new AbstractTool with the specified definition parameters and the default category.
     *
     * <p>
     * This constructor creates a static tools definition using {@link StaticToolDefinitionProvider} with the category
     * set to {@link ToolDefinition#DEFAULT_CATEGORY}. The definition remains constant throughout the tools's lifetime.
     *
     * @param name
     *            The tools name (must not be null)
     * @param description
     *            The tools description (must not be null)
     * @param inputSchema
     *            The input schema in JSON Schema format (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    protected AbstractTool(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, ToolDefinition.DEFAULT_CATEGORY, inputSchema);
    }

    /**
     * Creates a new AbstractTool with the specified definition parameters including category.
     *
     * <p>
     * This constructor creates a static tools definition using {@link StaticToolDefinitionProvider}. The definition
     * remains constant throughout the tools's lifetime.
     *
     * @param name
     *            The tools name (must not be null)
     * @param description
     *            The tools description (must not be null)
     * @param category
     *            The tools category (must not be null)
     * @param inputSchema
     *            The input schema in JSON Schema format (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    protected AbstractTool(String name, String description, String category, Map<String, Object> inputSchema) {
        ToolDefinition definition = ToolDefinition.of(Objects.requireNonNull(name, "Name cannot be null"),
                Objects.requireNonNull(description, "Description cannot be null"),
                Objects.requireNonNull(category, "Category cannot be null"),
                Objects.requireNonNull(inputSchema, "Input schema cannot be null"));
        this.definitionProvider = new StaticToolDefinitionProvider(definition);
    }

    /**
     * Creates a new AbstractTool with the specified definition provider.
     *
     * <p>
     * This constructor allows tools to use dynamic definitions that can change based on runtime context. Use
     * {@link DynamicToolDefinitionProvider} for descriptions that depend on system state.
     *
     * @param definitionProvider
     *            The tools definition provider (must not be null)
     * @throws NullPointerException
     *             if definitionProvider is null
     */
    protected AbstractTool(ToolDefinitionProvider definitionProvider) {
        this.definitionProvider = Objects.requireNonNull(definitionProvider, "Tool definition provider cannot be null");
    }

    /**
     * Gets the tools definition.
     *
     * <p>
     * For static definitions, this always returns the same definition. For dynamic definitions, this may return
     * different definitions based on current system state.
     *
     * @return The tools definition (never null)
     */
    @Override
    public ToolDefinition getDefinition() {
        return definitionProvider.getDefinition();
    }
}
