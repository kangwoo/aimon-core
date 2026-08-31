package at.aimon.core.agent;

/**
 * Marker class representing the {@code at.aimon.core.agent} package.
 *
 * <p>
 * This class serves as a type-safe reference to the agent core package. It is primarily used for architecture testing
 * and package identification scenarios. This class should not be instantiated.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {
 *     &#64;code
 *     String packageName = AgentCorePackage.class.getPackageName() + "..";
 *     // Use packageName for package-based operations
 * }
 * </pre>
 */
public final class AgentCorePackage {
    private AgentCorePackage() {
        throw new AssertionError("This class should not be instantiated");
    }
}
