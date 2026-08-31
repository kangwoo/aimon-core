package at.aimon.core.agent.tool;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;

/**
 * Per-execution metadata passed to a {@link ToolContextEnricher} — one instance per main-agent turn or subagent
 * execution, not one per tool call.
 *
 * <p>
 * Captures the data points an enricher most commonly needs to derive context keys: who this run is, who asked for it,
 * and the owning agent runtime. The class is intentionally small — additional data points should be added via the
 * builder when a concrete enricher needs them, never as ad-hoc parameters on {@link ToolContextEnricher#enrich}.
 *
 * <p>
 * Exactly one of {@link #getSessionId()} / {@link #getExecutionId()} is populated in practice: a main-agent turn runs
 * for a session and reports that session, while a subagent or skill fork has no session and reports an execution id
 * instead. Neither is validated against the other — a run that can honestly claim neither is better described by an
 * empty pair than by an invented id, which is what the previously-mandatory session id forced a fork to supply.
 *
 * <p>
 * Immutable value object, built via {@link #builder()}.
 */
public final class ToolContextEnrichmentInfo {

    private final SessionId sessionId;
    private final ExecutionId executionId;
    private final SessionId invokingSessionId;
    private final AgentRuntimeId agentRuntimeId;

    private ToolContextEnrichmentInfo(Builder builder) {
        this.sessionId = builder.sessionId;
        this.executionId = builder.executionId;
        this.invokingSessionId = builder.invokingSessionId;
        this.agentRuntimeId = Objects.requireNonNull(builder.agentRuntimeId, "agentRuntimeId cannot be null");
    }

    /** Returns a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the session this run <b>is</b> a turn of, when it is one.
     *
     * <p>
     * Present on the main-agent path. Empty on every run that has no session of its own — a subagent or skill fork, a
     * scheduled routine — which report {@link #getExecutionId()} instead. A fork used to mint a session id for its
     * transcript and report that here, which made this field impossible to trust: an enricher could not tell an id the
     * user was behind from one invented microseconds earlier. For "which session should this run's state be attributed
     * to?" see {@link #getInvokingSessionId()}.
     */
    public Optional<SessionId> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * Returns this run's own identifier when it has no session, e.g. a subagent fork or a scheduled routine.
     *
     * <p>
     * Node-local, never persisted, and it grants nothing: it is the right key for state the run should keep to itself
     * (so two concurrent forks do not share a bucket) and the wrong one for anything the user established. Empty
     * whenever {@link #getSessionId()} is present, since a session's turn is already identified by that session.
     */
    public Optional<ExecutionId> getExecutionId() {
        return Optional.ofNullable(executionId);
    }

    /**
     * Returns the session whose turn spawned this run, when there is one.
     *
     * <p>
     * Empty on the main-agent path (the main agent is the invoker, not an invokee) and for runs nobody asked for.
     * Present on a subagent fork, where it is the <em>only</em> id that can reach what the user built up — the fork
     * itself has no session, so there is nothing else to look under. Enrichers that want the user's session should
     * read {@code getInvokingSessionId().or(this::getSessionId)}: explicit at the call site, because which of the two
     * applies depends on whether this run is the invoker or an invokee. The result stays an {@link Optional}, and a
     * run that has neither must be handled rather than papered over with a fabricated id.
     */
    public Optional<SessionId> getInvokingSessionId() {
        return Optional.ofNullable(invokingSessionId);
    }

    /** Returns the owning agent runtime id (never null). */
    public AgentRuntimeId getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /** Builder for {@link ToolContextEnrichmentInfo}. */
    public static final class Builder {
        private SessionId sessionId;
        private ExecutionId executionId;
        private SessionId invokingSessionId;
        private AgentRuntimeId agentRuntimeId;

        private Builder() {
        }

        /** Sets the session this run is a turn of (optional — null when the run has no session). */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** Sets this run's own identifier (optional — null when the run is a session's turn). */
        public Builder executionId(ExecutionId executionId) {
            this.executionId = executionId;
            return this;
        }

        /** Sets the invoking session id (optional — null when the run has no invoker). */
        public Builder invokingSessionId(SessionId invokingSessionId) {
            this.invokingSessionId = invokingSessionId;
            return this;
        }

        /** Sets the agent runtime id (required). */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /** Builds the immutable info object. */
        public ToolContextEnrichmentInfo build() {
            return new ToolContextEnrichmentInfo(this);
        }
    }
}
