package at.aimon.core.llm;

/**
 * Marker class representing the {@code at.aimon.core.llm} package.
 *
 * <p>
 * This class serves as a type-safe reference to the LLM core package. It is primarily used for architecture testing and
 * package identification scenarios. This class should not be instantiated.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {
 *     &#64;code
 *     String packageName = LlmCorePackage.class.getPackageName() + "..";
 *     // Use packageName for package-based operations
 * }
 * </pre>
 */
public final class LlmCorePackage {
    private LlmCorePackage() {
        throw new AssertionError("This class should not be instantiated");
    }
}
