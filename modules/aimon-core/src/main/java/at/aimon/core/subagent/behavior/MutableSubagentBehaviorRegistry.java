package at.aimon.core.subagent.behavior;

import java.util.Optional;

/**
 * Mutable extension of {@link SubagentBehaviorRegistry} that supports programmatic registration and unregistration of
 * {@link SubagentBehavior code behaviors}, keyed by subagent name (CQRS write side).
 *
 * <p>
 * Mirrors {@link at.aimon.core.subagent.MutableSubagentRegistry}. Register a behavior under the SAME name as its
 * {@link at.aimon.core.subagent.Subagent} data entry; {@link SubagentBehaviorRegistrar} does both in one call to keep
 * the
 * names from drifting.
 *
 * @see SubagentBehaviorRegistry
 * @see InMemorySubagentBehaviorRegistry
 * @see SubagentBehaviorRegistrar
 */
public interface MutableSubagentBehaviorRegistry extends SubagentBehaviorRegistry {

    /**
     * Registers a behavior for the given subagent name.
     *
     * <p>
     * If a behavior is already registered under that name, it is replaced.
     *
     * @param subagentName
     *            the subagent name (must not be null)
     * @param behavior
     *            the behavior (must not be null)
     * @throws NullPointerException
     *             if subagentName or behavior is null
     */
    void register(String subagentName, SubagentBehavior behavior);

    /**
     * Unregisters the behavior for the given subagent name.
     *
     * @param subagentName
     *            the subagent name (must not be null)
     * @return an Optional holding the removed behavior, or empty if none was registered under that name
     * @throws NullPointerException
     *             if subagentName is null
     */
    Optional<SubagentBehavior> unregister(String subagentName);
}
