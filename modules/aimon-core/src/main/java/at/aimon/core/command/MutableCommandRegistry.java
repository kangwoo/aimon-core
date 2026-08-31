package at.aimon.core.command;

import java.util.Optional;

/**
 * Mutable extension of {@link CommandRegistry} that supports system command registration and unregistration.
 *
 * <p>
 * Separates mutation operations from the read-only {@link CommandRegistry} interface, following the CQRS principle.
 * Consumers that only need to query commands should depend on {@link CommandRegistry}, while components that need to
 * register or unregister system commands should depend on this interface.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     MutableCommandRegistry registry = new DefaultCommandRegistry(systemCommands, skillRegistry, fileSystem,
 *             ".aimon/commands");
 *     registry.initialize();
 *
 *     // Register additional system commands at runtime
 *     registry.registerSystemCommand(new HelpCommand(() -> registry));
 *     registry.registerSystemCommand(new VersionCommand("1.0.0"));
 *
 *     // Unregister system commands
 *     Optional<SystemCommand> removed = registry.unregisterSystemCommand("version");
 * }
 * </pre>
 *
 * @see CommandRegistry
 * @see DefaultCommandRegistry
 */
public interface MutableCommandRegistry extends CommandRegistry {

    /**
     * Registers a new system command in the registry.
     *
     * <p>
     * If a command with the same name already exists, it will be replaced. System commands take precedence over
     * skill-backed commands.
     *
     * @param command
     *            The system command to register (must not be null)
     * @throws NullPointerException
     *             if command is null
     */
    void registerSystemCommand(SystemCommand command);

    /**
     * Unregisters a system command from the registry.
     *
     * <p>
     * Only system commands can be unregistered with this method. Skill-backed commands are owned by
     * {@link at.aimon.core.skill.SkillRegistry} and must be removed there.
     *
     * @param commandName
     *            The name of the command to unregister (must not be null)
     * @return An Optional containing the unregistered command, or empty if not found
     * @throws NullPointerException
     *             if commandName is null
     */
    Optional<SystemCommand> unregisterSystemCommand(String commandName);
}
