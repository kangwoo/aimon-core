package at.aimon.core.llm;

import java.util.Map;
import java.util.Objects;

import at.aimon.core.base.NullSafeMaps;

/**
 * Represents an LLM's decision to use a tool.
 *
 * <p>
 * Contains the tool name, a unique ID for this invocation, and the input parameters provided by the LLM.
 *
 * <p>
 * <b>Input parameters whose value is JSON {@code null} are dropped</b> — see {@link NullSafeMaps}. This type is built
 * while converting an LLM response, which happens before the tool-execution loop and therefore outside its
 * {@code catch}: a value the model supplied as {@code null} must not be able to fail the turn here, because nothing
 * downstream would ever get the chance to report it back to the model.
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
 *     ToolUse toolUse = ToolUse.of("tool_call_123", "bash", Map.of("command", "git status"));
 *
 *     String command = (String) toolUse.getInput().get("command");
 *     // Execute the tool with the provided input
 * }
 * </pre>
 */
public final class ToolUse {
    /**
     * Creates a new ToolUse.
     *
     * @param id
     *            The unique ID for this tool use (must not be null)
     * @param name
     *            The tool name (must not be null)
     * @param input
     *            The tool input parameters (the map must not be null; individual values may be, and are dropped)
     * @return A new ToolUse
     * @throws NullPointerException
     *             if any parameter is null
     */
    public static ToolUse of(String id, String name, Map<String, Object> input) {
        return new ToolUse(id, name, input);
    }

    private final String id;
    private final String name;
    private final Map<String, Object> input;

    /**
     * Creates a new ToolUse.
     *
     * @param id
     *            The unique ID for this tool use (must not be null)
     * @param name
     *            The tool name (must not be null)
     * @param input
     *            The tool input parameters (the map must not be null; individual values may be, and are dropped)
     * @throws NullPointerException
     *             if any parameter is null
     */
    private ToolUse(String id, String name, Map<String, Object> input) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.input = NullSafeMaps.withoutNullValues(Objects.requireNonNull(input, "Input cannot be null"));
    }

    /**
     * Gets the tool use ID.
     *
     * @return The ID (never null)
     */
    public String getId() {
        return id;
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
     * Gets the tool input parameters.
     *
     * @return An immutable map of parameters (never null)
     */
    public Map<String, Object> getInput() {
        return input;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ToolUse toolUse = (ToolUse) o;
        return id.equals(toolUse.id) && name.equals(toolUse.name) && input.equals(toolUse.input);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, input);
    }

    @Override
    public String toString() {
        return "ToolUse{id='" + id + "', name='" + name + "', input=" + input + '}';
    }
}
