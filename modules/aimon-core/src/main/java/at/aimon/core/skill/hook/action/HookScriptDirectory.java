package at.aimon.core.skill.hook.action;

import java.nio.file.Path;
import java.util.Objects;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.VirtualFileSystems;

/**
 * The {@code hooks/} directory of a {@code hooks.json} layer, carried by a {@link ShellAction} whose command names
 * {@code AIMON_HOOK_DIR} (aimon-core #273, EE-48).
 *
 * <p>
 * A configured command runs either on the host or in the firing execution's environment, and which one is decided
 * per firing. The executor that ends up running the command is therefore the one that gives the variable its value:
 * the host path itself on the host, a run directory of the bytes read at the firing in an environment (EE-109). This
 * value is
 * what both need &mdash; the host path, and a read-only source over it to scan from &mdash; so it travels on the
 * action rather than through the hook.
 *
 * <p>
 * The source is a read-only view of the layer's {@code .aimon} directory, and {@link #getSourceDirectory()} names
 * {@code hooks} inside it: nothing outside that subdirectory is ever scanned or read.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class HookScriptDirectory {

    /** The directory name next to the declaring {@code hooks.json}. */
    public static final String DIRECTORY_NAME = "hooks";

    private final Path hostDirectory;
    private final String resourceName;
    private final VirtualFileSystem sourceFileSystem;

    private HookScriptDirectory(Path hostDirectory, String resourceName) {
        this.hostDirectory = hostDirectory;
        this.resourceName = resourceName;
        this.sourceFileSystem = VirtualFileSystems.readOnlyLocal(hostDirectory.getParent());
    }

    /**
     * Creates the directory value for one layer.
     *
     * @param layerDirectory
     *            the layer's {@code .aimon} directory, the one holding its {@code hooks.json} (must not be null)
     * @param resourceName
     *            the name the directory is known by (the resource name it is scanned under); one path segment (must
     *            not be null or blank)
     * @return the value (never null)
     * @throws NullPointerException
     *             if an argument is null
     * @throws IllegalArgumentException
     *             if {@code resourceName} is blank
     */
    public static HookScriptDirectory of(Path layerDirectory, String resourceName) {
        Objects.requireNonNull(layerDirectory, "layerDirectory cannot be null");
        Objects.requireNonNull(resourceName, "resourceName cannot be null");
        if (resourceName.isBlank()) {
            throw new IllegalArgumentException("resourceName cannot be blank");
        }
        return new HookScriptDirectory(layerDirectory.toAbsolutePath().normalize().resolve(DIRECTORY_NAME),
                resourceName);
    }

    /**
     * @return the absolute host path of the {@code hooks/} directory; what {@code AIMON_HOOK_DIR} is set to when the
     *         command runs on the host (never null)
     */
    public Path getHostDirectory() {
        return hostDirectory;
    }

    /**
     * @return the name the directory is known by (never null)
     */
    public String getResourceName() {
        return resourceName;
    }

    /**
     * @return a read-only filesystem over the layer's {@code .aimon} directory, to scan the scripts from (never null)
     */
    public VirtualFileSystem getSourceFileSystem() {
        return sourceFileSystem;
    }

    /**
     * @return the directory to scan on {@link #getSourceFileSystem()}: {@value #DIRECTORY_NAME} (never null)
     */
    public String getSourceDirectory() {
        return DIRECTORY_NAME;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HookScriptDirectory that)) {
            return false;
        }
        return hostDirectory.equals(that.hostDirectory) && resourceName.equals(that.resourceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostDirectory, resourceName);
    }

    @Override
    public String toString() {
        return "HookScriptDirectory{hostDirectory=" + hostDirectory + ", resourceName='" + resourceName + "'}";
    }
}
