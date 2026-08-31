package at.aimon.core.skill.parser;

import at.aimon.core.skill.Skill;
import at.aimon.core.skill.exception.SkillParseException;

/**
 * Parser interface for converting SKILL.md content into Skill objects.
 *
 * <p>
 * Implementations should parse the Agent Skills standard format with YAML frontmatter and Markdown body.
 *
 * <p>
 * Thread-safety depends on implementation.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillParser parser = new MarkdownSkillParser();
 *
 *     String content = "---\nname: my-skill\ndescription: ...\n---\n# My Skill\n...";
 *     Skill skill = parser.parse("my-skill", content);
 * }
 * </pre>
 */
public interface SkillParser {

    /**
     * Parses SKILL.md content into a Skill object.
     *
     * <p>
     * The content should follow the Agent Skills standard format with YAML frontmatter and Markdown body.
     *
     * @param skillName
     *            The skill name (must not be null)
     * @param content
     *            The SKILL.md file content (must not be null)
     * @return Parsed Skill object (never null)
     * @throws SkillParseException
     *             if parsing fails
     */
    Skill parse(String skillName, String content);
}
