package at.aimon.core.command;

import java.util.Objects;

import at.aimon.core.command.execution.direct.DirectExecutable;

/**
 * Abstract base class for built-in system commands.
 *
 * <p>
 * System commands are compiled into the codebase and provide core functionality like help, version, and clear.
 *
 * <p>
 * Key characteristics:
 *
 * <ul>
 * <li>Implemented as Java classes (compiled, type-safe)
 * <li>No file I/O overhead
 * <li>Cannot be overridden by custom commands
 * <li>Always available
 * <li>Take precedence over custom commands with the same name
 * </ul>
 *
 * <p>
 * Subclasses can choose their execution strategy:
 *
 * <ul>
 * <li>Implement {@link DirectExecutable} for direct Java execution (recommended for most system commands)
 * </ul>
 *
 * <p>
 * Example implementation for direct execution:
 *
 * <pre>
 * {
 *     &#64;code
 *     public final class ClearCommand extends SystemCommand implements DirectExecutable {
 *         private final SessionRecordStore repository;
 *
 *         public ClearCommand(SessionRecordStore repository) {
 *             super("clear", "Clear conversation history");
 *             this.repository = repository;
 *         }
 *
 *         &#64;Override
 *         public CommandExecutionResult execute(CommandExecutionContext context,
 *                 DirectCommandExecutionRequest request) {
 *             // Execute command logic directly
 *             SessionId id = request.getPreviousSnapshot().map(SessionSnapshot::getSessionId)
 *                     .orElse(null);
 *             if (id != null) {
 *                 repository.delete(id);
 *             }
 *             return CommandExecutionResult.success("Conversation cleared.");
 *         }
 *     }
 * }
 * </pre>
 *
 * @see Command
 * @see DirectExecutable
 * @see at.aimon.core.command.skill.SkillBackedCommand
 */
public abstract class SystemCommand implements Command {
    private final String name;
    private final CommandMetadata metadata;

    /**
     * Creates a new system command with the specified name and description.
     *
     * @param name
     *            The command name (must match [a-z0-9-]+)
     * @param description
     *            The command description
     * @throws NullPointerException
     *             if name or description is null
     */
    protected SystemCommand(String name, String description) {
        this.name = Objects.requireNonNull(name, "Command name cannot be null");
        Objects.requireNonNull(description, "Description cannot be null");
        metadata = CommandMetadata.builder().description(description).build();
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final CommandMetadata getMetadata() {
        return metadata;
    }

    @Override
    public final CommandType getType() {
        return CommandType.SYSTEM;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SystemCommand that = (SystemCommand) o;
        return name.equals(that.name) && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, metadata);
    }

    @Override
    public String toString() {
        return "SystemCommand{name='" + name + "'}";
    }
}
