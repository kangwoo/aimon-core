package at.aimon.core.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Defines a tool that can be used by the LLM.
 *
 * <p>
 * Tool definitions follow the standard JSON Schema format for function calling. Each tool has a name, description,
 * category, and input schema that defines its parameters.
 *
 * <p>
 * The {@code category} is metadata used for grouping tools when displayed to users (e.g., in CLI listings). It is NOT
 * exposed to the LLM as part of the function-calling schema.
 *
 * <p>
 * The top-level entries of the input schema keep the iteration order of the map they were given, and a provider
 * converter writes them in that order. The tool list leads every request, so an order that changed from one JVM to the
 * next would change the prompt-cache prefix with it. Pass a {@link LinkedHashMap} (or another ordered map) to fix the
 * order; {@code Map.of}, as in the example below, has none to keep. {@link #equals(Object)} compares entries and
 * ignores their order. Nested maps are held as given and are not copied.
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     Map&lt;String, Object&gt; inputSchema = Map.of("type", "object", "properties",
 *             Map.of("command", Map.of("type", "string", "description", "The bash command to execute")), "required",
 *             List.of("command"));
 *
 *     ToolDefinition bashTool = ToolDefinition.of("bash", "Execute a bash command", "execution", inputSchema);
 * }
 * </pre>
 */
public final class ToolDefinition {
    /**
     * Default category used when no category is specified.
     */
    public static final String DEFAULT_CATEGORY = "general";

    /**
     * Creates a new ToolDefinition with the default category.
     *
     * @param name
     *            The tool name (must not be null)
     * @param description
     *            The tool description (must not be null)
     * @param inputSchema
     *            The input schema in JSON Schema format (must not be null)
     * @return A new ToolDefinition with category set to {@link #DEFAULT_CATEGORY}
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static ToolDefinition of(String name, String description, Map<String, Object> inputSchema) {
        return new ToolDefinition(name, description, DEFAULT_CATEGORY, inputSchema);
    }

    /**
     * Creates a new ToolDefinition.
     *
     * @param name
     *            The tool name (must not be null)
     * @param description
     *            The tool description (must not be null)
     * @param category
     *            The tool category (must not be null)
     * @param inputSchema
     *            The input schema in JSON Schema format (must not be null)
     * @return A new ToolDefinition
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static ToolDefinition of(String name, String description, String category, Map<String, Object> inputSchema) {
        return new ToolDefinition(name, description, category, inputSchema);
    }

    private final String name;
    private final String description;
    private final String category;
    private final Map<String, Object> inputSchema;

    private ToolDefinition(String name, String description, String category, Map<String, Object> inputSchema) {
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.description = Objects.requireNonNull(description, "Description cannot be null");
        this.category = Objects.requireNonNull(category, "Category cannot be null");
        this.inputSchema = orderedCopy(Objects.requireNonNull(inputSchema, "Input schema cannot be null"));
    }

    /**
     * Copies the schema keeping its iteration order — {@code Map.copyOf} would not: its order is salted per JVM. Null
     * keys and values are rejected, as {@code Map.copyOf} rejects them.
     */
    private static Map<String, Object> orderedCopy(Map<String, Object> inputSchema) {
        final Map<String, Object> copy = new LinkedHashMap<>(inputSchema.size() * 2);
        for (Map.Entry<String, Object> entry : inputSchema.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "Input schema key cannot be null"), Objects
                    .requireNonNull(entry.getValue(), () -> "Input schema value cannot be null: " + entry.getKey()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Gets the tool name.
     *
     * @return The name (never null)
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the tool description.
     *
     * @return The description (never null)
     */
    public String getDescription() {
        return description;
    }

    /**
     * Gets the tool category.
     *
     * <p>
     * The category is human-facing metadata used for grouping tools in listings. It is not exposed to the LLM.
     *
     * @return The category (never null, defaults to {@link #DEFAULT_CATEGORY})
     */
    public String getCategory() {
        return category;
    }

    /**
     * Gets the input schema.
     *
     * @return An immutable map containing the JSON Schema, in the order it was given (never null)
     */
    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ToolDefinition that = (ToolDefinition) o;
        return name.equals(that.name) && description.equals(that.description) && category.equals(that.category)
                && inputSchema.equals(that.inputSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, category, inputSchema);
    }

    @Override
    public String toString() {
        return "ToolDefinition{name='" + name + "', description='" + description + "', category='" + category + "'}";
    }
}
