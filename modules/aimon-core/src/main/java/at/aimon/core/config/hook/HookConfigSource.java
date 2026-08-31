package at.aimon.core.config.hook;

/**
 * Provenance of a {@link HookConfigDocument}.
 *
 * <p>
 * Mirrors the Claude Code 4-tier layering. The relative precedence enforced by the layered config loader is
 * {@link #LOCAL} &gt; {@link #PROJECT} &gt; {@link #USER}; {@link #SKILL} entries are scope-isolated to the owning
 * skill's lifetime and merge into the same dispatch table without participating in the precedence chain.
 *
 * <p>
 * Each source carries an integer {@link #precedence()} where a higher value means &quot;wins on conflict&quot;.
 */
public enum HookConfigSource {

    /** {@code ~/.aimon/hooks.json} — user-level defaults. Lowest precedence. */
    USER(10),

    /** {@code <project>/.aimon/hooks.json} — checked into the repo, overrides USER. */
    PROJECT(20),

    /** {@code <project>/.aimon/hooks.local.json} — gitignored personal overrides, highest precedence. */
    LOCAL(30),

    /** Skill-supplied frontmatter; lifetime is bound to the owning skill, scope-isolated from the layered chain. */
    SKILL(0);

    private final int precedence;

    HookConfigSource(int precedence) {
        this.precedence = precedence;
    }

    /**
     * Returns the precedence weight; higher wins.
     *
     * @return the precedence weight (never negative for layered sources; SKILL uses 0 because it is scope-isolated)
     */
    public int precedence() {
        return precedence;
    }
}
