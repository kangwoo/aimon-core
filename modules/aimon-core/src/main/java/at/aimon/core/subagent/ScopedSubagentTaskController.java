package at.aimon.core.subagent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.TaskQuery;

/**
 * A {@link SubagentTaskController} decorator that confines every control-plane operation to a single agent execution
 * context.
 *
 * <p>
 * Background task ids are globally unique, so a shared {@code BackgroundTaskStore} holds the tasks of every
 * concurrently-running agent in one flat keyspace. Distinguishing tasks by id alone never collides, but it does not
 * <em>isolate</em> agents: without scoping, one agent's {@code TaskList} would enumerate another agent's tasks and its
 * {@code TaskStop} could cancel them. This decorator closes that cross-agent visibility gap at the control plane by
 * forcing the bound {@code agentRuntimeId} into every {@link #list(TaskQuery) list} query and by rejecting
 * {@link #stop(String) stop} / {@link #status(String) status} for a task that belongs to a different context.
 *
 * <p>
 * Scoping is by {@link AgentRuntimeId} — the agent-scoped id ({@code agent:<name>[:discriminator]}) stamped on
 * each task when it is spawned. Two sessions of the <em>same</em> agent share a context id and therefore keep
 * seeing each other's background tasks, matching the agent-scoped lifetime of {@code AgentRuntime}: a task
 * survives {@code /clear} and stays visible to the same agent across sessions. To additionally partition by caller
 * identity in a multi-tenant deployment, combine this with an owner criterion via {@link TaskQuery.Builder#owner}.
 *
 * <p>
 * Because scoping keys off the task's stored {@code agentRuntimeId} (not the owning node), a same-context task running
 * on
 * another node in a scale-out deployment is still visible and stoppable, while a foreign-context task is denied on
 * every node.
 */
public final class ScopedSubagentTaskController implements SubagentTaskController {

    private final SubagentTaskController delegate;
    private final AgentRuntimeId agentRuntimeId;

    /**
     * Creates a controller confined to a single agent runtime.
     *
     * @param delegate
     *            the underlying control plane every operation is delegated to (must not be null)
     * @param agentRuntimeId
     *            the agent runtime every operation is confined to (must not be null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public ScopedSubagentTaskController(SubagentTaskController delegate, AgentRuntimeId agentRuntimeId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.agentRuntimeId = Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
    }

    /**
     * Wraps {@code delegate} so it is confined to {@code agentRuntimeId} when present, or returns {@code delegate}
     * unchanged
     * when it is empty.
     *
     * <p>
     * The empty case preserves legacy unscoped behavior for call paths that do not carry an agent runtime id (unit
     * tests, non-Orca embeddings). Production Orca wiring always populates the id, so the scoping guarantee holds where
     * it matters.
     *
     * @param delegate
     *            the underlying control plane (must not be null)
     * @param agentRuntimeId
     *            the context to confine to, or empty to pass through unscoped (the {@link Optional} itself must not be
     *            null)
     * @return a scoped controller, or {@code delegate} when {@code agentRuntimeId} is empty
     */
    public static SubagentTaskController scopeOrPassThrough(SubagentTaskController delegate,
            Optional<AgentRuntimeId> agentRuntimeId) {
        Objects.requireNonNull(delegate, "delegate cannot be null");
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId optional cannot be null");
        return agentRuntimeId.<SubagentTaskController>map(id -> new ScopedSubagentTaskController(delegate, id))
                .orElse(delegate);
    }

    @Override
    public List<BackgroundTask> list(TaskQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        return delegate.list(scoped(query));
    }

    @Override
    public Optional<BackgroundTask> status(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        return delegate.status(taskId).filter(this::ownedByScope);
    }

    @Override
    public boolean stop(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        // Authorize before acting: only stop a task that exists in this scope. A foreign-context or unknown task is
        // reported as not-stoppable (false) without ever reaching delegate.stop, so it cannot be cancelled from here.
        if (delegate.status(taskId).filter(this::ownedByScope).isEmpty()) {
            return false;
        }
        return delegate.stop(taskId);
    }

    /**
     * Rebuilds the query with the bound context id forced in, preserving any state / owner criteria the caller set. A
     * caller-supplied context id is overridden — a scoped controller never widens beyond its own context.
     */
    private TaskQuery scoped(TaskQuery query) {
        final TaskQuery.Builder builder = TaskQuery.builder().agentRuntimeId(agentRuntimeId);
        query.getState().ifPresent(builder::state);
        query.getOwner().ifPresent(builder::owner);
        return builder.build();
    }

    private boolean ownedByScope(BackgroundTask task) {
        return agentRuntimeId.equals(task.getAgentRuntimeId().orElse(null));
    }
}
