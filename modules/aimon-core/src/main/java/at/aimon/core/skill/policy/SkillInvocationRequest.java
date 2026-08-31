package at.aimon.core.skill.policy;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.skill.Skill;

/**
 * Input to {@link SkillInvocationPolicy#check(SkillInvocationRequest)}.
 *
 * <p>
 * Carries everything a policy needs to decide whether a given skill invocation is allowed/denied/needs-approval, in an
 * immutable value object. The {@link Skill} is required — policies that want to apply safe-by-default rules (e.g.
 * "INLINE skills with no hooks → ALLOW") need to inspect skill metadata.
 *
 * <p>
 * {@code agentRuntimeId}, {@code sessionId}, {@code invokingSessionId} and {@code principal} are all
 * optional: callers running outside an interactive REPL (scheduled tasks, one-shot batch agents) may not have any of
 * them, and policies must tolerate their absence.
 *
 * <p>
 * The identifiers differ along <b>two independent axes</b>, and a policy that caches decisions must pick deliberately:
 *
 * <ul>
 * <li><b>Lifetime</b> — {@code agentRuntimeId} is agent-scoped and survives across every session of the same
 * agent, whereas {@code sessionId} is session-scoped and is the narrower, safer default for remembering a
 * user's approval.
 * <li><b>Reach</b> — {@code sessionId} is the caller's own session; {@code invokingSessionId} is the
 * session that spawned the caller. A subagent fork has only the second: a fork is not a session's turn, so it has no
 * session id at all, and a decision the user gave is findable only under the invoking one.
 * </ul>
 *
 * See {@code docs/design/skill/approval-scope.md}.
 */
public final class SkillInvocationRequest {

    private final Skill skill;
    private final String args;
    private final AgentRuntimeId agentRuntimeId;
    private final SessionId sessionId;
    private final SessionId invokingSessionId;
    private final Principal principal;

    private SkillInvocationRequest(Builder builder) {
        skill = Objects.requireNonNull(builder.skill, "Skill cannot be null");
        args = builder.args == null ? "" : builder.args;
        agentRuntimeId = builder.agentRuntimeId;
        sessionId = builder.sessionId;
        invokingSessionId = builder.invokingSessionId;
        principal = builder.principal;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Skill getSkill() {
        return skill;
    }

    /** The {@code args} string forwarded by the LLM (never null; empty string when absent). */
    public String getArgs() {
        return args;
    }

    public Optional<AgentRuntimeId> getAgentRuntimeId() {
        return Optional.ofNullable(agentRuntimeId);
    }

    /**
     * The session this invocation belongs to, when the caller has one.
     *
     * <p>
     * Empty for callers that run without a session of their own — scheduled tasks among them, and <b>every subagent
     * fork</b>: a fork is not a session's turn, so nothing publishes a session id to it and this is empty on the whole
     * forked path. Never {@code orElseThrow()} it; for a fork only {@link #getInvokingSessionId()} answers. Policies
     * must fall back to that, to a broader scope, or to their own rule evaluation rather than failing when it is
     * absent.
     */
    public Optional<SessionId> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * The session whose turn spawned this invocation, when there is one.
     *
     * <p>
     * Set for runs a session started on the user's behalf (subagent forks, skill forks, foreground workflows).
     * Empty on the main-agent path — the main agent <em>is</em> the session, so {@link #getSessionId()}
     * already answers the question — and empty for system-initiated runs, which nobody asked for and which therefore
     * inherit nothing.
     */
    public Optional<SessionId> getInvokingSessionId() {
        return Optional.ofNullable(invokingSessionId);
    }

    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SkillInvocationRequest that)) {
            return false;
        }
        return skill.equals(that.skill) && args.equals(that.args) && Objects.equals(agentRuntimeId, that.agentRuntimeId)
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(invokingSessionId, that.invokingSessionId)
                && Objects.equals(principal, that.principal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(skill, args, agentRuntimeId, sessionId, invokingSessionId, principal);
    }

    @Override
    public String toString() {
        return "SkillInvocationRequest{skill=" + skill.getName() + ", argsLen=" + args.length() + ", agentRuntimeId="
                + agentRuntimeId + ", sessionId=" + sessionId + ", invokingSessionId=" + invokingSessionId
                + ", principal=" + principal + '}';
    }

    /** Builder for {@link SkillInvocationRequest}. */
    public static final class Builder {
        private Skill skill;
        private String args;
        private AgentRuntimeId agentRuntimeId;
        private SessionId sessionId;
        private SessionId invokingSessionId;
        private Principal principal;

        private Builder() {
        }

        public Builder skill(Skill skill) {
            this.skill = skill;
            return this;
        }

        public Builder args(String args) {
            this.args = args;
            return this;
        }

        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /** Nullable — see {@link SkillInvocationRequest#getSessionId()}. */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** Nullable — see {@link SkillInvocationRequest#getInvokingSessionId()}. */
        public Builder invokingSessionId(SessionId invokingSessionId) {
            this.invokingSessionId = invokingSessionId;
            return this;
        }

        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        public SkillInvocationRequest build() {
            return new SkillInvocationRequest(this);
        }
    }
}
