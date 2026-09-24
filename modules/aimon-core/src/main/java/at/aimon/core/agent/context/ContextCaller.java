package at.aimon.core.agent.context;

import java.util.Optional;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.base.Principal;

/**
 * Who a {@link ContextEngine} call is made on behalf of.
 *
 * <p>
 * Both halves are optional and answer different questions. {@link #getExecutionId()} names a run that has <em>no
 * session of its own</em> &mdash; a subagent fork &mdash; so that a compaction the engine performs fires its hooks
 * with that identity instead of the transcript label, which for such a run is a wrapped execution id rather than a
 * session. It is absent for a session's turn, whose buffer's session id is the honest identity.
 * {@link #getPrincipal()} names the user the call is attributed to, when the caller knows one.
 *
 * <p>
 * Immutable value object built via {@link Builder}.
 */
public final class ContextCaller {

    private static final ContextCaller SESSION = new ContextCaller(new Builder());

    private final ExecutionId executionId;
    private final Principal principal;

    private ContextCaller(Builder builder) {
        this.executionId = builder.executionId;
        this.principal = builder.principal;
    }

    /**
     * The caller of a session's turn with no principal to attribute: identity comes from the transcript buffer's
     * session id.
     *
     * @return the shared instance (never null)
     */
    public static ContextCaller session() {
        return SESSION;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The identity of the session-less run the call belongs to.
     *
     * @return the run's execution id, or empty for a call inside a genuine session
     */
    public Optional<ExecutionId> getExecutionId() {
        return Optional.ofNullable(executionId);
    }

    /**
     * The principal the call is attributed to.
     *
     * @return the principal, or empty when the caller does not know one
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    @Override
    public String toString() {
        return "ContextCaller{" + (executionId != null ? "executionId=" + executionId.value() : "session")
                + (principal != null ? ", principal=" + principal.getId() : "") + '}';
    }

    /** Builder for {@link ContextCaller}. */
    public static final class Builder {
        private ExecutionId executionId;
        private Principal principal;

        private Builder() {
        }

        /**
         * Declares that the call belongs to a run with no session, and names that run.
         *
         * @param executionId
         *            the run's execution id, or {@code null} for a call inside a genuine session
         * @return this builder
         */
        public Builder executionId(ExecutionId executionId) {
            this.executionId = executionId;
            return this;
        }

        /**
         * @param principal
         *            the principal to attribute the call to, or {@code null} when unknown
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        public ContextCaller build() {
            return new ContextCaller(this);
        }
    }
}
