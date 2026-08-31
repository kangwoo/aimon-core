package at.aimon.core.skill.permission;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.exception.SkillPermissionException;

/**
 * Permission manager for skill tools access control.
 *
 * <p>
 * Enforces tools restrictions defined in skill metadata. Prevents skills from using unauthorized tools.
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
 *     SkillPermissionManager manager = new SkillPermissionManager();
 *
 *     // Get allowed tools for a skill
 *     List<ToolDefinition> allowed = manager.filterAllowedTools(skill, availableTools);
 *
 *     // Check if tools is allowed
 *     boolean canUse = manager.isToolAllowed(skill, "Read");
 *
 *     // Validate tools usage
 *     manager.validateToolUsage(skill, "Bash", "python scripts/analyze.py");
 * }
 * </pre>
 */
public class SkillPermissionManager {

    /**
     * Filters tools to only those allowed by the skill.
     *
     * <p>
     * If the skill has no tools restrictions, returns all available tools. Otherwise, returns only tools specified in
     * the skill's allowed-tools field.
     *
     * @param skill
     *            The skill (must not be null)
     * @param availableTools
     *            All available tools (must not be null)
     * @return List of allowed tools (never null, may be empty)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public List<ToolDefinition> filterAllowedTools(Skill skill, List<ToolDefinition> availableTools) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(availableTools, "Available tools cannot be null");

        if (!skill.hasToolRestrictions()) {
            return availableTools;
        }

        final Set<String> allowedToolNames = skill.getMetadata().getAllowedTools().stream()
                .map(AllowedTool::getToolName).collect(Collectors.toSet());

        return availableTools.stream().filter(tool -> allowedToolNames.contains(tool.getName())).toList();
    }

    /**
     * Checks if a tools is allowed for a skill.
     *
     * <p>
     * If the skill has no tools restrictions, returns true for all tools.
     *
     * @param skill
     *            The skill (must not be null)
     * @param toolName
     *            The tools name (must not be null)
     * @return true if allowed, false otherwise
     * @throws NullPointerException
     *             if any parameter is null
     */
    public boolean isToolAllowed(Skill skill, String toolName) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(toolName, "Tool name cannot be null");

        if (!skill.hasToolRestrictions()) {
            return true;
        }

        return skill.getMetadata().getAllowedTools().stream()
                .anyMatch(allowed -> allowed.getToolName().equals(toolName));
    }

    /**
     * Validates tools usage against skill permissions.
     *
     * <p>
     * Checks both tools name and argument patterns. For example, if the skill allows "Bash(python:*)", it will allow
     * "python script.py" but not "rm -rf".
     *
     * <p>
     * <b>Command patterns only.</b> The argument arrives as an already-flattened string and there is no
     * {@link at.aimon.core.agent.tool.Tool} instance to ask, so this path always matches with
     * {@link at.aimon.core.agent.tool.permission.ToolPattern} — the {@code COMMAND} matcher. A path spec such as
     * {@code Read(/tmp/**)} is not interpreted as a glob here: it goes through the same prefix-and-metacharacter rules
     * a command would, and {@code (} or {@code &amp;} in the argument is refused outright. Path specs are judged as
     * globs only on the {@link at.aimon.core.agent.tool.ToolExecutionManager} path, where the tool itself supplies a
     * {@link at.aimon.core.agent.tool.permission.PermissionSubject}.
     *
     * @param skill
     *            The skill (must not be null)
     * @param toolName
     *            The tools name being used (must not be null)
     * @param arguments
     *            Tool arguments for pattern matching (must not be null)
     * @throws SkillPermissionException
     *             if tools usage is not allowed
     * @throws NullPointerException
     *             if any parameter is null
     */
    public void validateToolUsage(Skill skill, String toolName, String arguments) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        Objects.requireNonNull(arguments, "Arguments cannot be null");

        if (!skill.hasToolRestrictions()) {
            return;
        }

        final boolean allowed = skill.getMetadata().getAllowedTools().stream().anyMatch(allowedTool -> {
            if (!allowedTool.getToolName().equals(toolName)) {
                return false;
            }
            // If tools has no pattern, it's allowed
            if (!allowedTool.hasPattern()) {
                return true;
            }
            // If tools has pattern, check if arguments match
            return allowedTool.getPattern().get().matches(arguments);
        });

        if (!allowed) {
            throw new SkillPermissionException(skill.getName(), toolName,
                    String.format("Tool '%s' with arguments '%s' is not allowed. " + "Allowed tools: %s", toolName,
                            arguments, skill.getMetadata().getAllowedTools()));
        }
    }
}
