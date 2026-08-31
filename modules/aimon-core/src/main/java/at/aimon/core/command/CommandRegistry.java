package at.aimon.core.command;

import java.util.List;
import java.util.Optional;

import at.aimon.core.base.Reloadable;
import at.aimon.core.command.exception.CommandNotFoundException;

/**
 * Registry interface for managing commands.
 *
 * <p>
 * Provides abstraction for command storage and retrieval, supporting:
 *
 * <ul>
 * <li>Command lookup by name
 * <li>Listing all available commands (system + skill-backed)
 * <li>Hot-reloading of command definitions
 * </ul>
 *
 * <p>
 * System commands take precedence over skill-backed commands in case of name conflicts.
 *
 * <p>
 * Thread-safe implementations are recommended for concurrent access.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     CommandRegistry registry = new DefaultCommandRegistry(systemCommands, skillRegistry, fileSystem,
 *             ".aimon/commands");
 *     registry.initialize();
 *
 *     Optional<Command> help = registry.getCommand("help");
 *     List<Command> allCommands = registry.getAllCommands();
 *     registry.reloadAll();
 * }
 * </pre>
 */
public interface CommandRegistry extends Reloadable {

    /**
     * Gets a command by name.
     *
     * <p>
     * System commands are checked first, then skill-backed commands.
     *
     * @param commandName
     *            The command name (must not be null)
     * @return An Optional containing the command, or empty if not found
     * @throws NullPointerException
     *             if commandName is null
     */
    Optional<Command> getCommand(String commandName);

    /**
     * Gets a command by name, throwing an exception if not found.
     *
     * <p>
     * Convenience method for when the command is expected to exist.
     *
     * @param commandName
     *            The command name (must not be null)
     * @return The command (never null)
     * @throws CommandNotFoundException
     *             if command is not found
     * @throws NullPointerException
     *             if commandName is null
     */
    default Command getCommandOrThrow(String commandName) {
        return getCommand(commandName).orElseThrow(() -> new CommandNotFoundException(commandName));
    }

    /**
     * Gets all available commands (system + skill-backed).
     *
     * @return A list of all commands (never null, may be empty)
     */
    List<Command> getAllCommands();

    /**
     * Gets only system commands.
     *
     * @return A list of system commands (never null, may be empty)
     */
    List<Command> getSystemCommands();

    /**
     * Gets user-invocable skill-backed commands.
     *
     * <p>
     * Implementations that do not surface skill-backed commands return an empty list.
     *
     * @return A list of skill-backed commands (never null, may be empty)
     */
    default List<Command> getSkillBackedCommands() {
        return List.of();
    }

    /**
     * Checks if a command exists (system or skill-backed).
     *
     * @param commandName
     *            The command name (must not be null)
     * @return true if the command exists, false otherwise
     * @throws NullPointerException
     *             if commandName is null
     */
    boolean hasCommand(String commandName);

    /**
     * Checks if a command is a system command.
     *
     * @param commandName
     *            The command name (must not be null)
     * @return true if the command is a system command, false otherwise
     * @throws NullPointerException
     *             if commandName is null
     */
    boolean isSystemCommand(String commandName);

    /**
     * Reloads a specific command.
     *
     * <p>
     * System commands cannot be reloaded; skill reloads happen through {@link at.aimon.core.skill.SkillRegistry}.
     * The default {@link DefaultCommandRegistry} treats this as a no-op for unknown names.
     *
     * @param commandName
     *            The command name to reload (must not be null)
     * @throws NullPointerException
     *             if commandName is null
     */
    void reloadCommand(String commandName);
}
