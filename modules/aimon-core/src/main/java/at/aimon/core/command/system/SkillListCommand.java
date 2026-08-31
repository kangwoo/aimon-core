package at.aimon.core.command.system;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.Constants;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;

/**
 * Built-in command that displays all registered skills.
 *
 * <p>
 * Lists skills with their descriptions.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /skills             - List all registered skills
 * </pre>
 *
 * <p>
 * This command executes directly without LLM interaction via {@link DirectExecutable}.
 */
public final class SkillListCommand extends SystemCommand implements DirectExecutable {
    private final SkillRegistry skillRegistry;

    /**
     * Creates a new SkillListCommand.
     *
     * @param skillRegistry
     *            The skill registry to list skills from (must not be null)
     * @throws NullPointerException
     *             if skillRegistry is null
     */
    public SkillListCommand(SkillRegistry skillRegistry) {
        super("skills", "Display all registered skills");
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "Skill registry cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final List<Skill> skills = skillRegistry.getAllSkills();

        final StringBuilder output = new StringBuilder();
        output.append("Registered skills:").append(Constants.DOUBLE_NEWLINE);

        if (skills.isEmpty()) {
            output.append("No skills registered.");
        } else {
            for (Skill skill : skills) {
                final SkillMetadata metadata = skill.getMetadata();
                output.append(String.format("  %s %s - %s%s", skill.getName(), formatInvokeMarker(metadata),
                        metadata.getDescription(), Constants.NEWLINE));
            }
            output.append(Constants.NEWLINE);
            output.append(String.format("Total: %d skill(s)", skills.size()));
        }

        return CommandExecutionResult.success(output.toString());
    }

    /**
     * Formats the invoke-policy marker shown next to each skill in the listing.
     *
     * <p>
     * Output reflects the {@code invoke.user} / {@code invoke.model} flags:
     * <ul>
     * <li>{@code [user, model]} — both flags enabled
     * <li>{@code [user-only]} — invocable by user but hidden from the LLM
     * <li>{@code [model-only]} — exposed to the LLM but not directly user-invocable (current AIMON default)
     * <li>{@code [disabled]} — neither flag enabled
     * </ul>
     */
    private static String formatInvokeMarker(SkillMetadata metadata) {
        final InvokePolicy policy = metadata.getInvokePolicy();
        final boolean user = policy.isUserInvocable();
        final boolean model = policy.isModelInvocable();
        if (user && model) {
            return "[user, model]";
        }
        if (user) {
            return "[user-only]";
        }
        if (model) {
            return "[model-only]";
        }
        return "[disabled]";
    }
}
