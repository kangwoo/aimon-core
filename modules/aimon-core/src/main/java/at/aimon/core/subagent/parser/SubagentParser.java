package at.aimon.core.subagent.parser;

import at.aimon.core.subagent.Subagent;

/**
 * Parser interface for subagent files.
 *
 * <p>
 * Parses raw content from subagent files (agents/*.md) into Subagent objects.
 *
 * <p>
 * Expected file format:
 *
 * <pre>
 * ---
 * name: code-reviewer
 * description: Expert code reviewer
 * when-to-use: When you need code review or quality analysis
 * allowed-tools: Read, Grep, Glob, Bash
 * model: sonnet
 * max-iterations: 50
 * ---
 * You are an expert code reviewer...
 * </pre>
 *
 * <p>
 * Implementations should:
 *
 * <ul>
 * <li>Parse YAML frontmatter for metadata
 * <li>Extract markdown body for content
 * <li>Validate required fields
 * <li>Handle parsing errors gracefully
 * </ul>
 *
 * @see Subagent
 */
public interface SubagentParser {
    /**
     * Parses raw content into a Subagent.
     *
     * @param subagentName
     *            The subagent name (must not be null)
     * @param content
     *            The raw content (must not be null)
     * @return A parsed Subagent object
     * @throws NullPointerException
     *             if any parameter is null
     * @throws SubagentParseException
     *             if parsing fails
     */
    Subagent parse(String subagentName, String content);
}
