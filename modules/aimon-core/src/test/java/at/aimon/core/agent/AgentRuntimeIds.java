package at.aimon.core.agent;

/**
 * Test-only factory helpers for {@link AgentRuntimeId}.
 *
 * <p>
 * The production factory {@link AgentRuntimeId#of(String)} requires the {@code agent:} prefix; tests often
 * want to fabricate ids from short symbolic names like {@code "ctx-1"}. This helper centralizes the prefix so
 * production code stays strict while tests stay readable.
 */
public final class AgentRuntimeIds {

    private AgentRuntimeIds() {
        // utility
    }

    /**
     * Build an {@link AgentRuntimeId} for tests from a short symbolic name. Equivalent to
     * {@code AgentRuntimeId.of("agent:" + name)}.
     */
    public static AgentRuntimeId testCtx(String name) {
        return AgentRuntimeId.of("agent:" + name);
    }
}
