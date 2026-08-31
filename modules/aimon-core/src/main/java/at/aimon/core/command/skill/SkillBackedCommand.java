package at.aimon.core.command.skill;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.command.Command;
import at.aimon.core.command.CommandContent;
import at.aimon.core.command.CommandMetadata;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.llm.LlmExecutable;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillMetadata;

/**
 * Adapter exposing a user-invocable {@link Skill} through the {@link Command} contract.
 *
 * <p>
 * Introduced in SK-08-D as part of the CustomCommand → Skill unification (option C). The adapter lets the existing
 * slash-command pipeline ({@code /<name>} → {@link at.aimon.core.command.CommandRegistry}) keep working while the
 * authoritative storage moves to {@link at.aimon.core.skill.SkillRegistry}. The actual ReAct loop runs through
 * {@link at.aimon.core.skill.execution.llm.LlmSkillExecutor}; this adapter is consumed by the lookup-only
 * {@link Command} clients (REPL routing, {@code /commands} listing, etc.).
 *
 * <p>
 * Mapping rules from {@link SkillMetadata} to {@link CommandMetadata}:
 *
 * <ul>
 * <li>{@code description}: copied directly.
 * <li>{@code allowedTools}: forwarded as {@code allowedToolsObjects} (already parsed; no re-parse).
 * <li>{@code maxIterations}: copied directly.
 * </ul>
 *
 * <p>
 * The adapted command is always classified as {@link CommandType#CUSTOM} — it is not built into the binary. Context
 * tokens ({@code !`cmd`}, {@code @file}) are rendered at execution time by
 * {@link at.aimon.core.skill.render.SkillContentRenderer}; {@link CommandContent} no longer carries them.
 *
 * <p>
 * Immutable and thread-safe (provided the wrapped skill is immutable, which it is by contract).
 */
public final class SkillBackedCommand implements Command, LlmExecutable {

    /**
     * Wraps a skill as a {@link Command}.
     *
     * <p>
     * Callers are expected to verify that the skill is user-invocable (
     * {@link at.aimon.core.skill.InvokePolicy#isUserInvocable()}) before constructing this adapter. The adapter
     * itself does not enforce the policy because the registry-level filter is the authoritative gate.
     *
     * @param skill
     *            The skill to expose as a command (must not be null)
     * @throws NullPointerException
     *             if {@code skill} is null
     */
    public SkillBackedCommand(Skill skill) {
        this.skill = Objects.requireNonNull(skill, "Skill cannot be null");
    }

    private final Skill skill;

    /**
     * Returns the wrapped skill.
     *
     * @return The underlying skill (never null)
     */
    public Skill getSkill() {
        return skill;
    }

    @Override
    public String getName() {
        return skill.getName();
    }

    @Override
    public CommandMetadata getMetadata() {
        final SkillMetadata m = skill.getMetadata();
        final CommandMetadata.Builder builder = CommandMetadata.builder().description(m.getDescription())
                .maxIterations(m.getMaxIterations());
        // CommandMetadata.Builder only accepts raw spec strings; round-trip via toString() (canonical spec form).
        final List<String> specs = m.getAllowedTools().stream().map(AllowedTool::toString).toList();
        if (!specs.isEmpty()) {
            builder.allowedTools(specs);
        }
        return builder.build();
    }

    @Override
    public CommandType getType() {
        return CommandType.CUSTOM;
    }

    @Override
    public CommandContent getContent() {
        return CommandContent.of(skill.getContent().getInstructions());
    }

    @Override
    public List<AllowedTool> getAllowedTools() {
        return skill.getMetadata().getAllowedTools();
    }

    @Override
    public boolean hasPermissionRestrictions() {
        return skill.getMetadata().hasToolRestrictions();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SkillBackedCommand that = (SkillBackedCommand) o;
        return skill.equals(that.skill);
    }

    @Override
    public int hashCode() {
        return skill.hashCode();
    }

    @Override
    public String toString() {
        return "SkillBackedCommand{name='" + skill.getName() + "'}";
    }
}
