package at.aimon.core.skill;

import java.util.Objects;

/**
 * Immutable policy describing who is allowed to invoke a Skill.
 *
 * <p>
 * Each Agent Skill may declare an {@code invoke} block in its SKILL.md frontmatter:
 *
 * <pre>
 * invoke:
 *   user:  true   # invocable via slash-command (e.g. /commit args...)
 *   model: true   # exposed to the LLM as a callable tool entry
 * </pre>
 *
 * <p>
 * When the {@code invoke} block is missing or partial, defaults are filled in:
 *
 * <ul>
 * <li>{@code user = false} — preserves the current AIMON behavior where skills are not directly user-invocable.
 * <li>{@code model = true} — preserves the current AIMON behavior where skills are visible to the LLM via
 * {@code SkillTool}.
 * </ul>
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class InvokePolicy {

    private static final InvokePolicy DEFAULT = new InvokePolicy(false, true);

    /**
     * Returns the default policy: {@code user = false}, {@code model = true}.
     *
     * @return The shared default policy instance (never null)
     */
    public static InvokePolicy defaults() {
        return DEFAULT;
    }

    /**
     * Creates a policy with the given flags.
     *
     * @param user
     *            Whether the skill is invocable by a human user (e.g. CLI slash command)
     * @param model
     *            Whether the skill is exposed to the LLM via {@code SkillTool}
     * @return A new {@code InvokePolicy} instance (never null)
     */
    public static InvokePolicy of(boolean user, boolean model) {
        if (!user && model) {
            return DEFAULT;
        }
        return new InvokePolicy(user, model);
    }

    private final boolean user;
    private final boolean model;

    private InvokePolicy(boolean user, boolean model) {
        this.user = user;
        this.model = model;
    }

    /**
     * @return {@code true} if a human user may invoke this skill (e.g. via a CLI slash command)
     */
    public boolean isUserInvocable() {
        return user;
    }

    /**
     * @return {@code true} if this skill is exposed to the LLM via {@code SkillTool}
     */
    public boolean isModelInvocable() {
        return model;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final InvokePolicy that = (InvokePolicy) o;
        return user == that.user && model == that.model;
    }

    @Override
    public int hashCode() {
        return Objects.hash(user, model);
    }

    @Override
    public String toString() {
        return "InvokePolicy{user=" + user + ", model=" + model + '}';
    }
}
