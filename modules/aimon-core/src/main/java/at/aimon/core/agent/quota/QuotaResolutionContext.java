package at.aimon.core.agent.quota;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * What a {@link QuotaScopeResolver} is told about the execution whose scopes it picks.
 *
 * <p>
 * Built once per turn, from the execution request. A fork never builds one: it inherits its parent's
 * {@link QuotaGuard}, scopes and all, because a fork has no session and resolving again would drop the session scope.
 *
 * <p>
 * Immutable value object. Use builder to create instances.
 */
public final class QuotaResolutionContext {

    private final AgentRuntimeId agentRuntimeId;
    private final SessionId sessionId;
    private final Principal principal;
    private final Map<String, Object> attributes;

    private QuotaResolutionContext(Builder builder) {
        this.agentRuntimeId = Objects.requireNonNull(builder.agentRuntimeId, "Agent runtime ID cannot be null");
        this.sessionId = builder.sessionId;
        this.principal = builder.principal;
        this.attributes = builder.attributes != null ? Map.copyOf(builder.attributes) : Map.of();
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the runtime running the execution. Its discriminator is where a bootstrap-style tenant lives
     * ({@code agent:ops:acme}).
     *
     * @return the runtime id (never null)
     */
    public AgentRuntimeId getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /**
     * Gets the session the execution is a turn of.
     *
     * @return the session id, or empty for a session-less execution (a scheduled routine, a rewake replay)
     */
    public Optional<SessionId> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * Gets the caller identity.
     *
     * @return the principal, or empty when the request carries none
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Gets the execution attributes the request carries — free-form application data a resolver may read a tenant
     * from.
     *
     * @return an immutable map (never null, may be empty)
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final QuotaResolutionContext that = (QuotaResolutionContext) o;
        return agentRuntimeId.equals(that.agentRuntimeId) && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(principal, that.principal) && attributes.equals(that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentRuntimeId, sessionId, principal, attributes);
    }

    @Override
    public String toString() {
        return "QuotaResolutionContext{agentRuntimeId=" + agentRuntimeId + ", sessionId=" + sessionId + ", principal="
                + principal + ", attributes=" + attributes.keySet() + '}';
    }

    /** Builder for {@link QuotaResolutionContext}. */
    public static final class Builder {
        private AgentRuntimeId agentRuntimeId;
        private SessionId sessionId;
        private Principal principal;
        private Map<String, Object> attributes;

        private Builder() {
        }

        /**
         * Sets the runtime running the execution.
         *
         * @param agentRuntimeId
         *            the runtime id (required)
         * @return this builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * Sets the session the execution is a turn of.
         *
         * @param sessionId
         *            the session id, or null for a session-less execution
         * @return this builder
         */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Sets the caller identity.
         *
         * @param principal
         *            the principal, or null when there is none
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Sets the execution attributes.
         *
         * @param attributes
         *            the attributes, or null for none
         * @return this builder
         */
        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        /**
         * Builds the context.
         *
         * @return a new context (never null)
         * @throws NullPointerException
         *             if the agent runtime id was not set
         */
        public QuotaResolutionContext build() {
            return new QuotaResolutionContext(this);
        }
    }
}
