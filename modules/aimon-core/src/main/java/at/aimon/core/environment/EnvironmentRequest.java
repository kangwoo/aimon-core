package at.aimon.core.environment;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * What an {@link ExecutionEnvironmentProvider} is told about the execution it resolves an environment for.
 *
 * <p>
 * Only {@link #agentRuntimeId()} is required. A main turn and a scheduled routine carry the {@link Agent}; forks and
 * workflow runs do not (they know their runtime id and their {@link #parent()} instead). A fork carries its parent's
 * environment so a provider can answer with the same place — the local provider returns the parent as-is — and its
 * own {@link #fork() definition}, so a provider can answer with a different place per subagent instead.
 */
public final class EnvironmentRequest {

    private final AgentRuntimeId agentRuntimeId;
    private final Agent agent;
    private final SessionId sessionId;
    private final ExecutionId executionId;
    private final SessionId invokingSessionId;
    private final Principal principal;
    private final ExecutionEnvironment parent;
    private final String branchKey;
    private final ForkDefinition fork;

    private EnvironmentRequest(Builder builder) {
        this.agentRuntimeId = Objects.requireNonNull(builder.agentRuntimeId, "agentRuntimeId must not be null");
        this.agent = builder.agent;
        this.sessionId = builder.sessionId;
        this.executionId = builder.executionId;
        this.invokingSessionId = builder.invokingSessionId;
        this.principal = builder.principal;
        this.parent = builder.parent;
        this.branchKey = builder.branchKey;
        this.fork = builder.fork;
    }

    /** @return the agent runtime the execution belongs to */
    public AgentRuntimeId agentRuntimeId() {
        return agentRuntimeId;
    }

    /** @return the agent, when the executor has one (main turns, routines) */
    public Optional<Agent> agent() {
        return Optional.ofNullable(agent);
    }

    /** @return the session a main turn runs for */
    public Optional<SessionId> sessionId() {
        return Optional.ofNullable(sessionId);
    }

    /** @return the execution id of a session-less execution (fork, routine) */
    public Optional<ExecutionId> executionId() {
        return Optional.ofNullable(executionId);
    }

    /** @return the session a fork acts on behalf of */
    public Optional<SessionId> invokingSessionId() {
        return Optional.ofNullable(invokingSessionId);
    }

    /** @return the principal the execution runs as */
    public Optional<Principal> principal() {
        return Optional.ofNullable(principal);
    }

    /** @return the parent execution's environment (forks) */
    public Optional<ExecutionEnvironment> parent() {
        return Optional.ofNullable(parent);
    }

    /** @return the branch key, when the request is for an isolated branch */
    public Optional<String> branchKey() {
        return Optional.ofNullable(branchKey);
    }

    /**
     * @return the subagent definition a fork runs — its name and attributes — when the request is for a fork (design
     *         §5.2)
     */
    public Optional<ForkDefinition> fork() {
        return Optional.ofNullable(fork);
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "EnvironmentRequest{agentRuntimeId=" + agentRuntimeId + ", sessionId=" + sessionId + ", executionId="
                + executionId + ", parent=" + (parent != null) + ", branchKey=" + branchKey
                + (fork != null ? ", fork=" + fork.name() : "") + '}';
    }

    /** Builder for {@link EnvironmentRequest}. */
    public static final class Builder {
        private AgentRuntimeId agentRuntimeId;
        private Agent agent;
        private SessionId sessionId;
        private ExecutionId executionId;
        private SessionId invokingSessionId;
        private Principal principal;
        private ExecutionEnvironment parent;
        private String branchKey;
        private ForkDefinition fork;

        private Builder() {
        }

        /**
         * @param agentRuntimeId
         *            the runtime id (required)
         * @return this builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * @param agent
         *            the agent, or null
         * @return this builder
         */
        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        /**
         * @param sessionId
         *            the session id, or null
         * @return this builder
         */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * @param executionId
         *            the execution id, or null
         * @return this builder
         */
        public Builder executionId(ExecutionId executionId) {
            this.executionId = executionId;
            return this;
        }

        /**
         * @param invokingSessionId
         *            the invoking session id, or null
         * @return this builder
         */
        public Builder invokingSessionId(SessionId invokingSessionId) {
            this.invokingSessionId = invokingSessionId;
            return this;
        }

        /**
         * @param principal
         *            the principal, or null
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * @param parent
         *            the parent environment, or null
         * @return this builder
         */
        public Builder parent(ExecutionEnvironment parent) {
            this.parent = parent;
            return this;
        }

        /**
         * @param branchKey
         *            the branch key, or null
         * @return this builder
         */
        public Builder branchKey(String branchKey) {
            this.branchKey = branchKey;
            return this;
        }

        /**
         * @param fork
         *            the subagent definition the fork runs, or null
         * @return this builder
         */
        public Builder fork(ForkDefinition fork) {
            this.fork = fork;
            return this;
        }

        /** @return the request */
        public EnvironmentRequest build() {
            return new EnvironmentRequest(this);
        }
    }
}
