package at.aimon.core.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for built-in system commands.
 *
 * <p>
 * Thread-safe registry initialized with provided system commands. Supports runtime addition and removal of system
 * commands.
 *
 * <p>
 * System commands:
 *
 * <ul>
 * <li>Are compiled into the application
 * <li>Can be added or removed at runtime
 * <li>Take precedence over custom commands
 * <li>Are always available (if registered)
 * </ul>
 *
 * <p>
 * Thread-safe with ConcurrentHashMap.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     List<SystemCommand> commands = List.of(new HelpCommand(commandRegistry), new VersionCommand(version),
 *             new ClearCommand(sessionContext));
 *     SystemCommandRegistry registry = new SystemCommandRegistry(commands);
 *
 *     // Get a system command
 *     Optional<SystemCommand> help = registry.getCommand("help");
 *
 *     // Check if a command is a system command
 *     boolean isSystem = registry.isSystemCommand("help"); // true
 *     boolean isCustom = registry.isSystemCommand("commit"); // false
 *
 *     // Get all system commands
 *     List<SystemCommand> allCommands = registry.getAllCommands();
 *
 *     // Add a new system command at runtime
 *     registry.addCommand(new CustomSystemCommand());
 *
 *     // Remove a system command
 *     registry.removeCommand("help");
 * }
 * </pre>
 */
public final class SystemCommandRegistry {
    private final Map<String, SystemCommand> commands;

    /**
     * Creates a new empty SystemCommandRegistry.
     *
     * <p>
     * Creates a registry with no system commands.
     */
    public SystemCommandRegistry() {
        this(List.of());
    }

    /**
     * Creates a new SystemCommandRegistry with the provided system commands.
     *
     * @param systemCommands
     *            The list of system commands to register (must not be null, may be empty)
     * @throws NullPointerException
     *             if systemCommands is null or contains null elements
     * @throws IllegalArgumentException
     *             if systemCommands contains duplicate command names
     */
    public SystemCommandRegistry(List<SystemCommand> systemCommands) {
        Objects.requireNonNull(systemCommands, "System commands cannot be null");

        Map<String, SystemCommand> temp = new ConcurrentHashMap<>();

        // Register all system commands
        for (SystemCommand command : systemCommands) {
            Objects.requireNonNull(command, "System command cannot be null");
            if (temp.containsKey(command.getName())) {
                throw new IllegalArgumentException("Duplicate system command name: " + command.getName());
            }
            temp.put(command.getName(), command);
        }
        commands = temp;
    }

    /**
     * Gets a system command by name.
     *
     * @param name
     *            The command name (must not be null)
     * @return An Optional containing the command, or empty if not found
     * @throws NullPointerException
     *             if name is null
     */
    public Optional<SystemCommand> getCommand(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    /**
     * Returns all system commands.
     *
     * @return An immutable list of all system commands (never null, may be empty)
     */
    public List<SystemCommand> getAllCommands() {
        return List.copyOf(commands.values());
    }

    /**
     * Checks if a command name corresponds to a system command.
     *
     * <p>
     * Useful for conflict detection when loading custom commands.
     *
     * @param name
     *            The command name to check (must not be null)
     * @return true if the name is a system command, false otherwise
     * @throws NullPointerException
     *             if name is null
     */
    public boolean hasCommand(String name) {
        return commands.containsKey(name);
    }

    /**
     * Returns the number of system commands.
     *
     * @return The number of registered system commands (>= 0)
     */
    public int size() {
        return commands.size();
    }

    /**
     * Adds a new system command to the registry.
     *
     * <p>
     * If a command with the same name already exists, it will be replaced.
     *
     * @param command
     *            The system command to add (must not be null)
     * @throws NullPointerException
     *             if command is null
     */
    public void addCommand(SystemCommand command) {
        Objects.requireNonNull(command, "System command cannot be null");
        commands.put(command.getName(), command);
    }

    /**
     * Removes a system command from the registry.
     *
     * @param commandName
     *            The name of the command to remove (must not be null)
     * @return An Optional containing the removed command, or empty if not found
     * @throws NullPointerException
     *             if commandName is null
     */
    public Optional<SystemCommand> removeCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");
        return Optional.ofNullable(commands.remove(commandName));
    }

    @Override
    public String toString() {
        return "SystemCommandRegistry{commands=" + commands.keySet() + '}';
    }
}
