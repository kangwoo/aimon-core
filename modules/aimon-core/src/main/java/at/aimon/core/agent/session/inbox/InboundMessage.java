package at.aimon.core.agent.session.inbox;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.base.Principal;

/**
 * Immutable envelope appended to a {@code SessionInbox}.
 *
 * <p>
 * An {@code InboundMessage} carries everything the holder node needs to start (or inject mid-turn) a turn for a
 * session it did not originally receive: target {@link SessionId}, the {@code agentRef} the requester
 * targeted (validated against the session's binding per design §3.6), the optional {@code contextDiscriminator}
 * naming which runtime of that agent to open, the raw user input, priority tier, optional idempotency key, the
 * {@link Principal} that initiated the input, and arbitrary string metadata.
 *
 * <p>
 * Notably absent from this envelope is {@code agentRuntimeId}: the deliver-side context id is meaningless on
 * the holder side, and including it here would only enable accidental misuse. Holder rewraps the user input as a
 * {@link at.aimon.core.agent.queue.QueuedInput} with its own session's ctxId before forwarding to the session's
 * internal queue (routing design §3.4).
 *
 * <p>
 * Construction is via the nested {@link Builder}. The {@code id} is supplied by the inbox implementation at
 * {@code deliver} time and assigned through the builder before {@code build()}.
 */
public final class InboundMessage {

    private final InboundMessageId id;
    private final TurnId turnId;
    private final SessionId sessionId;
    private final String agentRef;
    private final String contextDiscriminator;
    private final String userInput;
    private final QueuedInputPriority priority;
    private final String idempotencyKey;
    private final Principal initiator;
    private final Instant deliveredAt;
    private final Map<String, String> metadata;
    private final SubmitOptions submitOptions;

    private InboundMessage(Builder b) {
        this.id = b.id;
        this.turnId = b.turnId;
        this.sessionId = Objects.requireNonNull(b.sessionId, "sessionId must not be null");
        this.agentRef = Objects.requireNonNull(b.agentRef, "agentRef must not be null");
        this.contextDiscriminator = b.contextDiscriminator;
        this.userInput = Objects.requireNonNull(b.userInput, "userInput must not be null");
        this.priority = Objects.requireNonNull(b.priority, "priority must not be null");
        this.idempotencyKey = b.idempotencyKey;
        this.initiator = Objects.requireNonNull(b.initiator, "initiator must not be null");
        this.deliveredAt = Objects.requireNonNull(b.deliveredAt, "deliveredAt must not be null");
        this.metadata = b.metadata != null ? Map.copyOf(b.metadata) : Map.of();
        this.submitOptions = b.submitOptions != null ? b.submitOptions : SubmitOptions.empty();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the inbox-assigned id.
     *
     * <p>
     * Callers building an envelope to {@code deliver()} may leave this unset — the inbox implementation issues the id
     * at delivery time and the manager observes it via {@code SessionInbox.deliver}'s return value. After
     * {@code collect()}, the id is always present.
     *
     * @return the id, or {@link Optional#empty()} when the envelope has not yet been stamped by an inbox
     */
    public Optional<InboundMessageId> getId() {
        return Optional.ofNullable(id);
    }

    /**
     * The {@link TurnId} the submitting node issued for this input, so the holder node runs the turn under the identity
     * the submitter already reported to its caller (via {@code SubmitDisposition.getTurnId()}) and every event the
     * holder
     * relays back is attributable to it.
     *
     * <p>
     * Optional by design, for two reasons. An inbox holds work that has <em>not been done yet</em>, so at every upgrade
     * the queue still contains envelopes written by a build that had no {@code turnId} to write — those messages must
     * still run, not fail to decode. And a producer that never intends to address the turn (an internal re-delivery, a
     * test fixture) is not obliged to invent an id. A holder that finds this empty issues its own id for the turn; it
     * simply cannot be addressed by the submitter.
     *
     * <p>
     * <b>Codec note:</b> all four built-in inbox codecs (in-memory, Redis, Postgres, MongoDB) round-trip this field,
     * and
     * absence round-trips as absence.
     *
     * @return the turn id, or {@link Optional#empty()} when the producer did not supply one
     */
    public Optional<TurnId> getTurnId() {
        return Optional.ofNullable(turnId);
    }

    public SessionId getSessionId() {
        return sessionId;
    }

    public String getAgentRef() {
        return agentRef;
    }

    /**
     * The {@code SubmitRequest.contextDiscriminator} the submitting node was given, so a holder that opens the session
     * to run this message opens it against the runtime the submitter meant — {@code agent:<ref>:<discriminator>} rather
     * than the bare {@code agent:<ref>}.
     *
     * <p>
     * It travels even though {@code agentRef} is settled by the session's persisted binding rather than by the message,
     * because the two answer different questions. The binding says <em>which agent</em>, and the holder is right to
     * distrust a message about that. The discriminator says <em>which runtime of that agent</em>, and nothing durable
     * records it: a session bound to {@code reporter} carries no memory of having been opened for {@code tenant-a}. A
     * holder without this field can only open the bare runtime, which is a different runtime with different tools,
     * hooks and MCP clients — not a degraded version of the right one.
     *
     * <p>
     * Optional, on the same terms as {@link #getTurnId()}: envelopes written before this field existed must still
     * decode, and a submission that named no discriminator has none to carry. Empty means the bare runtime, which is
     * also what the submitting node would have opened.
     *
     * <p>
     * Read on cache miss only, exactly as on the submitting side — a session already open on the holder is served as it
     * stands, and this field goes unread. So it changes the outcome only when the holder is opening the session fresh,
     * which is the one case where the alternative was guessing.
     *
     * <p>
     * <b>Codec note:</b> all four built-in inbox codecs (in-memory, Redis, Postgres, MongoDB) round-trip this field,
     * and absence round-trips as absence.
     *
     * @return the discriminator, or {@link Optional#empty()} when the bare {@code agent:<ref>} runtime is meant
     */
    public Optional<String> getContextDiscriminator() {
        return Optional.ofNullable(contextDiscriminator);
    }

    public String getUserInput() {
        return userInput;
    }

    public QueuedInputPriority getPriority() {
        return priority;
    }

    public Optional<String> getIdempotencyKey() {
        return Optional.ofNullable(idempotencyKey);
    }

    public Principal getInitiator() {
        return initiator;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Per-turn {@link SubmitOptions} preserved end-to-end from {@code SubmitRequest} so the holder-side dispatch
     * forwards the original caller-supplied executor metadata when it eventually calls
     * {@code session.submitAsync(input, submitOptions, listener)}. Defaults to {@link SubmitOptions#empty()} when the
     * producer did not supply any.
     *
     * <p>
     * <b>Codec note:</b> all four built-in inbox codecs (in-memory, Redis, Postgres, MongoDB) round-trip this field.
     * Heterogeneous {@code Map<String, Object>} values inside {@code systemPromptVariables} /
     * {@code executionAttributes}
     * are best-effort across persistent backends: scalar types (String, Number, Boolean, Date) and nested Map/List of
     * those round-trip cleanly; custom POJOs surface as nested {@code Map} on the consumer side because the wire format
     * is JSON / BSON without polymorphic type information.
     */
    public SubmitOptions getSubmitOptions() {
        return submitOptions;
    }

    /** Builder for {@link InboundMessage}. */
    public static final class Builder {
        private InboundMessageId id;
        private TurnId turnId;
        private SessionId sessionId;
        private String agentRef;
        private String contextDiscriminator;
        private String userInput;
        private QueuedInputPriority priority;
        private String idempotencyKey;
        private Principal initiator;
        private Instant deliveredAt;
        private Map<String, String> metadata;
        private SubmitOptions submitOptions;

        private Builder() {
        }

        public Builder id(InboundMessageId v) {
            this.id = v;
            return this;
        }

        /** Optional — see {@link InboundMessage#getTurnId()}. A {@code null} value leaves the field unset. */
        public Builder turnId(TurnId v) {
            this.turnId = v;
            return this;
        }

        public Builder sessionId(SessionId v) {
            this.sessionId = v;
            return this;
        }

        public Builder agentRef(String v) {
            this.agentRef = v;
            return this;
        }

        /**
         * Optional — see {@link InboundMessage#getContextDiscriminator()}. A {@code null} value leaves the field unset.
         */
        public Builder contextDiscriminator(String v) {
            this.contextDiscriminator = v;
            return this;
        }

        public Builder userInput(String v) {
            this.userInput = v;
            return this;
        }

        public Builder priority(QueuedInputPriority v) {
            this.priority = v;
            return this;
        }

        public Builder idempotencyKey(String v) {
            this.idempotencyKey = v;
            return this;
        }

        public Builder initiator(Principal v) {
            this.initiator = v;
            return this;
        }

        public Builder deliveredAt(Instant v) {
            this.deliveredAt = v;
            return this;
        }

        public Builder metadata(Map<String, String> v) {
            this.metadata = v;
            return this;
        }

        public Builder submitOptions(SubmitOptions v) {
            this.submitOptions = v;
            return this;
        }

        public InboundMessage build() {
            return new InboundMessage(this);
        }
    }
}
