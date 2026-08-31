package at.aimon.core.agent.orca.tool;

import at.aimon.core.agent.tool.ToolRegistry;

/**
 * Provides tools to the Orca agent system.
 *
 * <p>
 * Implementations are responsible for creating and registering specific groups of tools. This interface enables modular
 * tool registration, allowing for flexible composition of tool sets and easier testing through dependency injection.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OrcaToolProvider fileToolProvider = new OrcaFileToolProvider();
 *     fileToolProvider.registerTools(toolRegistry, context);
 * }
 * </pre>
 *
 * @see OrcaToolProviderContext
 * @see ToolRegistry
 */
public interface OrcaToolProvider {

    /**
     * Registers tools to the given tool registry.
     *
     * <p>
     * Implementations should create tool instances using dependencies from the provided context and register them with
     * the registry. This method may be called multiple times with different registries if needed.
     *
     * @param registry
     *            the tool registry to register tools to, must not be null
     * @param context
     *            the context providing dependencies needed for tool creation, must not be null
     * @throws NullPointerException
     *             if registry or context is null
     */
    void registerTools(ToolRegistry registry, OrcaToolProviderContext context);
}
