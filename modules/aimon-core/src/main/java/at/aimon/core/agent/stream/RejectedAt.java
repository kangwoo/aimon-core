package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Signals that a queued / inbox-delivered input was rejected after binding evaluation, before any iteration runs.
 *
 * <p>
 * <b>Use when:</b> the web session manager (routing design §3.6) detects that an inbox-delivered input is targeting a
 * session that is already bound to a different agent. The input is dropped and the originator is notified via
 * this event so UI can render a clear rejection.
 *
 * <p>
 * Extra fields:
 *
 * <ul>
 * <li>{@link #getReason()} — classification of why the input was rejected
 * <li>{@link #getRequestedAgent()} — the {@code agentRef} carried by the rejected input
 * <li>{@link #getExistingAgent()} — the {@code agentRef} already bound to the session (may be null when no
 * binding existed but the manager rejects for another reason in the future)
 * <li>{@link #getInboxId()} — opaque identifier of the rejected inbox message; kept as a {@link String} rather
 * than an {@code InboundMessageId} so an event consumer need not reach into
 * {@link at.aimon.core.agent.session.inbox} for a value it only ever echoes back
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class RejectedAt extends AgentExecutionEvent {

    private final RejectReason reason;
    private final String requestedAgent;
    private final String existingAgent;
    private final String inboxId;

    private RejectedAt(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.reason = Objects.requireNonNull(builder.reason, "Reason cannot be null");
        this.requestedAgent = Objects.requireNonNull(builder.requestedAgent, "RequestedAgent cannot be null");
        this.existingAgent = builder.existingAgent;
        this.inboxId = Objects.requireNonNull(builder.inboxId, "InboxId cannot be null");
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the rejection reason.
     *
     * @return the reason (never null)
     */
    public RejectReason getReason() {
        return reason;
    }

    /**
     * Returns the {@code agentRef} carried by the rejected input.
     *
     * @return the requested agent reference (never null, never empty)
     */
    public String getRequestedAgent() {
        return requestedAgent;
    }

    /**
     * Returns the {@code agentRef} already bound to the session, if any.
     *
     * @return the existing agent reference, or empty when no binding existed
     */
    public Optional<String> getExistingAgent() {
        return Optional.ofNullable(existingAgent);
    }

    /**
     * Returns the opaque inbox message identifier of the rejected input.
     *
     * @return the inbox id (never null)
     */
    public String getInboxId() {
        return inboxId;
    }

    @Override
    protected String eventName() {
        return "RejectedAt";
    }

    @Override
    protected String detailString() {
        return "reason=" + reason + ", requestedAgent=" + requestedAgent + ", existingAgent=" + existingAgent
                + ", inboxId=" + inboxId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RejectedAt that = (RejectedAt) o;
        return getIteration() == that.getIteration() && reason == that.reason
                && requestedAgent.equals(that.requestedAgent) && Objects.equals(existingAgent, that.existingAgent)
                && inboxId.equals(that.inboxId) && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), reason, requestedAgent, existingAgent,
                inboxId);
    }

    /** Builder for {@link RejectedAt}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private RejectReason reason;
        private String requestedAgent;
        private String existingAgent;
        private String inboxId;

        private Builder() {
        }

        /**
         * Sets the event timestamp.
         *
         * @param timestamp
         *            the wall-clock instant (must not be null)
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the agent runtime identifier.
         *
         * @param agentRuntimeId
         *            the agent runtime identifier (must not be null)
         * @return this builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        /**
         * Sets the iteration number on the base event.
         *
         * @param iteration
         *            the iteration number (must be {@code >= 0})
         * @return this builder
         */
        public Builder iteration(int iteration) {
            this.iteration = iteration;
            return this;
        }

        /**
         * Sets the rejection reason.
         *
         * @param reason
         *            the reason (must not be null)
         * @return this builder
         */
        public Builder reason(RejectReason reason) {
            this.reason = reason;
            return this;
        }

        /**
         * Sets the requested agent reference.
         *
         * @param requestedAgent
         *            the requested agent reference (must not be null)
         * @return this builder
         */
        public Builder requestedAgent(String requestedAgent) {
            this.requestedAgent = requestedAgent;
            return this;
        }

        /**
         * Sets the existing agent reference (the session's current binding, if any).
         *
         * @param existingAgent
         *            the existing agent reference (may be null)
         * @return this builder
         */
        public Builder existingAgent(String existingAgent) {
            this.existingAgent = existingAgent;
            return this;
        }

        /**
         * Sets the opaque inbox message identifier of the rejected input.
         *
         * @param inboxId
         *            the inbox id (must not be null)
         * @return this builder
         */
        public Builder inboxId(String inboxId) {
            this.inboxId = inboxId;
            return this;
        }

        /**
         * Builds the {@link RejectedAt} event.
         *
         * @return a new {@link RejectedAt}
         * @throws NullPointerException
         *             if a required field is null
         */
        public RejectedAt build() {
            return new RejectedAt(this);
        }
    }
}
