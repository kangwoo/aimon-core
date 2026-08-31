package at.aimon.core.command.skill;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.command.Command;
import at.aimon.core.command.CommandNameConflictDetector;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillRegistry;

/**
 * Read-through {@link CommandRegistry} that exposes user-invocable {@link Skill}s as {@link Command}s.
 *
 * <p>
 * Introduced in SK-08-D. The registry does <b>not</b> cache wrapped commands; it always asks the underlying
 * {@link SkillRegistry} so reloads in the skill registry are reflected immediately. Filtering is done by
 * {@link InvokePolicy#isUserInvocable()}: skills that opt out of user invocation (the default before SK-08-A) are
 * invisible to slash-command lookups.
 *
 * <p>
 * From the perspective of {@link CommandRegistry}, every adapted command is a skill-backed (non-system) command, so
 * {@link #getSystemCommands()} returns an empty list and {@link #isSystemCommand(String)} always returns {@code false}.
 * The composite registry is responsible for layering system + skill-backed sources and resolving precedence (see
 * {@link CommandNameConflictDetector}).
 *
 * <p>
 * Reload semantics:
 *
 * <ul>
 * <li>{@link #reloadCommand(String)} delegates to {@link SkillRegistry#reloadSkill(String)}.
 * <li>{@link #reloadAll()} delegates to {@link SkillRegistry#reloadAll()}.
 * </ul>
 *
 * <p>
 * Thread-safe to the same degree the wrapped {@link SkillRegistry} is.
 */
public final class SkillBackedCommandRegistry implements CommandRegistry {

    private final SkillRegistry skillRegistry;

    /**
     * Creates a new registry adapter.
     *
     * @param skillRegistry
     *            The skill registry to wrap (must not be null)
     * @throws NullPointerException
     *             if {@code skillRegistry} is null
     */
    public SkillBackedCommandRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "Skill registry cannot be null");
    }

    @Override
    public Optional<Command> getCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");
        return skillRegistry.getSkill(commandName).filter(SkillBackedCommandRegistry::isUserInvocable)
                .map(SkillBackedCommand::new);
    }

    @Override
    public List<Command> getAllCommands() {
        return skillRegistry.getAllSkills().stream().filter(SkillBackedCommandRegistry::isUserInvocable)
                .<Command>map(SkillBackedCommand::new).toList();
    }

    @Override
    public List<Command> getSystemCommands() {
        return List.of();
    }

    @Override
    public List<Command> getSkillBackedCommands() {
        return getAllCommands();
    }

    @Override
    public boolean hasCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");
        return getCommand(commandName).isPresent();
    }

    @Override
    public boolean isSystemCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");
        return false;
    }

    @Override
    public void reloadCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");
        skillRegistry.reloadSkill(commandName);
    }

    @Override
    public void reloadAll() {
        skillRegistry.reloadAll();
    }

    private static boolean isUserInvocable(Skill skill) {
        return skill.getMetadata().getInvokePolicy().isUserInvocable();
    }
}
