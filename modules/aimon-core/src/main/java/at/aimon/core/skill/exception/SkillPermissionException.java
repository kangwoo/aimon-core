package at.aimon.core.skill.exception;

/**
 * Exception thrown when a skill attempts to use a tools it's not permitted to use.
 *
 * <p>
 * This exception is thrown by SkillPermissionManager when validating tools usage against the skill's allowed-tools
 * restrictions.
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
 *     permissionManager.validateToolUsage(skill, "Bash", "rm -rf /");
 * } catch (SkillPermissionException e) {
 *     logger.error("Permission denied: {}", e.getMessage());
 * }
 * }
 * </pre>
 */
public class SkillPermissionException extends SkillException {
    private static final long serialVersionUID = 1L;

    private final String skillName;
    private final String toolName;

    /**
     * Constructs a new skill permission exception.
     *
     * @param message
     *            The detail message (must not be null)
     */
    public SkillPermissionException(String message) {
        super(message);
        skillName = null;
        toolName = null;
    }

    /**
     * Constructs a new skill permission exception with skill and tools information.
     *
     * @param skillName
     *            The name of the skill (must not be null)
     * @param toolName
     *            The name of the tools (must not be null)
     * @param message
     *            The detail message (must not be null)
     */
    public SkillPermissionException(String skillName, String toolName, String message) {
        super(String.format("Skill '%s' permission denied for tools '%s': %s", skillName, toolName, message));
        this.skillName = skillName;
        this.toolName = toolName;
    }

    /**
     * Gets the name of the skill that was denied permission.
     *
     * @return The skill name, or null if not available
     */
    public String getSkillName() {
        return skillName;
    }

    /**
     * Gets the name of the tools that was denied.
     *
     * @return The tools name, or null if not available
     */
    public String getToolName() {
        return toolName;
    }
}
