package at.aimon.core.subagent.task;

import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * Storage abstraction for a completed subagent's session snapshot, keyed by {@code taskId} (design §7).
 *
 * <p>
 * This is the multi-instance seam for <b>resume</b>: when a subagent finishes, its {@link SessionSnapshot}
 * (system prompt + full ReAct message history) is saved here under the run's {@code taskId}. A later {@code Task}
 * invocation with {@code resume=<taskId>} loads it and hands it to the executor as
 * {@code SubagentExecutionRequest.previousSnapshot}, so the subagent continues from the prior transcript rather
 * than
 * starting fresh. The snapshot namespace is the same {@code taskId} namespace used by {@link BackgroundTaskStore} and
 * {@link TaskOutputStore}, so a single task id addresses its metadata, its output log, and its resumable transcript.
 *
 * <p>
 * <b>Owner-tagged, immutable value, last-write-wins.</b> A snapshot is stored together with the name of the subagent
 * that produced it and the {@link AgentRuntimeId} that owns it (as a {@link ResumableSession}) so resume
 * can verify the caller is resuming the same subagent <em>and</em> confine the resume to the caller's own agent
 * context;
 * {@link SessionSnapshot} is an immutable value object, so implementations store the reference directly (no
 * defensive copy needed).
 * {@link #save(String, String, AgentRuntimeId, SessionSnapshot)} overwrites any previously stored
 * snapshot
 * for the same {@code taskId}; {@link #load(String)} returns the current owner-tagged snapshot or empty.
 *
 * <p>
 * <b>Bounded, in-memory default, shareable alternatives.</b> The default {@link InMemorySessionSnapshotStore}
 * keeps snapshots in a per-node, size-bounded map — resume works within a single JVM and long-running agent contexts
 * cannot accumulate transcripts without limit. A scale-out deployment supplies a shared/persistent implementation (a
 * VFS/session-backed store, ...) so a task started on one node can be resumed on another. Persisting a snapshot to
 * bytes
 * requires a hand-written {@code SessionSnapshot}/{@code Message} codec (the core message types are not
 * Jackson-ready); that codec ({@link at.aimon.core.subagent.task.codec.JsonSessionSnapshotCodec},
 * {@code FORMAT_VERSION=1}) and a VFS-backed store ({@link VfsSessionSnapshotStore}) are implemented. Per the
 * project's multi-instance rule, swapping the backend is an implementation change, not a refactoring.
 *
 * <p>
 * Implementations must be safe for concurrent access: the executor thread saving a terminal snapshot may race with a
 * parent agent loading it to resume.
 */
public interface SessionSnapshotStore {

    /**
     * Saves (or overwrites) the session snapshot for a task, tagged with the subagent that produced it and the
     * agent runtime that owns it.
     *
     * <p>
     * Best-effort persistence: implementations must not throw for routine backend errors — a failed save must never
     * abort the subagent whose transcript it is recording.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @param subagentName
     *            the name of the subagent that produced the snapshot (must not be null); resume compares this against
     *            the requested {@code subagent_name}
     * @param agentRuntimeId
     *            the agent runtime that produced the snapshot (nullable; absent leaves the transcript
     *            unscoped, so a scoped resume cannot confirm ownership and will decline it)
     * @param snapshot
     *            the session snapshot to persist (must not be null)
     */
    void save(String taskId, String subagentName, AgentRuntimeId agentRuntimeId, SessionSnapshot snapshot);

    /**
     * Saves (or overwrites) the session snapshot for a task with no owning agent runtime.
     *
     * <p>
     * Backward-compatible convenience that delegates to
     * {@link #save(String, String, AgentRuntimeId, SessionSnapshot)} with a {@code null} context id. A
     * transcript saved this way is unscoped, so a context-scoped resume cannot confirm ownership and will decline it —
     * production Orca wiring always supplies a context id via the four-argument overload.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @param subagentName
     *            the name of the subagent that produced the snapshot (must not be null)
     * @param snapshot
     *            the session snapshot to persist (must not be null)
     */
    default void save(String taskId, String subagentName, SessionSnapshot snapshot) {
        save(taskId, subagentName, null, snapshot);
    }

    /**
     * Loads the owner-tagged session snapshot previously saved for a task.
     *
     * <p>
     * The returned {@link ResumableSession} carries the owning {@link AgentRuntimeId} (when it was tagged
     * at save time) so a caller can confine a resume to its own context; {@code ScopedSessionSnapshotStore}
     * applies
     * that confinement.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @return the stored owner-tagged snapshot, or empty when no snapshot exists for the task (unknown/expired id)
     */
    Optional<ResumableSession> load(String taskId);

    /**
     * Discards the snapshot recorded for a task, releasing its storage.
     *
     * <p>
     * A no-op when the task is unknown. Best-effort: implementations must not throw for routine backend errors.
     *
     * @param taskId
     *            the task identifier (must not be null)
     */
    void evict(String taskId);
}
