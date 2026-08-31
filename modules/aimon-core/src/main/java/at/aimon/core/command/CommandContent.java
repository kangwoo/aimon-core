package at.aimon.core.command;

import java.util.Objects;

/**
 * Immutable representation of parsed command markdown body.
 *
 * <p>
 * Holds the markdown body of a command (excluding frontmatter). Context tokens ({@code !`cmd`}, {@code @file}) are no
 * longer extracted at parse time — the surviving consumer ({@link at.aimon.core.command.skill.SkillBackedCommand})
 * delegates rendering to {@link at.aimon.core.skill.render.SkillContentRenderer}, which scans the body lazily at
 * execution time.
 *
 * <p>
 * Thread-safe value object.
 */
public final class CommandContent {
    /**
     * Creates a new CommandContent.
     *
     * @param rawContent
     *            The raw markdown content (must not be null)
     * @return A new CommandContent instance
     * @throws NullPointerException
     *             if rawContent is null
     */
    public static CommandContent of(String rawContent) {
        return new CommandContent(rawContent);
    }

    private final String rawContent;

    private CommandContent(String rawContent) {
        this.rawContent = Objects.requireNonNull(rawContent, "Content cannot be null");
    }

    /**
     * @return The raw markdown body (never null)
     */
    public String getRawContent() {
        return rawContent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CommandContent that = (CommandContent) o;
        return rawContent.equals(that.rawContent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawContent);
    }

    @Override
    public String toString() {
        return "CommandContent{rawContent='" + rawContent.substring(0, Math.min(50, rawContent.length())) + "...'}";
    }
}
