package at.aimon.core.skill.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Base exception for all skill-related errors.
 *
 * <p>
 * This is the root of the skill exception hierarchy. All skill-specific exceptions should extend this class to provide
 * consistent error handling. Extends {@link AimonException} for a unified exception hierarchy.
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
 *     skill = skillRegistry.getSkill("unknown-skill");
 * } catch (SkillException e) {
 *     // Handle any skill-related error
 *     logger.error("Skill error: {}", e.getMessage());
 * }
 * }
 * </pre>
 */
public class SkillException extends AimonException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new skill exception with the specified detail message.
     *
     * @param message
     *            The detail message (must not be null)
     */
    public SkillException(String message) {
        super(message);
    }

    /**
     * Constructs a new skill exception with the specified detail message and cause.
     *
     * @param message
     *            The detail message (must not be null)
     * @param cause
     *            The cause (must not be null)
     */
    public SkillException(String message, Throwable cause) {
        super(message, cause);
    }
}
