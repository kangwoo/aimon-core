package at.aimon.core.agent.definition;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A collection of agent definitions loaded from a single source.
 *
 * <p>
 * This class represents a bundle of agent definitions that originate from the same source (e.g., a directory, a file,
 * or a remote location). It provides traceability by tracking the source of the definitions.
 *
 * <p>
 * This class is immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     List<AgentDefinition> definitions = List.of(definition1, definition2);
 *     AgentDefinitionBundle bundle = new AgentDefinitionBundle("/agents/", definitions);
 *
 *     String source = bundle.getSource(); // "/agents/"
 *     List<AgentDefinition> loaded = bundle.getDefinitions();
 * }
 * </pre>
 *
 * @see AgentDefinition
 */
public final class AgentDefinitionBundle {

    private final String source;
    private final List<AgentDefinition> definitions;

    /**
     * Creates a new agent definition bundle.
     *
     * @param source
     *            The source identifier (e.g., directory path, URL) where definitions were loaded from (must not be
     *            null)
     * @param definitions
     *            The list of agent definitions (may be null or empty, will be converted to empty list if null)
     * @throws NullPointerException
     *             if source is null
     */
    public AgentDefinitionBundle(String source, List<AgentDefinition> definitions) {
        this.source = Objects.requireNonNull(source, "AgentDefinitionBundle: source cannot be null");
        this.definitions = definitions != null ? List.copyOf(definitions) : Collections.emptyList();
    }

    /**
     * Returns the source identifier where definitions were loaded from.
     *
     * @return The source identifier (never null)
     */
    public String getSource() {
        return source;
    }

    /**
     * Returns the list of agent definitions in this bundle.
     *
     * @return Immutable list of agent definitions (never null, may be empty)
     */
    public List<AgentDefinition> getDefinitions() {
        return definitions;
    }
}
