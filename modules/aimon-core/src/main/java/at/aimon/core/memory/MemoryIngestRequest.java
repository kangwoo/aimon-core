package at.aimon.core.memory;

import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.Message;

/**
 * Conversation messages offered to a {@link MemoryIngestor} so the backend can derive from them.
 *
 * <p>
 * This is the write counterpart to {@link MemorySnapshotQuery}: where the snapshot tier is consulted while a prompt is
 * assembled, this one is fed after an <b>execution</b> ends. Execution, not turn — the seam stands in the same place
 * for a session's turn and for a session-less execution (a subagent fork, a skill fork, a rewake replay, a scheduled
 * routine).
 *
 * <p>
 * As on the other tier requests, the workspace is {@code observer.getWorkspace()}.
 *
 * <p>
 * <b>{@link #getMessages() messages} must already have passed redaction.</b> As with {@link ObservationDraft}, that is
 * held by a decorator the assembly wraps around every backend rather than by callers remembering to do it.
 *
 * <p>
 * Immutable value object built via {@link #builder()}.
 */
public final class MemoryIngestRequest {

    private final PeerView observer;
    private final String sessionId;
    private final List<Message> messages;
    private final boolean waitForDerivation;

    private MemoryIngestRequest(Builder builder) {
        this.observer = Objects.requireNonNull(builder.observer, "observer cannot be null");
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId cannot be null");
        if (this.sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        this.messages = List.copyOf(Objects.requireNonNull(builder.messages, "messages cannot be null"));
        if (this.messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be empty — there is nothing to ingest");
        }
        this.waitForDerivation = builder.waitForDerivation;
    }

    /**
     * Starts a request.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the peer the conversation is attributed to. Its workspace is the request's workspace.
     *
     * @return the observer, never null
     */
    public PeerView getObserver() {
        return observer;
    }

    /**
     * Returns the session the messages belong to.
     *
     * @return the session id, never null or blank
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the messages to ingest, already redacted.
     *
     * @return the messages, never null or empty
     */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * Returns whether the caller wants derivation to have finished before the call returns.
     *
     * <p>
     * A request, not a guarantee. Backends that cannot synchronise ignore it, and
     * {@link MemoryIngestReceipt#isDerived()} says which happened.
     *
     * @return {@code true} when read-your-writes was asked for
     */
    public boolean isWaitForDerivation() {
        return waitForDerivation;
    }

    /**
     * Returns a copy of this request with the messages replaced — the shape the redaction decorator uses.
     *
     * @param newMessages
     *            the replacement messages (must not be null or empty)
     * @return a new request
     */
    public MemoryIngestRequest withMessages(List<Message> newMessages) {
        return builder().observer(observer).sessionId(sessionId).messages(newMessages)
                .waitForDerivation(waitForDerivation).build();
    }

    @Override
    public String toString() {
        return "MemoryIngestRequest{observer=" + observer.key() + ", session=" + sessionId + ", messages="
                + messages.size() + ", wait=" + waitForDerivation + "}";
    }

    /** Builder for {@link MemoryIngestRequest}. */
    public static final class Builder {

        private PeerView observer;
        private String sessionId;
        private List<Message> messages;
        private boolean waitForDerivation;

        private Builder() {
        }

        /**
         * Sets the peer the conversation is attributed to. Required; carries the workspace.
         *
         * @param observer
         *            the observer (must not be null)
         * @return this builder
         */
        public Builder observer(PeerView observer) {
            this.observer = Objects.requireNonNull(observer, "observer cannot be null");
            return this;
        }

        /**
         * Sets the session the messages belong to. Required.
         *
         * @param sessionId
         *            the session id (must not be null or blank)
         * @return this builder
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
            return this;
        }

        /**
         * Sets the messages to ingest. Required, and must already be redacted.
         *
         * @param messages
         *            the messages (must not be null or empty)
         * @return this builder
         */
        public Builder messages(List<Message> messages) {
            this.messages = Objects.requireNonNull(messages, "messages cannot be null");
            return this;
        }

        /**
         * Asks for derivation to complete before the call returns.
         *
         * @param waitForDerivation
         *            {@code true} to request read-your-writes
         * @return this builder
         */
        public Builder waitForDerivation(boolean waitForDerivation) {
            this.waitForDerivation = waitForDerivation;
            return this;
        }

        /**
         * Validates and builds the request.
         *
         * @return the immutable request
         * @throws IllegalArgumentException
         *             if the session id is blank or the message list is empty
         */
        public MemoryIngestRequest build() {
            return new MemoryIngestRequest(this);
        }
    }
}
