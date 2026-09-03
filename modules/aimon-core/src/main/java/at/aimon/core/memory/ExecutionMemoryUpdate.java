package at.aimon.core.memory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;

/**
 * What an execution added to the conversation, offered to an {@link ExecutionMemorySink}.
 *
 * <p>
 * The identity half is the same shape as {@link MemoryContextRequest} — a session id that a session-less execution
 * does not have, and a principal a transport may not have attached — and that is on purpose: the read seam and the
 * write seam are told the same thing about who is executing, and both resolve a peer from it through
 * {@link MemoryPeerResolver}. The messages are the half the read seam has no use for.
 *
 * <p>
 * <b>Execution, not turn.</b> The seam stands in the same place for a session's turn and for a session-less execution
 * — a subagent fork, a skill fork, a rewake replay, a scheduled routine — so the vocabulary is the one that covers
 * both.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class ExecutionMemoryUpdate {

    private final SessionId sessionId;
    private final Principal principal;
    private final List<Message> messages;

    private ExecutionMemoryUpdate(Builder builder) {
        this.sessionId = builder.sessionId;
        this.principal = builder.principal;
        this.messages = List.copyOf(Objects.requireNonNull(builder.messages, "messages cannot be null"));
    }

    /**
     * Starts an update.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the session the execution belonged to.
     *
     * @return the session id, or empty for a session-less execution
     */
    public Optional<SessionId> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * Returns the identity the execution arrived under.
     *
     * @return the principal, or empty when the transport attached none
     */
    public Optional<Principal> getPrincipal() {
        return Optional.ofNullable(principal);
    }

    /**
     * Returns the messages the execution added, in order.
     *
     * @return the messages, never null; empty when the execution added nothing, or when the history was rewritten
     *         underneath it and no delta could be taken
     */
    public List<Message> getMessages() {
        return messages;
    }

    @Override
    public String toString() {
        return "ExecutionMemoryUpdate{session=" + (sessionId == null ? "-" : sessionId.value()) + ", principal="
                + (principal == null ? "-" : principal.getId()) + ", messages=" + messages.size() + "}";
    }

    /** Builder for {@link ExecutionMemoryUpdate}. */
    public static final class Builder {

        private SessionId sessionId;
        private Principal principal;
        private List<Message> messages = List.of();

        private Builder() {
        }

        /**
         * Sets the session the execution belonged to.
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
         * Sets the identity the execution arrived under.
         *
         * @param principal
         *            the principal, or {@code null}
         * @return this builder
         */
        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Sets the messages the execution added.
         *
         * @param messages
         *            the messages (must not be null; may be empty)
         * @return this builder
         */
        public Builder messages(List<Message> messages) {
            this.messages = Objects.requireNonNull(messages, "messages cannot be null");
            return this;
        }

        /**
         * Builds the update.
         *
         * @return the immutable update
         */
        public ExecutionMemoryUpdate build() {
            return new ExecutionMemoryUpdate(this);
        }
    }
}
