package at.aimon.core.skill;

import java.util.Objects;

/**
 * Immutable content of an Agent Skill.
 *
 * <p>
 * Contains the skill instructions that will be injected into the agent's context when the skill is activated.
 *
 * <p>
 * The instructions are the Markdown body content after the frontmatter in the SKILL.md file.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillContent content = SkillContent.of("# Alert Analysis\n\n" + "## Instructions\n\n"
 *             + "1. Collect alert information...\n" + "2. Query relevant metrics...\n");
 *
 *     String instructions = content.getInstructions();
 * }
 * </pre>
 */
public final class SkillContent {

    /**
     * Creates a new SkillContent with the specified instructions.
     *
     * @param instructions
     *            The skill instructions (must not be null or blank)
     * @return A new SkillContent instance (never null)
     * @throws NullPointerException
     *             if instructions is null
     * @throws IllegalArgumentException
     *             if instructions is blank
     */
    public static SkillContent of(String instructions) {
        return new SkillContent(instructions);
    }

    private final String instructions;

    private SkillContent(String instructions) {
        this.instructions = Objects.requireNonNull(instructions, "Instructions cannot be null");
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("Instructions cannot be blank");
        }
    }

    /**
     * Gets the skill instructions.
     *
     * @return The skill instructions (never null)
     */
    public String getInstructions() {
        return instructions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SkillContent that = (SkillContent) o;
        return Objects.equals(instructions, that.instructions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instructions);
    }

    @Override
    public String toString() {
        return "SkillContent{" + "instructions='"
                + (instructions.length() > 100 ? instructions.substring(0, 100) + "..." : instructions) + '\'' + '}';
    }
}
