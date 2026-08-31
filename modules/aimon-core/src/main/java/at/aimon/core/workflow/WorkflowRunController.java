package at.aimon.core.workflow;

import java.util.List;
import java.util.Optional;

/**
 * Store-backed control plane for background workflow runs (design §5.1): stop a run, and list/query run
 * state.
 *
 * <p>
 * {@link #list}/{@link #status} read the {@code RunStore}, so they observe runs across a scale-out deployment.
 * {@link #stop} is cooperative and node-local in effect: it trips the owning node's run coordinator so the run's
 * in-flight fan-out subagents observe the stop (via their per-run environment's cancellation signal) and unwind, after
 * which the run settles as {@link WorkflowRunState#KILLED}.
 */
public interface WorkflowRunController {

    /**
     * Requests cooperative cancellation of a run. On the owning node this trips the run's coordinator so its in-flight
     * subagents observe the stop and unwind; on a non-owning node it is a no-op for now (cross-node stop is a later
     * enhancement). Idempotent.
     *
     * @param runId
     *            the run to stop (must not be null)
     * @return {@code true} if a live run for {@code runId} was found on this node and signalled; {@code false}
     *         otherwise
     */
    boolean stop(RunId runId);

    /**
     * Lists runs matching the query from the {@code RunStore}.
     *
     * @param query
     *            the filter (must not be null; use {@link RunQuery#all()} for no filtering)
     * @return the matching run snapshots (never null; ordering is implementation-defined)
     */
    List<WorkflowRun> list(RunQuery query);

    /**
     * Looks up a run's current metadata snapshot from the {@code RunStore}.
     *
     * @param runId
     *            the run id (must not be null)
     * @return the run snapshot, or empty if unknown
     */
    Optional<WorkflowRun> status(RunId runId);
}
