package at.aimon.core.memory.dreamer;

import at.aimon.core.memory.Workspace;

/**
 * Long-lived service that consolidates a workspace's memory in one cycle.
 *
 * <p>
 * Per design doc §6.3, the engine is the orchestration layer above
 * {@link ConsolidationStrategy}: it walks every {@link at.aimon.core.memory.PeerView
 * subject} that has observations in the workspace, runs the strategy to build a
 * {@link ConsolidationPlan}, and applies it. The engine is invoked from a
 * Quartz job ({@code DreamerJob} in {@code aimon-scheduling-quartz}) on a
 * cron cadence — the design's "long-lived" component lives here, not in
 * {@code AgentRuntime}.
 *
 * <p>
 * Implementations must be safe for concurrent use; the scheduler may fire
 * multiple workspaces in parallel.
 */
public interface DreamerEngine {

    /**
     * Consolidates the memory of every subject in {@code workspace}.
     *
     * <p>
     * One bad subject (failed LLM call, transient store error) must not stall
     * the cycle: implementations are expected to log and continue. The returned
     * {@link DreamerCycleSummary} captures aggregate counts for telemetry.
     *
     * @param workspace
     *            tenant scope (must not be null)
     * @return cycle metrics (subjects walked, clusters merged, errors)
     */
    DreamerCycleSummary consolidate(Workspace workspace);
}
