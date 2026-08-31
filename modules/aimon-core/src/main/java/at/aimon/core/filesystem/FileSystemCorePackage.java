package at.aimon.core.filesystem;

/**
 * Marker class representing the {@code at.aimon.core.filesystem} package.
 *
 * <p>
 * This class serves as a type-safe reference to the filesystem core package. It is primarily used for architecture
 * testing and package identification scenarios. This class should not be instantiated.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {
 *     &#64;code
 *     String packageName = FileSystemCorePackage.class.getPackageName() + "..";
 *     // Use packageName for package-based operations
 * }
 * </pre>
 */
public final class FileSystemCorePackage {
    private FileSystemCorePackage() {
        throw new AssertionError("This class should not be instantiated");
    }
}
