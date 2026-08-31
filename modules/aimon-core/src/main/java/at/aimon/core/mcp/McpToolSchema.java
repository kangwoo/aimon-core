package at.aimon.core.mcp;

import java.util.Map;
import java.util.Objects;

/**
 * MCP server tool definition.
 *
 * <p>
 * An immutable object containing tool information extracted from the MCP protocol's {@code tools/list} response. It is
 * converted to AIMON's {@link at.aimon.core.llm.ToolDefinition} for use.
 *
 * <h2>inputSchema Format</h2>
 * <p>
 * {@code inputSchema} contains the complete JSON Schema object, including top-level keys such as {@code type},
 * {@code properties}, and {@code required}. This format matches what {@link at.aimon.core.agent.tool.AbstractTool}
 * constructors and {@link at.aimon.core.llm.ToolDefinition} expect.
 *
 * <h2>Immutability Guarantee</h2>
 * <p>
 * The constructor uses {@link Map#copyOf(Map)} to guarantee immutability of the top-level Map, but does not deep-copy
 * nested Map/List structures (e.g., inside {@code properties}). Therefore, <b>implementations that create
 * McpToolSchema</b> (such as {@link DefaultMcpClient}) must convert MCP server responses into immutable data structures
 * ({@link Map#of()}, {@link java.util.List#of()}, Jackson's {@code ObjectMapper.convertValue()}, etc.).
 *
 * <p>
 * Example:
 *
 * <pre>
 * {@code
 * Map.of(
 *     "type", "object",
 *     "properties", Map.of(
 *         "owner", Map.of("type", "string", "description", "Repository owner"),
 *         "repo", Map.of("type", "string", "description", "Repository name")
 *     ),
 *     "required", List.of("owner", "repo")
 * )
 * }
 * </pre>
 */
public final class McpToolSchema {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final McpToolAnnotations annotations;

    private McpToolSchema(String name, String description, Map<String, Object> inputSchema,
            McpToolAnnotations annotations) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.description = Objects.requireNonNull(description, "description cannot be null");
        this.inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema cannot be null"));
        this.annotations = Objects.requireNonNull(annotations, "annotations cannot be null");
    }

    /**
     * Creates a new McpToolSchema for a server that sent no behavioural annotations.
     *
     * @param name
     *            the tool name as reported by the MCP server
     * @param description
     *            the tool description
     * @param inputSchema
     *            the complete JSON Schema object for tool input
     * @return a new McpToolSchema instance
     */
    public static McpToolSchema of(String name, String description, Map<String, Object> inputSchema) {
        return new McpToolSchema(name, description, inputSchema, McpToolAnnotations.empty());
    }

    /**
     * Creates a new McpToolSchema carrying the server's behavioural annotations.
     *
     * @param name
     *            the tool name as reported by the MCP server
     * @param description
     *            the tool description
     * @param inputSchema
     *            the complete JSON Schema object for tool input
     * @param annotations
     *            the hints the server attached to this tool (must not be null; pass
     *            {@link McpToolAnnotations#empty()} for none)
     * @return a new McpToolSchema instance
     */
    public static McpToolSchema of(String name, String description, Map<String, Object> inputSchema,
            McpToolAnnotations annotations) {
        return new McpToolSchema(name, description, inputSchema, annotations);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Returns the tool's input JSON Schema.
     *
     * <p>
     * The returned Map is the complete JSON Schema object ({@code type}, {@code properties}, {@code required}, etc.).
     * It
     * does not contain only {@code properties}.
     *
     * @return input JSON Schema (immutable)
     */
    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    /**
     * Returns the behavioural hints the server attached to this tool.
     *
     * <p>
     * These are claims, not facts — see {@link McpToolAnnotations}. Whether they become the tool's AIMON declarations
     * depends on {@link McpServerConfig#getAnnotationTrust()}, which is applied when the tool is registered, not here.
     *
     * @return the annotations, {@link McpToolAnnotations#empty()} when the server sent none (never null)
     */
    public McpToolAnnotations getAnnotations() {
        return annotations;
    }

}
