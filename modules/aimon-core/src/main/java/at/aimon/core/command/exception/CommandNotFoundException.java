package at.aimon.core.command.exception;

/**
 * Exception thrown when a command file is not found in the .aimon/commands/ directory.
 *
 * <p>
 * This exception is typically thrown when trying to load a command that doesn't exist or when the command file cannot
 * be accessed.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * if (!commandFile.exists()) {
 *     throw new CommandNotFoundException("commit");
 * }
 * }
 * </pre>
 */
public class CommandNotFoundException extends CommandException {
    private static final long serialVersionUID = 1019675280184354187L;

    private final String commandName;

    /**
     * Creates a new CommandNotFoundException for the specified command name.
     *
     * @param commandName
     *            The name of the command that was not found
     */
    public CommandNotFoundException(String commandName) {
        super("Command not found: " + commandName);
        this.commandName = commandName;
    }

    /**
     * Returns the name of the command that was not found.
     *
     * @return The command name
     */
    public String getCommandName() {
        return commandName;
    }
}
