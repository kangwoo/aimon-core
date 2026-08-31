package at.aimon.core.skill.hook.declarative;

import java.util.Objects;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Builds the stable identifiers returned by {@link ExecutionHook#getHookId()} for declarative hooks.
 *
 * <p>
 * The default {@code getHookId()} implementation returns the class name, which is <b>not</b> unique when several hooks
 * of the same class are registered — the common case for {@code hooks.json} and multi-entry skill frontmatter. Two
 * subsystems key off this id and misbehave when it collides:
 * <ul>
 * <li>{@code DefaultRewakeFireListener} routes a fired rewake envelope back to its originating hook by matching the id
 * against the registry. On a collision it dispatches to whichever same-class hook happens to be registered first.
 * <li>{@code HookRegistryReloader} diffs the id sets across a config reload to decide which pending rewakes to cancel.
 * On a collision it both under-cancels (dropping one of N same-class hooks leaves the id set unchanged) and
 * over-cancels (dropping all of them also kills envelopes owned by still-live skill hooks of that class).
 * </ul>
 *
 * <p>
 * <b>Ids must be content-derived, not identity-derived.</b> Declarative hooks are torn down and rebuilt on every config
 * reload, so an identity-based id (e.g. {@code System.identityHashCode}) would change on each reload and cause the
 * reloader to cancel every pending rewake — technically safe, but it defeats the feature. Callers therefore pass a
 * discriminator derived from the hook's position in its defining document (config source + event + entry/handler index,
 * or the skill frontmatter path), which is stable as long as that document is unchanged.
 *
 * <p>
 * Utility class; not instantiable.
 */
public final class DeclarativeHookId {

    private DeclarativeHookId() {
    }

    /**
     * Builds an id for a hook, falling back to the skill-scoped default when no discriminator is available.
     *
     * @param hookClass
     *            the concrete hook class (must not be null)
     * @param skillName
     *            the owning skill (or synthetic config-source) name (must not be null)
     * @param discriminator
     *            a value that distinguishes this hook from its same-class siblings, stable across reloads — e.g.
     *            {@code "preTool[0][1]"}. {@code null} or blank yields {@link #defaultId(Class, String)}.
     * @return a non-blank hook id (never null)
     */
    public static String of(Class<?> hookClass, String skillName, String discriminator) {
        if (discriminator == null || discriminator.isBlank()) {
            return defaultId(hookClass, skillName);
        }
        return defaultId(hookClass, skillName) + "#" + discriminator;
    }

    /**
     * Builds the skill-scoped id used when a caller supplies no discriminator.
     *
     * <p>
     * This is unique as long as a given skill registers at most one hook of each class — true for hand-written
     * {@code ExecutionHook} implementations, but not guaranteed for multi-entry declarative config. Prefer
     * {@link #of(Class, String, String)}.
     *
     * @param hookClass
     *            the concrete hook class (must not be null)
     * @param skillName
     *            the owning skill (or synthetic config-source) name (must not be null)
     * @return a non-blank hook id (never null)
     */
    public static String defaultId(Class<?> hookClass, String skillName) {
        Objects.requireNonNull(hookClass, "hookClass cannot be null");
        Objects.requireNonNull(skillName, "skillName cannot be null");
        return hookClass.getName() + "@" + skillName;
    }
}
