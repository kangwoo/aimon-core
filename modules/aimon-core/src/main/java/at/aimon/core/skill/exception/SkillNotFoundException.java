package at.aimon.core.skill.exception;

/**
 * Exception thrown when a requested skill cannot be found.
 *
 * <p>
 * This exception is thrown by SkillRegistry and SkillRepository when a skill lookup fails.
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
 *     Optional<Skill> skill = skillRegistry.getSkill("nonexistent-skill");
 *     if (skill.isEmpty()) {
 *         throw new SkillNotFoundException("nonexistent-skill");
 *     }
 * }
 * </pre>
 */
public class SkillNotFoundException extends SkillException {
    private static final long serialVersionUID = 1L;

    private final String skillName;

    /**
     * Constructs a new skill not found exception.
     *
     * @param skillName
     *            The name of the skill that was not found (must not be null)
     */
    public SkillNotFoundException(String skillName) {
        super(String.format("Skill not found: %s", skillName));
        this.skillName = skillName;
    }

    /**
     * Gets the name of the skill that was not found.
     *
     * @return The skill name (never null)
     */
    public String getSkillName() {
        return skillName;
    }
}
