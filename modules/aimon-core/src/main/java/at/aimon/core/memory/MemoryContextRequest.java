package at.aimon.core.memory;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * Identifies the execution a {@link MemoryContextProvider} is being asked to contribute memory for.
 *
 * <p>
 * Before this type existed, a provider was constructed with its session id and its peer already baked in, which is
 * correct for exactly one deployment shape: a single-user process that opens one session. Anywhere else it is a leak —
 * every session of every user gets the one representation the provider was built with. Passing the execution's own
 * identity per call is what lets one provider instance serve many sessions and many callers.
 *
 * <h2>Both fields are optional, for different reasons</h2>
 *
 * <ul>
 * <li><b>{@code sessionId}</b> is absent for executions that are not turns — subagent forks, skill forks, rewake
 * replays and scheduled routines have an {@code ExecutionId} and no session at all (see
 * {@code docs/overview/glossary.md} §4). An absent session id means "resolve across sessions", not "unknown".
 * <li><b>{@code principal}</b> is absent whenever the transport did not identify the caller. The CLI never sets one —
 * it has a single configured peer — and an HTTP request may be anonymous. What that absence <em>means</em> is not this
 * type's decision: it is {@link MemoryPeerResolver}'s.
 * </ul>
 *
 * <p>
 * Immutable. New identity axes (tenant, execution id) are added as fields here rather than as parameters on
 * {@link MemoryContextProvider#provide(MemoryContextRequest)}, so widening the input never breaks an implementation.
 */
public final class MemoryContextRequest {

    private static final MemoryContextRequest EMPTY = builder().build();

    private final SessionId sessionId;
    private final Principal principal;

    private MemoryContextRequest(Builder builder) {
        this.sessionId = builder.sessionId;
        this.principal = builder.principal;
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the request that names neither a session nor a caller.
     *
     * <p>
     * This is the shape a session-less execution with an unidentified caller has, and it is also what test and
     * diagnostic call sites should pass rather than inventing an identity.
     *
     * @return the empty request; never {@code null}
     */
    public static MemoryContextRequest empty() {
        return EMPTY;
    }

    /**
     * Returns the session this execution belongs to.
     *
     * @return the session id, or empty for an execution that has no session
     */
    public Optional<SessionId> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * Returns the identity the transport attached to this execution.
     *
     * @return the caller, or empty when the transport did not identify one
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final MemoryContextRequest that = (MemoryContextRequest) o;
        return Objects.equals(sessionId, that.sessionId) && Objects.equals(principal, that.principal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, principal);
    }

    @Override
    public String toString() {
        return "MemoryContextRequest{sessionId=" + sessionId + ", principal=" + principal + '}';
    }

    /** Builder for {@link MemoryContextRequest}; every field is optional. */
    public static final class Builder {

        private SessionId sessionId;
        private Principal principal;

        private Builder() {
        }

        /**
         * Sets the session this execution belongs to.
         *
         * @param sessionId
         *            the session id, or {@code null} for a session-less execution
         * @return this builder
         */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Sets the identity the transport attached to this execution.
         *
         * @param principal
         *            the caller, or {@code null} when unidentified
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the immutable request
         */
        public MemoryContextRequest build() {
            return new MemoryContextRequest(this);
        }
    }
}
