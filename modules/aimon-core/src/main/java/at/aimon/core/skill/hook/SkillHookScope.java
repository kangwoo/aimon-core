package at.aimon.core.skill.hook;

import java.util.Optional;

import at.aimon.core.hook.HookRegistry;

/**
 * Closeable scope returned by {@link SkillHookActivator#activate}; closing the scope ends every hook the activator
 * made available for the active skill.
 *
 * <p>
 * Implementations must be idempotent — calling {@link #close()} more than once must be a no-op.
 *
 * <p>
 * Designed for use in a try-with-resources block:
 *
 * <pre>{@code
 * try (SkillHookScope scope = activator.activate(skill, context)) {
 *     // the skill's fork runs here, dispatching against scope.hookRegistry()
 * }
 * }</pre>
 */
public interface SkillHookScope extends AutoCloseable {

    /** A no-op scope; useful as the return value when there is nothing to activate. */
    SkillHookScope EMPTY = () -> {
    };

    /**
     * Returns the registry the skill's fork should dispatch against — the invoking execution's registry with the
     * skill's hooks on top.
     *
     * @return the registry, or empty when the scope layered nothing and the fork uses its caller's registry as is
     */
    default Optional<HookRegistry> hookRegistry() {
        return Optional.empty();
    }

    @Override
    void close();
}
