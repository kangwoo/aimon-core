package at.aimon.core.subagent.behavior;

import java.util.Optional;
import java.util.Set;

/**
 * Read-only registry mapping a subagent name to a {@link SubagentBehavior}.
 *
 * <p>
 * Composed alongside the data-side {@link at.aimon.core.subagent.SubagentRegistry}: when the execution manager resolves
 * a subagent name that has a registered behavior, it runs the behavior instead of the ReAct loop. A subagent name with
 * no registered behavior executes the unchanged data path, so this registry is purely additive and origin-agnostic
 * execution among data subagents is preserved.
 *
 * <p>
 * Separates reads from writes (CQRS): execution code depends on this interface; only bootstrap/registration code
 * depends
 * on {@link MutableSubagentBehaviorRegistry}.
 *
 * @see MutableSubagentBehaviorRegistry
 * @see InMemorySubagentBehaviorRegistry
 */
public interface SubagentBehaviorRegistry {

    /** Immutable, always-empty registry. Used as the safe default when no behaviors are configured. */
    SubagentBehaviorRegistry EMPTY = new SubagentBehaviorRegistry() {
        @Override
        public Optional<SubagentBehavior> getBehavior(String subagentName) {
            return Optional.empty();
        }

        @Override
        public Set<String> behaviorNames() {
            return Set.of();
        }
    };

    /**
     * Returns the immutable empty registry — the safe default when no code behaviors are wired.
     *
     * @return the empty registry (never null)
     */
    static SubagentBehaviorRegistry empty() {
        return EMPTY;
    }

    /**
     * Gets the behavior registered for the given subagent name.
     *
     * @param subagentName
     *            the subagent name (must not be null)
     * @return an Optional holding the behavior, or empty if none is registered (→ ReAct path)
     * @throws NullPointerException
     *             if subagentName is null
     */
    Optional<SubagentBehavior> getBehavior(String subagentName);

    /**
     * Returns the set of subagent names that have a registered behavior.
     *
     * @return an immutable set of names (never null, may be empty)
     */
    Set<String> behaviorNames();
}
