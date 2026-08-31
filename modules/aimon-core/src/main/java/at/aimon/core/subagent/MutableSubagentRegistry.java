package at.aimon.core.subagent;

import java.util.Optional;

/**
 * Mutable extension of {@link SubagentRegistry} that supports programmatic subagent registration and unregistration.
 *
 * <p>
 * Separates mutation operations from the read-only {@link SubagentRegistry} interface, following the CQRS principle.
 * Consumers that only query subagents (e.g. {@code TaskTool}, skill-fork, the {@code /agents} command, the ReAct
 * executor) depend on {@link SubagentRegistry}; only bootstrap/registration code depends on this interface.
 *
 * <p>
 * The canonical implementation is {@link InMemorySubagentRegistry}, used to contribute code-defined subagents
 * (built via {@link Subagent#builder()}) that are composed with the file-based registries through
 * {@link CompositeSubagentRegistry}.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     MutableSubagentRegistry registry = new InMemorySubagentRegistry();
 *     registry.register(Subagent.builder().name("db-triage").systemPrompt("...").build());
 *
 *     Optional<Subagent> removed = registry.unregister("db-triage");
 * }
 * </pre>
 *
 * @see SubagentRegistry
 * @see InMemorySubagentRegistry
 */
public interface MutableSubagentRegistry extends SubagentRegistry {

    /**
     * Registers a subagent in the registry.
     *
     * <p>
     * If a subagent with the same name already exists, it is replaced.
     *
     * @param subagent
     *            the subagent to register (must not be null)
     * @throws NullPointerException
     *             if subagent is null
     */
    void register(Subagent subagent);

    /**
     * Unregisters a subagent from the registry.
     *
     * @param name
     *            the name of the subagent to unregister (must not be null)
     * @return an Optional containing the unregistered subagent, or empty if none was registered under that name
     * @throws NullPointerException
     *             if name is null
     */
    Optional<Subagent> unregister(String name);
}
