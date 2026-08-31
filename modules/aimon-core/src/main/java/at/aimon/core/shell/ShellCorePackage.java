package at.aimon.core.shell;

/**
 * Marker class representing the {@code at.aimon.core.shell} package.
 *
 * <p>
 * This class serves as a type-safe reference to the shell core package. It is primarily used for architecture testing
 * and package identification scenarios. This class should not be instantiated.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {
 *     &#64;code
 *     String packageName = ShellCorePackage.class.getPackageName() + "..";
 *     // Use packageName for package-based operations
 * }
 * </pre>
 */
public final class ShellCorePackage {
    private ShellCorePackage() {
        throw new AssertionError("This class should not be instantiated");
    }
}
