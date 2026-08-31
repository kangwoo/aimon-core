package at.aimon.core.skill.execution.llm;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import at.aimon.core.agent.tool.permission.AllowedTool;

/**
 * Builds system prompts for user-invoked skill execution.
 *
 * <p>
 * Mirrors the (deprecating) {@code at.aimon.core.command.execution.llm.SystemPromptBuilder}: a base prompt
 * describing the assistant role, followed by a tools-restriction section listing the allowed tools. Kept in the skill
 * package so the skill execution path does not depend on {@code at.aimon.core.command.*}. Introduced in SK-08-C.
 *
 * <p>
 * Thread-safe and stateless.
 */
public class SkillSystemPromptBuilder {

    private static final String BASE_SYSTEM_PROMPT = """
            You are a helpful AI assistant executing a user-defined command.

            """;

    private static final String NO_RESTRICTIONS = """
            Tool Usage: You have access to all available tools without restrictions.
            """;

    private static final String WITH_RESTRICTIONS = """
            Tool Usage Restrictions:
            You may ONLY use the following tools:
            %s

            Any attempt to use other tools will be rejected. Stay within these boundaries.
            """;

    /** Creates a new builder. */
    public SkillSystemPromptBuilder() {
    }

    /**
     * Builds a system prompt with the given tools restrictions.
     *
     * @param allowedTools
     *            Allowed tools (must not be null; empty means no restrictions)
     * @return The complete system prompt (never null)
     * @throws NullPointerException
     *             if {@code allowedTools} is null
     */
    public String build(List<AllowedTool> allowedTools) {
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");

        if (allowedTools.isEmpty()) {
            return BASE_SYSTEM_PROMPT + NO_RESTRICTIONS;
        }

        final String toolsList = formatAllowedTools(allowedTools);
        final String restrictionsSection = String.format(WITH_RESTRICTIONS, toolsList);
        return BASE_SYSTEM_PROMPT + restrictionsSection;
    }

    private String formatAllowedTools(List<AllowedTool> allowedTools) {
        return allowedTools.stream().map(this::formatAllowedTool).collect(Collectors.joining("\n"));
    }

    private String formatAllowedTool(AllowedTool tool) {
        if (tool.getPattern().isPresent()) {
            final String pattern = tool.getPattern().get().getPattern();
            return String.format("- %s (commands matching: %s)", tool.getToolName(), pattern);
        }
        return String.format("- %s (no restrictions)", tool.getToolName());
    }
}
