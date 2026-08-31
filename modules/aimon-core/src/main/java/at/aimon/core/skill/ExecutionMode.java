package at.aimon.core.skill;

import java.util.Locale;

/**
 * How a Skill body executes when invoked (AIMON extension).
 *
 * <p>
 * Declared on a Skill via the {@code execution.mode} frontmatter key. Default is {@link #INLINE} when the key is
 * absent.
 *
 * @see SkillMetadata#getExecutionMode()
 */
public enum ExecutionMode {

    /**
     * Run the skill body in the calling agent's context.
     *
     * <p>
     * Tool calls and intermediate ReAct turns are visible in the parent conversation. This is the default mode and
     * matches the behavior that existed before SK-09.
     */
    INLINE,

    /**
     * Run the skill body inside a forked SubAgent.
     *
     * <p>
     * The SubAgent is named by {@link SkillMetadata#getForkAgentName()}. Only the final response text is returned to
     * the parent; intermediate tool calls remain in the SubAgent's isolated context.
     */
    FORK;

    /**
     * Parses a frontmatter mode token into an {@code ExecutionMode}.
     *
     * <p>
     * Lookup is case-insensitive (so {@code "inline"} and {@code "INLINE"} both resolve to {@link #INLINE}).
     *
     * @param raw
     *            The raw token from frontmatter; must not be null or blank
     * @return The matching mode (never null)
     * @throws IllegalArgumentException
     *             if {@code raw} is blank or does not match a known mode
     * @throws NullPointerException
     *             if {@code raw} is null
     */
    public static ExecutionMode parse(String raw) {
        if (raw == null) {
            throw new NullPointerException("Execution mode cannot be null");
        }
        final String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Execution mode cannot be blank");
        }
        for (ExecutionMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown execution mode: '" + raw + "'. Expected one of: inline, fork");
    }
}
