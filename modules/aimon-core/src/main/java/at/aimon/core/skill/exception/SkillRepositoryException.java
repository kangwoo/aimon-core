package at.aimon.core.skill.exception;

/**
 * Exception thrown when skill repository operations fail.
 *
 * <p>
 * This exception is thrown by SkillRepository implementations when they encounter errors accessing the underlying
 * storage (filesystem, database, etc.).
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
 *     Optional<String> content = repository.findByName("my-skill");
 * } catch (SkillRepositoryException e) {
 *     logger.error("Repository error: {}", e.getMessage());
 * }
 * }
 * </pre>
 */
public class SkillRepositoryException extends SkillException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new skill repository exception.
     *
     * @param message
     *            The detail message (must not be null)
     */
    public SkillRepositoryException(String message) {
        super(message);
    }

    /**
     * Constructs a new skill repository exception with a cause.
     *
     * @param message
     *            The detail message (must not be null)
     * @param cause
     *            The cause (must not be null)
     */
    public SkillRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
