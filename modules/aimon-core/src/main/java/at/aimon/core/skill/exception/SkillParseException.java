package at.aimon.core.skill.exception;

/**
 * Exception thrown when skill parsing fails.
 *
 * <p>
 * This exception is thrown by SkillParser implementations when they cannot parse a SKILL.md file due to invalid format,
 * missing required fields, or other parsing errors.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * try {
 *     Skill skill = parser.parse(skillName, content);
 * } catch (SkillParseException e) {
 *     logger.error("Failed to parse skill {}: {}", skillName, e.getMessage());
 * }
 * }
 * </pre>
 */
public class SkillParseException extends SkillException {
    private static final long serialVersionUID = 1L;

    private final String skillName;

    /**
     * Constructs a new skill parse exception.
     *
     * @param skillName
     *            The name of the skill that failed to parse (must not be null)
     * @param message
     *            The detail message (must not be null)
     */
    public SkillParseException(String skillName, String message) {
        super(String.format("Failed to parse skill '%s': %s", skillName, message));
        this.skillName = skillName;
    }

    /**
     * Constructs a new skill parse exception with a cause.
     *
     * @param skillName
     *            The name of the skill that failed to parse (must not be null)
     * @param message
     *            The detail message (must not be null)
     * @param cause
     *            The cause (must not be null)
     */
    public SkillParseException(String skillName, String message, Throwable cause) {
        super(String.format("Failed to parse skill '%s': %s", skillName, message), cause);
        this.skillName = skillName;
    }

    /**
     * Gets the name of the skill that failed to parse.
     *
     * @return The skill name (never null)
     */
    public String getSkillName() {
        return skillName;
    }
}
