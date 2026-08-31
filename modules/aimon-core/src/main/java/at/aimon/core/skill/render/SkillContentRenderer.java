package at.aimon.core.skill.render;

import at.aimon.core.skill.Skill;

/**
 * Renders the prompt body of a {@link Skill} immediately before it is injected into the conversation context.
 *
 * <p>
 * Implementations may perform variable substitution, argument expansion, or other transformations.
 *
 * <p>
 * The default {@link NoOpSkillContentRenderer} returns the body unchanged. Non-trivial implementations are introduced
 * in subsequent work units (see SK-02 and later).
 *
 * <p>
 * Implementations must be thread-safe — a single instance is shared across tool invocations — and should complete
 * synchronously without performing blocking I/O.
 */
public interface SkillContentRenderer {

    /**
     * Renders the skill's instructions for injection.
     *
     * @param skill
     *            the skill being activated (must not be null)
     * @param args
     *            the raw argument string from the tool call; the empty string when no args were provided (must not be
     *            null)
     * @param context
     *            the render context (must not be null)
     * @return the rendered instructions (never null)
     * @throws NullPointerException
     *             if any argument is null
     */
    String render(Skill skill, String args, RenderContext context);
}
