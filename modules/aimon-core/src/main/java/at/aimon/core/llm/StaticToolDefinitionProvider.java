package at.aimon.core.llm;

import java.util.Objects;

/**
 * Provides a static, immutable tool definition.
 *
 * <p>
 * This provider always returns the same {@link ToolDefinition} that was provided during construction. The definition is
 * immutable and thread-safe.
 *
 * <p>
 * Use this provider for tools whose definitions never change at runtime, such as basic file operations, bash commands,
 * or other fixed-functionality tools.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     Map&lt;String, Object&gt; schema = Map.of("type", "object", "properties",
 *             Map.of("command", Map.of("type", "string", "description", "The bash command to execute")), "required",
 *             List.of("command"));
 *
 *     ToolDefinition definition = ToolDefinition.of("bash", "Execute a bash command", schema);
 *
 *     ToolDefinitionProvider provider = new StaticToolDefinitionProvider(definition);
 *
 *     // Always returns the same definition
 *     ToolDefinition def1 = provider.getDefinition();
 *     ToolDefinition def2 = provider.getDefinition();
 *     assert def1 == def2; // Same object reference
 * }
 * </pre>
 *
 * @see ToolDefinitionProvider
 * @see ToolDefinition
 */
public final class StaticToolDefinitionProvider implements ToolDefinitionProvider {
    private final ToolDefinition definition;

    /**
     * Creates a new StaticToolDefinitionProvider.
     *
     * @param definition
     *            The tool definition (must not be null)
     * @throws NullPointerException
     *             if definition is null
     */
    public StaticToolDefinitionProvider(ToolDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "Tool definition cannot be null");
    }

    /**
     * Gets the static tool definition.
     *
     * <p>
     * This method always returns the same definition object that was provided during construction.
     *
     * @return The tool definition (never null, always the same instance)
     */
    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final StaticToolDefinitionProvider that = (StaticToolDefinitionProvider) o;
        return definition.equals(that.definition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(definition);
    }

    @Override
    public String toString() {
        return "StaticToolDefinitionProvider{definition=" + definition + '}';
    }
}
