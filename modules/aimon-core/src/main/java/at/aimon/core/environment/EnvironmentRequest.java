package at.aimon.core.environment;

import java.util.Map;
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
 *
 * <p>
 * Two attribute maps describe what the execution asks for. {@link #definitionAttributes()} comes from the agent or
 * fork <em>definition</em> and is the same for every run of it; {@link #requestAttributes()} comes from the caller of
 * <em>this</em> run — the {@code environment.*} execution attributes of a main turn or a scheduled routine (issue
 * #252). A provider can layer the second over the first however its policy says.
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
    private final Map<String, String> requestAttributes;

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
        this.requestAttributes = builder.requestAttributes;
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

    /**
     * Returns the attributes of the definition this execution runs: the fork's subagent when the request is for a fork,
     * otherwise the agent's, otherwise none. The one lookup every provider's binding policy needs — a provider that
     * read only {@link #agent()} would place every fork where its main turn runs, and nothing would fail.
     *
     * @return the attributes (never null; unmodifiable; empty when neither is present)
     */
    public Map<String, String> definitionAttributes() {
        if (fork != null) {
            return fork.attributes();
        }
        return agent != null ? agent.getAttributes() : Map.of();
    }

    /**
     * Returns the attributes the caller attached to <em>this</em> run: the {@code environment.*} subset of a main
     * turn's or a routine's execution attributes, prefix removed (see {@link EnvironmentRequestAttributes}). Forks,
     * workflow steps and workflow runners never carry them — a fork's placement is its {@link #parent()}'s business.
     *
     * <p>
     * They are placement input and are host-supplied: a host must never fill the {@code environment.*} namespace from
     * model output (tool arguments, model-written routine parameters, workflow script values). Core forwards them and
     * does not read them; the key names belong to the provider. {@link #toString()} prints the key names only, and a
     * provider that logs this map should do the same.
     *
     * @return the attributes (never null; unmodifiable; empty when the caller attached none)
     */
    public Map<String, String> requestAttributes() {
        return requestAttributes;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "EnvironmentRequest{agentRuntimeId=" + agentRuntimeId + ", sessionId=" + sessionId + ", executionId="
                + executionId + ", parent=" + (parent != null) + ", branchKey=" + branchKey
                + (fork != null ? ", fork=" + fork.name() : "")
                + (requestAttributes.isEmpty()
                        ? ""
                        : ", requestAttributes=" + requestAttributes.keySet().stream().sorted().toList())
                + '}';
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
        private Map<String, String> requestAttributes = Map.of();

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

        /**
         * @param requestAttributes
         *            the attributes the caller attached to this run, prefix already removed, or null for none
         * @return this builder
         * @throws NullPointerException
         *             if the map holds a null key or value
         */
        public Builder requestAttributes(Map<String, String> requestAttributes) {
            this.requestAttributes = requestAttributes == null ? Map.of() : Map.copyOf(requestAttributes);
            return this;
        }

        /** @return the request */
        public EnvironmentRequest build() {
            return new EnvironmentRequest(this);
        }
    }
}
