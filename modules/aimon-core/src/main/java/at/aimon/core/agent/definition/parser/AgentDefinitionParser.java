package at.aimon.core.agent.definition.parser;

import java.io.InputStream;

import at.aimon.core.agent.definition.AgentDefinition;
import at.aimon.core.agent.definition.exception.AgentDefinitionParseException;

/**
 * Interface for parsing agent definitions from various file formats.
 *
 * <p>
 * Implementations can support different file formats such as:
 * <ul>
 * <li>Markdown with YAML frontmatter ({@link MarkdownAgentDefinitionParser})</li>
 * <li>Pure YAML (custom implementations)</li>
 * <li>JSON (custom implementations)</li>
 * </ul>
 *
 * <p>
 * Implementations should be thread-safe and reusable.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentDefinitionParser parser = new MarkdownAgentDefinitionParser();
 *     try (InputStream input = Files.newInputStream(path)) {
 *         AgentDefinition definition = parser.parse(input);
 *     }
 * }
 * </pre>
 *
 * @see AgentDefinition
 * @see MarkdownAgentDefinitionParser
 */
public interface AgentDefinitionParser {

    /**
     * Parses an agent definition from the input stream.
     *
     * <p>
     * The input stream is not closed by this method. Callers are responsible for closing the stream.
     *
     * @param input
     *            The input stream to parse (must not be null)
     * @return The parsed agent definition (never null)
     * @throws AgentDefinitionParseException
     *             if parsing fails due to invalid format or content
     * @throws NullPointerException
     *             if input is null
     */
    AgentDefinition parse(InputStream input);
}
