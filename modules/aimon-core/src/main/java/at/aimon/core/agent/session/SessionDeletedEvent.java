package at.aimon.core.agent.session;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.Principal;

/**
 * What {@link SessionLifecycleListener#onDeleted(SessionDeletedEvent)} is told about a deleted session.
 *
 * <p>
 * The owner and the agent binding are the values recorded just before the record was deleted, read by the deleting
 * node under the session's lease. They are carried here because they can no longer be read from the store: an
 * extension keyed by owner would otherwise have nothing left to look its state up by. Either is empty when the record
 * carried none, and both are empty when the record could not be read.
 *
 * <p>
 * Immutable; built through {@link #builder()}.
 */
public final class SessionDeletedEvent {

    private final SessionId sessionId;
    private final Principal owner;
    private final String agentRef;
    private final Instant deletedAt;

    private SessionDeletedEvent(Builder builder) {
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId must not be null");
        this.owner = builder.owner;
        this.agentRef = builder.agentRef;
        this.deletedAt = Objects.requireNonNull(builder.deletedAt, "deletedAt must not be null");
    }

    /**
     * Starts building an event.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the id of the deleted session (never null)
     */
    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * @return the owner the record carried just before deletion, or empty when it carried none
     */
    public Optional<Principal> getOwner() {
        return Optional.ofNullable(owner);
    }

    /**
     * @return the agent the record was bound to just before deletion, or empty when it was unbound
     */
    public Optional<String> getAgentRef() {
        return Optional.ofNullable(agentRef);
    }

    /**
     * @return when the deleting node removed the record (never null)
     */
    public Instant getDeletedAt() {
        return deletedAt;
    }

    @Override
    public String toString() {
        return "SessionDeletedEvent{sessionId=" + sessionId + ", owner="
                + (owner == null ? null : owner.getType() + ":" + owner.getId()) + ", agentRef=" + agentRef
                + ", deletedAt=" + deletedAt + '}';
    }

    /** Builder for {@link SessionDeletedEvent}. */
    public static final class Builder {

        private SessionId sessionId;
        private Principal owner;
        private String agentRef;
        private Instant deletedAt;

        private Builder() {
        }

        /**
         * @param sessionId
         *            the deleted session (required)
         * @return this builder
         */
        public Builder sessionId(SessionId sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * @param owner
         *            the owner the record carried, or null for none
         * @return this builder
         */
        public Builder owner(Principal owner) {
            this.owner = owner;
            return this;
        }

        /**
         * @param agentRef
         *            the agent the record was bound to, or null when unbound
         * @return this builder
         */
        public Builder agentRef(String agentRef) {
            this.agentRef = agentRef;
            return this;
        }

        /**
         * @param deletedAt
         *            when the record was removed (required)
         * @return this builder
         */
        public Builder deletedAt(Instant deletedAt) {
            this.deletedAt = deletedAt;
            return this;
        }

        /**
         * @return the event (never null)
         * @throws NullPointerException
         *             if {@code sessionId} or {@code deletedAt} is unset
         */
        public SessionDeletedEvent build() {
            return new SessionDeletedEvent(this);
        }
    }
}
