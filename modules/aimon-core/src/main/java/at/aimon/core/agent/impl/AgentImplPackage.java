package at.aimon.core.agent.impl;

/**
 * Marker class representing the {@code at.aimon.core.agent.impl} package.
 *
 * <p>
 * This class serves as a type-safe reference to the agents package (containing specific agent implementations such as
 * Orca). It is primarily used for architecture testing and package identification scenarios. This class should not be
 * instantiated.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {
 *     &#64;code
 *     String packageName = AgentImplPackage.class.getPackageName() + "..";
 *     // Use packageName for package-based operations
 * }
 * </pre>
 */
public final class AgentImplPackage {
    private AgentImplPackage() {
        throw new AssertionError("This class should not be instantiated");
    }
}
