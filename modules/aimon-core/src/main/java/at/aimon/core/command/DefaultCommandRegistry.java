package at.aimon.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.command.exception.CommandException;
import at.aimon.core.command.skill.SkillBackedCommandRegistry;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.core.skill.SkillRegistry;

/**
 * Default implementation of {@link CommandRegistry} backed by built-in system commands and skill-backed commands.
 *
 * <p>
 * Sources, in lookup precedence:
 *
 * <ol>
 * <li>{@link SystemCommand} - built-in commands compiled into the binary
 * <li>{@link at.aimon.core.command.skill.SkillBackedCommand} - user-invocable skills exposed via {@code /<name>}
 * </ol>
 *
 * <p>
 * Cross-source name collisions are rejected by {@link CommandNameConflictDetector} at {@link #initialize()} time, so
 * lookup precedence is informational only.
 *
 * <p>
 * <b>Legacy {@code .aimon/commands/*.md} support was removed in SK-08-F.</b> The constructor still accepts the legacy
 * directory path and uses it for one purpose only: at {@link #initialize()} time, if any {@code *.md} files are present
 * under that directory, the registry fails fast with a {@link CommandException} that points at the migration guide.
 * This protects projects that upgrade across the cut without first running
 * {@code scripts/migrate-custom-command-to-skill.sh}.
 *
 * <p>
 * Thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem fileSystem = new LocalFileSystem(projectRoot);
 *     DefaultCommandRegistry registry = new DefaultCommandRegistry(systemCommands, skillRegistry, fileSystem,
 *             ".aimon/commands");
 *     registry.initialize();
 *
 *     Optional<Command> help = registry.getCommand("help"); // built-in
 *     Optional<Command> commit = registry.getCommand("commit"); // skill-backed
 *
 *     List<Command> all = registry.getAllCommands();
 *     List<Command> systemOnly = registry.getSystemCommands();
 *     List<Command> skillsOnly = registry.getSkillBackedCommands();
 * }
 * </pre>
 */
public class DefaultCommandRegistry implements MutableCommandRegistry {
    private static final Logger log = LoggerFactory.getLogger(DefaultCommandRegistry.class);
    private static final String LEGACY_FILE_SUFFIX = ".md";

    private final SystemCommandRegistry systemCommandRegistry;
    private final SkillBackedCommandRegistry skillBackedCommandRegistry;
    private final CommandNameConflictDetector conflictDetector;
    private final VirtualFileSystem fileSystem;
    private final String legacyCommandsDirectory;
    private volatile boolean initialized;

    /**
     * Creates a new DefaultCommandRegistry with system commands, skill-backed commands, and a legacy directory to
     * reject.
     *
     * <p>
     * If {@code skillRegistry} is {@code null}, {@code /<name>} routing falls back to built-in system commands only.
     * The {@code legacyCommandsDirectory} is not loaded; it is scanned at {@link #initialize()} time and the registry
     * fails fast if it still contains command files (SK-08-F).
     *
     * @param systemCommands
     *            The system commands to expose (must not be null)
     * @param skillRegistry
     *            The skill registry to expose user-invocable skills as commands (may be null)
     * @param fileSystem
     *            The virtual file system used for the legacy-directory check (must not be null)
     * @param legacyCommandsDirectory
     *            The legacy {@code .aimon/commands} directory to scan and reject (must not be null)
     * @throws NullPointerException
     *             if a required parameter is null
     */
    public DefaultCommandRegistry(List<SystemCommand> systemCommands, SkillRegistry skillRegistry,
            VirtualFileSystem fileSystem, String legacyCommandsDirectory) {
        Objects.requireNonNull(systemCommands, "System commands cannot be null");
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
        this.legacyCommandsDirectory = Objects.requireNonNull(legacyCommandsDirectory,
                "Legacy commands directory cannot be null");

        systemCommandRegistry = new SystemCommandRegistry(systemCommands);
        skillBackedCommandRegistry = (skillRegistry == null) ? null : new SkillBackedCommandRegistry(skillRegistry);
        conflictDetector = new CommandNameConflictDetector();
    }

    /**
     * Creates a new DefaultCommandRegistry with no system commands and no skill registry.
     *
     * <p>
     * Test convenience overload. Production callers should use the four-arg constructor and supply a real skill
     * registry.
     *
     * @param fileSystem
     *            The virtual file system used for the legacy-directory check (must not be null)
     * @param legacyCommandsDirectory
     *            The legacy {@code .aimon/commands} directory to scan and reject (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultCommandRegistry(VirtualFileSystem fileSystem, String legacyCommandsDirectory) {
        this(List.of(), null, fileSystem, legacyCommandsDirectory);
    }

    /**
     * Initializes the registry.
     *
     * <p>
     * Verifies that the legacy commands directory is empty (or absent) and that no command name appears in more than
     * one source.
     *
     * @throws CommandException
     *             if the legacy directory still contains {@code *.md} files (SK-08-F)
     * @throws IllegalStateException
     *             if a command name is published by more than one source
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        rejectLegacyCommandFiles();
        verifyNoConflicts();
        initialized = true;
    }

    private void rejectLegacyCommandFiles() {
        final List<String> legacyFiles;
        try {
            if (!fileSystem.exists(legacyCommandsDirectory)) {
                return;
            }
            legacyFiles = fileSystem.list(legacyCommandsDirectory).stream()
                    .filter(path -> path.endsWith(LEGACY_FILE_SUFFIX)).filter(path -> !fileSystem.isDirectory(path))
                    .toList();
        } catch (VirtualFileSystemException e) {
            throw new CommandException(
                    "Failed to scan legacy commands directory '" + legacyCommandsDirectory + "' for migration check",
                    e);
        }

        if (legacyFiles.isEmpty()) {
            return;
        }

        // Name the workspace root, not just the directory: legacyCommandsDirectory is filesystem-relative and
        // therefore identical for every agent runtime, so on a multi-tenant deployment it alone does not say
        // whose workspace has to be cleaned up.
        final String workspaceRoot = fileSystem.getWorkingDirectory();
        log.error("Legacy {} files detected under {} of workspace {}: {}", LEGACY_FILE_SUFFIX, legacyCommandsDirectory,
                workspaceRoot, legacyFiles);
        throw new CommandException("Legacy CustomCommand files detected under '" + legacyCommandsDirectory
                + "' of agent workspace '" + workspaceRoot + "'. CustomCommand was removed in SK-08-F, so this "
                + "agent runtime cannot be created until every file is migrated to .aimon/skills/<name>/SKILL.md "
                + "and the originals are deleted (see docs/migration/custom-command-to-skill.md, or "
                + "scripts/migrate-custom-command-to-skill.sh in the AIMON repository). Files still present: "
                + legacyFiles);
    }

    @Override
    public Optional<Command> getCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");

        final Optional<? extends Command> systemCommand = systemCommandRegistry.getCommand(commandName);
        if (systemCommand.isPresent()) {
            return Optional.<Command>of(systemCommand.get());
        }

        if (skillBackedCommandRegistry != null) {
            final Optional<Command> skillCommand = skillBackedCommandRegistry.getCommand(commandName);
            if (skillCommand.isPresent()) {
                return skillCommand;
            }
        }

        return Optional.empty();
    }

    @Override
    public List<Command> getAllCommands() {
        final List<Command> all = new ArrayList<>();
        all.addAll(systemCommandRegistry.getAllCommands());
        if (skillBackedCommandRegistry != null) {
            all.addAll(skillBackedCommandRegistry.getAllCommands());
        }
        return List.copyOf(all);
    }

    @Override
    public List<Command> getSystemCommands() {
        return List.copyOf(systemCommandRegistry.getAllCommands());
    }

    @Override
    public List<Command> getSkillBackedCommands() {
        return skillBackedCommandRegistry == null ? List.of() : skillBackedCommandRegistry.getAllCommands();
    }

    @Override
    public boolean hasCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");
        if (systemCommandRegistry.hasCommand(commandName)) {
            return true;
        }
        return skillBackedCommandRegistry != null && skillBackedCommandRegistry.hasCommand(commandName);
    }

    @Override
    public boolean isSystemCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");
        return systemCommandRegistry.hasCommand(commandName);
    }

    /**
     * Returns the total number of registered commands.
     *
     * @return The combined count of system and skill-backed commands
     */
    public int size() {
        final int skillCount = skillBackedCommandRegistry == null
                ? 0
                : skillBackedCommandRegistry.getAllCommands().size();
        return systemCommandRegistry.size() + skillCount;
    }

    @Override
    public void reloadCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");
        if (systemCommandRegistry.hasCommand(commandName)) {
            log.warn("Cannot reload '{}' as it is a system command.", commandName);
            return;
        }
        // Skills do not support hot-reload through this entry point. The SkillRegistry owns its own reload contract.
        log.debug("reloadCommand('{}') is a no-op; skill reloads happen through SkillRegistry.", commandName);
    }

    @Override
    public synchronized void reloadAll() {
        // Re-run the legacy rejection so a freshly created .aimon/commands/foo.md (e.g. accidentally checked in
        // post-migration) fails fast on the next reload sweep.
        rejectLegacyCommandFiles();
        verifyNoConflicts();
    }

    private void verifyNoConflicts() {
        final List<CommandNameConflictDetector.Source> sources = new ArrayList<>();
        sources.add(CommandNameConflictDetector.Source.ofCommands("system",
                () -> systemCommandRegistry.getAllCommands().stream().map(c -> (Command) c).toList()));
        if (skillBackedCommandRegistry != null) {
            sources.add(CommandNameConflictDetector.Source.of("skill", skillBackedCommandRegistry));
        }
        conflictDetector.verifyNoConflicts(sources);
    }

    /**
     * @return true once {@link #initialize()} has run successfully
     */
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void registerSystemCommand(SystemCommand command) {
        Objects.requireNonNull(command, "System command cannot be null");
        systemCommandRegistry.addCommand(command);
    }

    @Override
    public Optional<SystemCommand> unregisterSystemCommand(String commandName) {
        Objects.requireNonNull(commandName, "Command name cannot be null");
        return systemCommandRegistry.removeCommand(commandName);
    }
}
