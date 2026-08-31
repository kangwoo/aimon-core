package at.aimon.core.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for background workflow run metadata ({@link WorkflowRun} snapshots).
 *
 * <p>
 * Run-scoped analog of {@code at.aimon.core.subagent.task.BackgroundTaskStore} — the multi-instance seam for detached
 * {@link WorkflowScript} execution. The default {@code InMemoryRunStore} keeps runs in a per-node map; a scale-out
 * deployment supplies a shared implementation (Redis, a relational table, ...) so that run listing and status queries
 * observe runs submitted on <em>any</em> node. Per the project's multi-instance rule, swapping the backend is an
 * implementation change, not a refactoring.
 *
 * <p>
 * Implementations must be safe for concurrent access from multiple worker threads.
 */
public interface RunStore {

    /**
     * Inserts or replaces the snapshot for a run (unconditional).
     *
     * @param run
     *            the run snapshot to persist (must not be null)
     */
    void put(WorkflowRun run);

    /**
     * Atomically inserts {@code run} <b>only if</b> no run with the same {@link WorkflowRun#getRunId() id} exists,
     * or the existing one is in a {@link WorkflowRunState#isTerminal() terminal} state. This is the idempotency
     * guard for background submission (design §5.1): re-submitting a {@link RunId} whose prior run is still
     * PENDING/RUNNING is refused (returns {@code false}), so the runner returns the existing run instead of dispatching
     * a duplicate worker. A terminal (or absent) prior run may be (re)submitted.
     *
     * <p>
     * Unlike a non-atomic {@code find}-then-{@code put}, this closes the check-then-act race: two concurrent
     * submissions of the same fresh id can never both insert.
     *
     * @param run
     *            the run snapshot to insert (must not be null; typically {@link WorkflowRunState#PENDING})
     * @return {@code true} if {@code run} was inserted (caller should dispatch); {@code false} if a non-terminal run
     *         already exists for that id (caller should return the existing run / reject)
     */
    boolean putIfAbsentOrTerminal(WorkflowRun run);

    /**
     * Looks up a run by id.
     *
     * @param runId
     *            the run identifier (must not be null)
     * @return the run snapshot, or empty if unknown
     */
    Optional<WorkflowRun> find(RunId runId);

    /**
     * Lists runs matching the given query.
     *
     * @param query
     *            the filter (must not be null; use {@link RunQuery#all()} for no filtering)
     * @return a snapshot list of matching runs (never null; ordering is implementation-defined)
     */
    List<WorkflowRun> list(RunQuery query);

    /**
     * Atomically transitions a run to a new state, guarding against transitions out of a terminal state.
     *
     * <p>
     * <b>Guard-and-return semantics:</b> if the run is unknown, or is already in a
     * {@link WorkflowRunState#isTerminal() terminal} state, this is a no-op and returns {@link Optional#empty()} —
     * making completion notification and duplicate {@code stop} requests idempotent. Otherwise the run's state is set
     * to {@code to}; when {@code to} is terminal, the implementation stamps the end time. The updated snapshot is
     * returned.
     *
     * @param runId
     *            the run identifier (must not be null)
     * @param to
     *            the target state (must not be null)
     * @return the updated snapshot, or empty if the transition was rejected (unknown run or already terminal)
     */
    Optional<WorkflowRun> transition(RunId runId, WorkflowRunState to);

    /**
     * Renews a run's lease heartbeat, guarding against renewing a terminal run.
     *
     * <p>
     * <b>Guard-and-return semantics:</b> if the run is unknown, or is already terminal, this is a no-op and returns
     * {@link Optional#empty()}. Otherwise the run's {@link WorkflowRun#getLastHeartbeat() last heartbeat} is set
     * to
     * {@code at} and the updated snapshot is returned. Only the heartbeat is evolved. The terminal guard mirrors
     * {@link #transition} so a heartbeat can never resurrect a run that completed or was stopped concurrently.
     *
     * @param runId
     *            the run identifier (must not be null)
     * @param at
     *            the heartbeat instant to record (must not be null)
     * @return the updated snapshot, or empty if the renewal was rejected (unknown run or already terminal)
     */
    Optional<WorkflowRun> heartbeat(RunId runId, Instant at);

    /**
     * Removes a run's metadata.
     *
     * @param runId
     *            the run identifier (must not be null)
     */
    void remove(RunId runId);
}
