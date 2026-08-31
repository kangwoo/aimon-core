package at.aimon.core.skill.policy.pending;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;

/**
 * Immutable snapshot of an agent turn that was suspended awaiting user approval for one or more skill invocations.
 *
 * <p>
 * SK-11 follows an "atomic suspension" rule (B3-mem): when at least one tool_use in a turn maps to a
 * {@link at.aimon.core.skill.policy.SkillInvocationDecision#ASK} decision, the entire turn is suspended without
 * writing the assistant message or any tool_result back to {@code TranscriptBuffer}. On resume, the agent re-issues
 * the LLM call from the same memory state; the pre-flight scan finds the now-cached decisions and lets the turn
 * proceed.
 *
 * <p>
 * Because nothing is half-written to memory, this snapshot does not need to carry the LLM assistant message — it only
 * carries enough metadata for the approval UI ({@code /pending}) and the timeout reaper:
 * <ul>
 * <li>{@link #getId()} — opaque id the user references in {@code /approve <id>} / {@code /deny <id>}.</li>
 * <li>{@link #getAgentRuntimeId()} — owning agent runtime (used for scoping listings and invalidation).</li>
 * <li>{@link #getSessionId()} — the session the suspended turn belongs to. Optional at the type level only: every
 * entry the framework itself registers carries one, for the reason the accessor gives.</li>
 * <li>{@link #getPendingSkills()} — skills awaiting approval at suspend time, for display only. Resume always
 * re-derives the actual list from the live LLM response.</li>
 * <li>{@link #getCreatedAt()} / {@link #getExpiresAt()} — TTL for the timeout reaper.</li>
 * </ul>
 */
public final class PendingTurn {

    private final PendingTurnId id;
    private final AgentRuntimeId agentRuntimeId;
    private final SessionId sessionId;
    private final List<PendingSkillRequest> pendingSkills;
    private final Instant createdAt;
    private final Instant expiresAt;

    private PendingTurn(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id cannot be null");
        this.agentRuntimeId = Objects.requireNonNull(builder.agentRuntimeId, "agentRuntimeId cannot be null");
        this.sessionId = builder.sessionId;
        Objects.requireNonNull(builder.pendingSkills, "pendingSkills cannot be null");
        if (builder.pendingSkills.isEmpty()) {
            throw new IllegalArgumentException("pendingSkills cannot be empty");
        }
        this.pendingSkills = List.copyOf(builder.pendingSkills);
        this.createdAt = Objects.requireNonNull(builder.createdAt, "createdAt cannot be null");
        this.expiresAt = Objects.requireNonNull(builder.expiresAt, "expiresAt cannot be null");
        if (expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be >= createdAt");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public PendingTurnId getId() {
        return id;
    }

    public AgentRuntimeId getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /**
     * The session this turn was suspended in.
     *
     * <p>
     * Present on every entry this framework registers. Suspending is a step of the ReAct loop, so only a session's
     * turn ever reaches it, and the one registrar — {@code OrcaAgentExecutor}'s suspend path — takes the id from the
     * {@code TranscriptBuffer} it is running against, which rejects a null one. The session-less executions do not
     * arrive here at all: a subagent fork cannot prompt, so it is never suspended and inherits the spawning session's
     * decision through {@code invokingSessionId} instead; a scheduled routine invokes its tools directly and runs no
     * loop to suspend, so a skill it is not cleared for is denied by the rule tail's {@code ASK} rather than held for
     * an answer nobody is waiting to give.
     *
     * <p>
     * {@code Optional} is still the honest return type, because the builder does not require the id: an embedder that
     * registers entries of its own may omit it. Approval commands must keep honouring that — with no session there is
     * no store narrower than the agent, so a session-scoped write widens to agent scope rather than being dropped.
     */
    public Optional<SessionId> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    public List<PendingSkillRequest> getPendingSkills() {
        return pendingSkills;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * Returns true if this turn's expiry instant is strictly before {@code now}.
     */
    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now cannot be null");
        return expiresAt.isBefore(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PendingTurn that = (PendingTurn) o;
        return id.equals(that.id) && agentRuntimeId.equals(that.agentRuntimeId)
                && Objects.equals(sessionId, that.sessionId) && pendingSkills.equals(that.pendingSkills)
                && createdAt.equals(that.createdAt) && expiresAt.equals(that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, agentRuntimeId, sessionId, pendingSkills, createdAt, expiresAt);
    }

    @Override
    public String toString() {
        return "PendingTurn{id=" + id + ", agentRuntimeId=" + agentRuntimeId + ", sessionId=" + sessionId
                + ", pendingSkills=" + pendingSkills.size() + ", expiresAt=" + expiresAt + '}';
    }

    /** Builder for {@link PendingTurn}. */
    public static final class Builder {

        private PendingTurnId id;
        private AgentRuntimeId agentRuntimeId;
        private SessionId sessionId;
        private List<PendingSkillRequest> pendingSkills;
        private Instant createdAt;
        private Instant expiresAt;

        private Builder() {
        }

        public Builder id(PendingTurnId id) {
            this.id = id;
            return this;
        }

        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /** Nullable — see {@link PendingTurn#getSessionId()}. */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder pendingSkills(List<PendingSkillRequest> pendingSkills) {
            this.pendingSkills = pendingSkills;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /**
         * Convenience setter that derives {@link #expiresAt(Instant)} from {@link #createdAt(Instant)} plus a TTL.
         *
         * @throws IllegalStateException
         *             if {@link #createdAt(Instant)} has not been called yet
         */
        public Builder ttl(Duration ttl) {
            Objects.requireNonNull(ttl, "ttl cannot be null");
            if (createdAt == null) {
                throw new IllegalStateException("createdAt must be set before ttl()");
            }
            this.expiresAt = createdAt.plus(ttl);
            return this;
        }

        public PendingTurn build() {
            return new PendingTurn(this);
        }
    }
}
