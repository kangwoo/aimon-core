package at.aimon.core.subagent.task;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * A {@link SessionSnapshotStore} decorator that confines {@link #load(String) resume loads} to a single agent
 * runtime.
 *
 * <p>
 * Background task ids are globally unique, so a shared {@link SessionSnapshotStore} holds the resumable
 * transcripts
 * of every concurrently-running agent in one flat keyspace. Addressing a transcript by id alone never collides, but it
 * does not <em>isolate</em> agents: without scoping, one agent could {@code Task(resume=<taskId>)} another agent's
 * transcript merely by knowing its id, grafting the foreign agent's full ReAct history (and whatever it contains) into
 * its own execution. This decorator closes that cross-agent read/hijack gap by returning a loaded transcript only when
 * its recorded {@code agentRuntimeId} equals the bound context; a transcript owned by another context — or one with no
 * recorded context (unverifiable ownership) — loads as {@link Optional#empty()}, exactly as an unknown id would.
 *
 * <p>
 * This mirrors {@code ScopedSubagentTaskController} on the control plane: both key isolation off the task's stored
 * {@link AgentRuntimeId} (the agent-scoped {@code agent:<name>[:discriminator]} id), so two sessions of
 * the <em>same</em> agent still resume each other's transcripts (matching the agent-scoped lifetime of
 * {@code AgentRuntime}), while a foreign agent is denied on every node in a scale-out deployment.
 *
 * <p>
 * {@link #save(String, String, AgentRuntimeId, SessionSnapshot) save} and {@link #evict(String) evict}
 * are delegated unchanged: the owning executor tags a transcript with its own context at save time, and resume — the
 * only cross-agent read path — is the operation that needs confining.
 */
public final class ScopedSessionSnapshotStore implements SessionSnapshotStore {

    private final SessionSnapshotStore delegate;
    private final AgentRuntimeId agentRuntimeId;

    /**
     * Creates a snapshot store whose loads are confined to a single agent runtime.
     *
     * @param delegate
     *            the underlying snapshot store every operation is delegated to (must not be null)
     * @param agentRuntimeId
     *            the agent runtime loads are confined to (must not be null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public ScopedSessionSnapshotStore(SessionSnapshotStore delegate, AgentRuntimeId agentRuntimeId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.agentRuntimeId = Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
    }

    /**
     * Wraps {@code delegate} so its loads are confined to {@code agentRuntimeId} when present, or returns
     * {@code delegate}
     * unchanged when it is empty.
     *
     * <p>
     * The empty case preserves legacy unscoped behavior for call paths that do not carry an agent runtime id (unit
     * tests, non-Orca embeddings). Production Orca wiring always populates the id, so the scoping guarantee holds where
     * it matters.
     *
     * @param delegate
     *            the underlying snapshot store (must not be null)
     * @param agentRuntimeId
     *            the context to confine to, or empty to pass through unscoped (the {@link Optional} itself must not be
     *            null)
     * @return a scoped store, or {@code delegate} when {@code agentRuntimeId} is empty
     */
    public static SessionSnapshotStore scopeOrPassThrough(SessionSnapshotStore delegate,
            Optional<AgentRuntimeId> agentRuntimeId) {
        Objects.requireNonNull(delegate, "delegate cannot be null");
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId optional cannot be null");
        return agentRuntimeId.<SessionSnapshotStore>map(id -> new ScopedSessionSnapshotStore(delegate, id))
                .orElse(delegate);
    }

    @Override
    public void save(String taskId, String subagentName, AgentRuntimeId agentRuntimeId, SessionSnapshot snapshot) {
        delegate.save(taskId, subagentName, agentRuntimeId, snapshot);
    }

    @Override
    public Optional<ResumableSession> load(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        // Authorize the resume: surface the transcript only when it was recorded by this same context. A
        // foreign-context
        // or untagged (unverifiable) transcript is hidden as an unknown id, so a caller can never read or continue it.
        return delegate.load(taskId).filter(this::ownedByScope);
    }

    @Override
    public void evict(String taskId) {
        delegate.evict(taskId);
    }

    private boolean ownedByScope(ResumableSession session) {
        return agentRuntimeId.equals(session.getAgentRuntimeId().orElse(null));
    }
}
