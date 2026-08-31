package at.aimon.core.agent.impl.orca.command;

import at.aimon.core.command.MutableCommandRegistry;

/**
 * Provides commands to the Orca agent system.
 *
 * <p>
 * Implementations are responsible for registering specific groups of commands. This interface enables modular command
 * registration, allowing for flexible composition of command sets and easier testing through dependency injection.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OrcaCommandProvider systemCommandProvider = new OrcaSystemCommandProvider();
 *     systemCommandProvider.registerCommands(commandRegistry, context);
 * }
 * </pre>
 *
 * @see OrcaCommandProviderContext
 * @see MutableCommandRegistry
 */
public interface OrcaCommandProvider {

    /**
     * Registers commands to the given command registry.
     *
     * <p>
     * Implementations should create command instances using dependencies from the provided context and register them
     * with the registry. This method may be called multiple times with different registries if needed.
     *
     * @param registry
     *            the mutable command registry to register commands to, must not be null
     * @param context
     *            the context providing dependencies needed for command creation, must not be null
     * @throws NullPointerException
     *             if registry or context is null
     */
    void registerCommands(MutableCommandRegistry registry, OrcaCommandProviderContext context);
}
