package at.aimon.core.workflow;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Deterministic cache key for one {@code agent()} step within an workflow run (design §5.3).
 *
 * <p>
 * A step key is {@code runId + owning agentRuntimeId + structural step-path}. The <b>structural step-path</b> is a
 * program-order coordinate that is stable across re-executions regardless of fan-out scheduling: each path level's
 * owning thread assigns a program-order construct ordinal ({@code agent}&rarr;{@code a<n>}, {@code parallel}&rarr;
 * {@code p<n>}, {@code pipeline}&rarr;{@code q<n>}), and a fan-out child additionally carries its list index. So
 * sibling
 * constructs never collide (distinct ordinals) and identical-input parallel branches never collide (distinct list
 * indices). This key holds that path opaquely as a string; the path is <em>built</em> by the run context
 * ({@code DefaultWorkflowContext}) — this type only composes and compares it.
 *
 * <p>
 * The owning {@link AgentRuntimeId} is part of the key so a shared cache backend isolates agents structurally
 * (a foreign context yields a different key), reinforced by {@code ScopedStepResultCache}. Immutable; safe as a map
 * key.
 */
public final class StepKey {

    private static final String NO_CONTEXT = "-";

    private final RunId runId;
    private final AgentRuntimeId agentRuntimeId;
    private final String path;
    private final String value;

    private StepKey(RunId runId, AgentRuntimeId agentRuntimeId, String path) {
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.agentRuntimeId = agentRuntimeId;
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("structural step-path cannot be null or blank");
        }
        this.path = path;
        this.value = runId.value() + "/" + (agentRuntimeId == null ? NO_CONTEXT : agentRuntimeId.value()) + "/" + path;
    }

    /**
     * Composes a step key.
     *
     * @param runId
     *            the owning run (must not be null)
     * @param agentRuntimeId
     *            the owning agent runtime, or null when the run has none (embeddings / tests)
     * @param path
     *            the structural step-path (must not be null or blank; see the class javadoc for its grammar)
     * @return a new step key
     */
    public static StepKey of(RunId runId, AgentRuntimeId agentRuntimeId, String path) {
        return new StepKey(runId, agentRuntimeId, path);
    }

    /**
     * @return the owning run id (never null)
     */
    public RunId runId() {
        return runId;
    }

    /**
     * @return the owning agent runtime, or empty when the run has none
     */
    public Optional<AgentRuntimeId> agentRuntimeId() {
        return Optional.ofNullable(agentRuntimeId);
    }

    /**
     * @return the structural step-path segment string (never null or blank)
     */
    public String path() {
        return path;
    }

    /**
     * @return the canonical composite value {@code <runId>/<agentRuntimeId|->/<path>}, suitable as a map key or
     *         cache-file
     *         name (never null or blank)
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final StepKey that = (StepKey) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
