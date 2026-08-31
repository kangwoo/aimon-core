package at.aimon.core.subagent.task;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * A resumable subagent transcript together with the identity of the subagent that produced it and the agent execution
 * context that owns it (design §7).
 *
 * <p>
 * {@link SessionSnapshotStore} stores this instead of a bare {@link SessionSnapshot} for two reasons:
 *
 * <ul>
 * <li><b>Subagent tag.</b> A later {@code Task(resume=<taskId>)} can verify the caller is resuming the <em>same</em>
 * subagent that recorded the transcript. The snapshot itself carries no subagent identity (its system prompt is
 * overwritten with the resuming subagent's prompt on rehydration), so without this tag a mismatched
 * {@code subagent_name} would silently graft one subagent's history under another's system prompt and tool allowlist.
 * <li><b>Owning context.</b> Background task ids are globally unique, so a shared {@link SessionSnapshotStore}
 * holds the transcripts of every concurrently-running agent in one flat keyspace. The {@code agentRuntimeId} records
 * which
 * {@link AgentRuntimeId} produced the transcript so a resume can be confined to the caller's own context — one
 * agent must not be able to resume (and thereby read + hijack) another agent's transcript merely by knowing its task
 * id.
 * Scoping is enforced by {@code ScopedSessionSnapshotStore}.
 * </ul>
 *
 * <p>
 * Immutable value object: {@code subagentName} and {@code snapshot} are non-null; {@code agentRuntimeId} is optional
 * (absent
 * for transcripts recorded by a non-Orca path, or by an older store version that predates context tagging). There are
 * no setters.
 */
public final class ResumableSession {

    private final String subagentName;
    private final AgentRuntimeId agentRuntimeId;
    private final SessionSnapshot snapshot;

    private ResumableSession(String subagentName, AgentRuntimeId agentRuntimeId, SessionSnapshot snapshot) {
        this.subagentName = Objects.requireNonNull(subagentName, "subagentName cannot be null");
        this.agentRuntimeId = agentRuntimeId;
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot cannot be null");
    }

    /**
     * Creates a resumable session binding a snapshot to the subagent that produced it, with no owning execution
     * context (the transcript is not confined to any agent context — see {@link #of(String, AgentRuntimeId,
     * SessionSnapshot)} to tag one).
     *
     * @param subagentName
     *            the name of the subagent that recorded the transcript (must not be null)
     * @param snapshot
     *            the session snapshot to persist (must not be null)
     * @return a new {@link ResumableSession} with an absent context id
     */
    public static ResumableSession of(String subagentName, SessionSnapshot snapshot) {
        return new ResumableSession(subagentName, null, snapshot);
    }

    /**
     * Creates a resumable session binding a snapshot to the subagent that produced it and the agent execution
     * context that owns it.
     *
     * @param subagentName
     *            the name of the subagent that recorded the transcript (must not be null)
     * @param agentRuntimeId
     *            the agent runtime that produced the transcript (nullable; absent leaves the transcript
     *            unscoped)
     * @param snapshot
     *            the session snapshot to persist (must not be null)
     * @return a new {@link ResumableSession}
     */
    public static ResumableSession of(String subagentName, AgentRuntimeId agentRuntimeId, SessionSnapshot snapshot) {
        return new ResumableSession(subagentName, agentRuntimeId, snapshot);
    }

    /**
     * Returns the name of the subagent that produced this transcript.
     *
     * @return the owning subagent's name (never null)
     */
    public String getSubagentName() {
        return subagentName;
    }

    /**
     * Returns the agent runtime that produced this transcript, when known.
     *
     * @return the owning context id, or empty when the transcript was recorded without one
     */
    public Optional<AgentRuntimeId> getAgentRuntimeId() {
        return Optional.ofNullable(agentRuntimeId);
    }

    /**
     * Returns the resumable session snapshot.
     *
     * @return the session snapshot (never null)
     */
    public SessionSnapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResumableSession other)) {
            return false;
        }
        return subagentName.equals(other.subagentName) && Objects.equals(agentRuntimeId, other.agentRuntimeId)
                && snapshot.equals(other.snapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subagentName, agentRuntimeId, snapshot);
    }

    @Override
    public String toString() {
        return "ResumableSession{subagentName='" + subagentName + "', agentRuntimeId=" + agentRuntimeId + ", snapshot="
                + snapshot + "}";
    }
}
