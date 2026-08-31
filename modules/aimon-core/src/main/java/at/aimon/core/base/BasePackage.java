package at.aimon.core.base;

/**
 * Marker class representing the {@code at.aimon.core.base} package.
 *
 * <p>
 * This class serves as a type-safe reference to the base package. It is primarily used for architecture testing and
 * package identification scenarios. This class should not be instantiated.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {
 *     &#64;code
 *     String packageName = BasePackage.class.getPackageName() + "..";
 *     // Use packageName for package-based operations
 * }
 * </pre>
 */
public final class BasePackage {
    private BasePackage() {
        throw new UnsupportedOperationException("This class should not be instantiated");
    }
}
