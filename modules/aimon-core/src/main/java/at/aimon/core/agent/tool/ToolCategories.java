package at.aimon.core.agent.tool;

import at.aimon.core.llm.ToolDefinition;

/**
 * Standard tool category constants used for grouping tools in listings.
 *
 * <p>
 * Categories are human-facing metadata. They are not exposed to the LLM as part of the function-calling schema. The
 * default category is defined by {@link ToolDefinition#DEFAULT_CATEGORY}.
 *
 * <p>
 * Standard categories:
 *
 * <ul>
 * <li>{@link #FILESYSTEM} — file and artifact I/O (Read, Write, Edit, Grep, ...)
 * <li>{@link #EXECUTION} — command, agent, scheduling, and skill execution (Bash, Task, Schedule*, Skill, ...)
 * <li>{@link #SEARCH} — external/internal information retrieval (Web*, Knowledge*, Wiki*, ToolSearch, ...)
 * <li>{@link #WORKFLOW} — user-facing workflow helpers (TodoWrite, ConsoleOutput, ...)
 * <li>{@link #GENERAL} — default for uncategorized tools (alias of {@link ToolDefinition#DEFAULT_CATEGORY})
 * </ul>
 *
 * <p>
 * Third-party tools may use these constants or define their own free-form category strings.
 */
public final class ToolCategories {
    /** File system and artifact I/O. */
    public static final String FILESYSTEM = "filesystem";

    /** Command, agent, scheduling, and skill execution. */
    public static final String EXECUTION = "execution";

    /** External/internal information retrieval. */
    public static final String SEARCH = "search";

    /** User-facing workflow helpers. */
    public static final String WORKFLOW = "workflow";

    /** Default category for uncategorized tools. */
    public static final String GENERAL = ToolDefinition.DEFAULT_CATEGORY;

    private ToolCategories() {
    }
}
