package at.aimon.core.command;

/**
 * Enum representing the type of a command.
 *
 * <p>
 * Commands can be either:
 *
 * <ul>
 * <li>{@link #SYSTEM} - Built-in commands compiled into the application (help, version, clear)
 * <li>{@link #CUSTOM} - User-authored commands sourced from a {@code Skill} via {@code SkillBackedCommand}
 * </ul>
 *
 * <p>
 * System commands take precedence over custom commands in case of name conflicts.
 *
 * <p>
 * <b>SK-08-F:</b> the {@code .aimon/commands/*.md} file-based loader was removed in 0.1.0. The {@code CUSTOM} value now
 * represents skill-backed commands exclusively; the enum survives because callers (REPL routing, {@code /commands}
 * listing, help text) still need to distinguish built-in from user-authored commands at the API surface.
 *
 * <p>
 * <b>Design Note:</b> This is implemented as an enum rather than a Value Object because:
 * <ul>
 * <li>The command type dichotomy (SYSTEM vs CUSTOM) is fundamental and stable
 * <li>New command types would require architectural changes beyond just adding a new type
 * <li>The simple binary nature doesn't warrant the complexity of a Value Object pattern
 * </ul>
 */
public enum CommandType {
    /**
     * Built-in system commands implemented as Java classes.
     *
     * <p>
     * System commands:
     *
     * <ul>
     * <li>Are compiled into the application
     * <li>Cannot be overridden by custom commands
     * <li>Have no file I/O overhead
     * <li>Are always available
     * </ul>
     *
     * <p>
     * Examples: help, version, clear
     */
    SYSTEM,

    /**
     * User-authored commands sourced from a {@code Skill}.
     *
     * <p>
     * Custom commands:
     *
     * <ul>
     * <li>Are adapted from a {@code Skill} via {@code SkillBackedCommand} (see {@code SkillBackedCommandRegistry})
     * <li>Are user-invocable when the underlying Skill's {@code invoke.user} policy is {@code true}
     * <li>Cannot override system commands (conflicts fail-fast at startup)
     * <li>Reload through the {@code SkillRegistry}, not through this registry's {@code reloadCommand} entry point
     * </ul>
     *
     * <p>
     * Examples: commit, deploy, review
     */
    CUSTOM
}
