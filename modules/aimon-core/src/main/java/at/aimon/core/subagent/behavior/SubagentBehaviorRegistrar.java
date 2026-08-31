package at.aimon.core.subagent.behavior;

import java.util.Objects;

import at.aimon.core.subagent.MutableSubagentRegistry;
import at.aimon.core.subagent.Subagent;

/**
 * Convenience for registering a code-behavior subagent as a <b>pair</b> under one name: the {@link Subagent} data entry
 * (for discovery, description and the tool allow-list) into a {@link MutableSubagentRegistry}, and the
 * {@link SubagentBehavior} (for execution) into a {@link MutableSubagentBehaviorRegistry} — both keyed by
 * {@code subagent.getName()} so the two can never drift.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {
 *     &#64;code
 *     InMemorySubagentRegistry codeData = new InMemorySubagentRegistry();
 *     InMemorySubagentBehaviorRegistry codeBehavior = new InMemorySubagentBehaviorRegistry();
 *
 *     SubagentBehaviorRegistrar.register(
 *             Subagent.builder().name("clock").description("Returns the current server time.")
 *                     .systemPrompt("(code behavior)").build(),
 *             (ctx, req, support) -> support.success("Current server time: " + Instant.now()),
 *             codeData, codeBehavior);
 * }
 * </pre>
 */
public final class SubagentBehaviorRegistrar {

    private SubagentBehaviorRegistrar() {
    }

    /**
     * Registers the data entry and the behavior under {@code subagent.getName()}.
     *
     * @param subagent
     *            the data entry providing name, description and tool allow-list (must not be null)
     * @param behavior
     *            the execution behavior (must not be null)
     * @param dataRegistry
     *            the mutable data registry (must not be null)
     * @param behaviorRegistry
     *            the mutable behavior registry (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public static void register(Subagent subagent, SubagentBehavior behavior, MutableSubagentRegistry dataRegistry,
            MutableSubagentBehaviorRegistry behaviorRegistry) {
        Objects.requireNonNull(subagent, "subagent cannot be null");
        Objects.requireNonNull(behavior, "behavior cannot be null");
        Objects.requireNonNull(dataRegistry, "dataRegistry cannot be null");
        Objects.requireNonNull(behaviorRegistry, "behaviorRegistry cannot be null");
        dataRegistry.register(subagent);
        behaviorRegistry.register(subagent.getName(), behavior);
    }
}
