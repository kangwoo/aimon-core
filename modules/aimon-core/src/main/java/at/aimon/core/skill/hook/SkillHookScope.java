package at.aimon.core.skill.hook;

/**
 * Closeable scope returned by {@link SkillHookActivator#activate}; closing the scope unregisters every hook the
 * activator registered for the active skill.
 *
 * <p>
 * Implementations must be idempotent — calling {@link #close()} more than once must be a no-op.
 *
 * <p>
 * Designed for use in a try-with-resources block:
 *
 * <pre>{@code
 * try (SkillHookScope scope = activator.activate(skill)) {
 *     // skill body executes here with hooks active
 * }
 * }</pre>
 */
public interface SkillHookScope extends AutoCloseable {

    /** A no-op scope; useful as the return value when there is nothing to register. */
    SkillHookScope EMPTY = () -> {
    };

    @Override
    void close();
}
