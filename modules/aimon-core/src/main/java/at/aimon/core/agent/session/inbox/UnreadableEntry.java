package at.aimon.core.agent.session.inbox;

import java.util.Objects;
import java.util.Optional;

/**
 * An inbox entry a {@code SessionInbox} removed from its backend and then could not decode.
 *
 * <p>
 * Every backend deletes an entry from storage <em>before</em> the codec sees it, so a decode failure is not a
 * rejection — the entry is already gone and no node will ever run its turn. What is left is the chance to say so
 * to whoever is waiting: this value carries what could still be read of the entry's address after the failure, and
 * the router turns it into an addressed turn failure rather than letting the submitter wait out the forward TTL.
 * The design is <a href="../../../../../../../../../docs/design/session/inbox-collect-durability.md">
 * inbox-collect-durability.md</a>.
 *
 * <p>
 * <b>Both addresses are unvalidated strings, deliberately.</b> They come out of a payload that has already proved
 * itself untrustworthy, on the path taken after an exception. Rebuilding a {@link at.aimon.core.agent.session.TurnId}
 * here would run that type's validation against exactly the input least likely to satisfy it, and a throw at this
 * point either re-creates the batch loss this whole seam exists to remove or silently swallows the report. The
 * conversion happens at the point that needs it, where a {@code null} has a branch of its own.
 *
 * <p>
 * <b>{@link #getReason()} is the failure's own message, never the stored payload.</b> Nothing on this path appends
 * the entry's text to it, and the user's input cannot reach here at all — an encoding this build cannot read
 * degrades to the plain-text rendering beside it long before the envelope is rebuilt
 * ({@link at.aimon.core.subagent.task.codec.UserInputCodec#decodeOrText}), so a decode failure that gets this far
 * is a failure about the envelope.
 *
 * <p>
 * <b>What that message can quote is the one envelope value that caused it</b>, because the JDK's own exceptions do:
 * {@code Instant.parse} reports {@code Text 'not-a-date' could not be parsed at index 0} and an enum's
 * {@code valueOf} reports {@code No enum constant …Principal.Type.ROBOT}. That is deliberate rather than tolerated —
 * without the offending value an operator cannot tell a newer build's document from a damaged one, which is the
 * question this whole path exists to answer — and it is the same width the tree already accepts one field over,
 * where {@code UserInputCodec}'s degradation logs its cause's message for the same reason. It is not a licence to
 * widen: an implementation must not add the payload, and the fields that can appear are the envelope's own
 * ({@code deliveredAt}, {@code initiator.type}, {@code priority}), never the message a user wrote.
 *
 * <p>
 * Immutable value object; construct through {@link #builder()}.
 */
public final class UnreadableEntry {

    private final InboundMessageId id;
    private final String turnId;
    private final String idempotencyKey;
    private final String reason;

    private UnreadableEntry(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id must not be null");
        this.turnId = b.turnId;
        this.idempotencyKey = b.idempotencyKey;
        this.reason = Objects.requireNonNull(b.reason, "reason must not be null");
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The backend's own id for the entry — Redis stream entry id, Postgres row id, Mongo {@code _id}. Always known,
     * because the backend read it before it tried to decode anything.
     *
     * @return the entry id (never null)
     */
    public InboundMessageId getId() {
        return id;
    }

    /**
     * The turn id the submitting node issued, as raw text, when it could still be read.
     *
     * @return the raw turn id, or empty when the document did not yield one
     */
    public Optional<String> getTurnId() {
        return Optional.ofNullable(turnId);
    }

    /**
     * The submission's idempotency key, as raw text, when it could still be read.
     *
     * @return the raw key, or empty when the submission had none or the document did not yield one
     */
    public Optional<String> getIdempotencyKey() {
        return Optional.ofNullable(idempotencyKey);
    }

    /**
     * Why the entry could not be decoded — the failure's message, never its payload.
     *
     * @return the reason (never null)
     */
    public String getReason() {
        return reason;
    }

    /**
     * Whether anything on this entry can address a waiting caller.
     *
     * @return {@code true} when at least one of the two addresses survived
     */
    public boolean isAddressable() {
        return turnId != null || idempotencyKey != null;
    }

    @Override
    public String toString() {
        return "UnreadableEntry{id=" + id + ", turnId=" + turnId + ", idempotencyKey=" + idempotencyKey + "}";
    }

    /** Builder for {@link UnreadableEntry}. */
    public static final class Builder {

        private InboundMessageId id;
        private String turnId;
        private String idempotencyKey;
        private String reason;

        private Builder() {
        }

        /**
         * @param id
         *            the backend's entry id (must not be null)
         * @return this builder
         */
        public Builder id(InboundMessageId id) {
            this.id = id;
            return this;
        }

        /**
         * @param turnId
         *            the raw turn id, or null when it could not be read
         * @return this builder
         */
        public Builder turnId(String turnId) {
            this.turnId = turnId;
            return this;
        }

        /**
         * @param idempotencyKey
         *            the raw idempotency key, or null when it could not be read
         * @return this builder
         */
        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        /**
         * @param reason
         *            the failure's message (must not be null)
         * @return this builder
         */
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * @return the built entry
         */
        public UnreadableEntry build() {
            return new UnreadableEntry(this);
        }
    }
}
