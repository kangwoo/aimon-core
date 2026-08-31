package at.aimon.core.workflow.impl;

import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.StepResultCache;

/**
 * The run's resume binding: its {@link RunId}, owning agent-execution {@code agentRuntimeId} (nullable), and
 * {@link StepResultCache}. Bundled so {@link DefaultWorkflowContext}'s constructor stays within the parameter
 * limit and so the three resume inputs travel together. Immutable value object, package-private (impl detail).
 */
final class ResumeBinding {

    private final RunId runId;
    private final AgentRuntimeId agentRuntimeId;
    private final StepResultCache cache;

    ResumeBinding(RunId runId, AgentRuntimeId agentRuntimeId, StepResultCache cache) {
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.agentRuntimeId = agentRuntimeId;
        this.cache = cache != null ? cache : StepResultCache.NO_OP;
    }

    /** @return the run id (never null) */
    RunId runId() {
        return runId;
    }

    /** @return the owning agent-agent runtime id, or null when the run has none */
    AgentRuntimeId agentRuntimeId() {
        return agentRuntimeId;
    }

    /** @return the step cache (never null; {@link StepResultCache#NO_OP} when none was supplied) */
    StepResultCache cache() {
        return cache;
    }
}
