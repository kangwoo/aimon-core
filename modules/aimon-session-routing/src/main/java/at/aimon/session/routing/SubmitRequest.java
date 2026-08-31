package at.aimon.session.routing;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.OpenAttributes;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;

/**
 * Immutable submit request envelope for {@link SessionRouter#submit(SubmitRequest)}.
 *
 * <p>
 * Carries the target {@link SessionId}, the {@code agentRef} the caller intends to bind to the session, the
 * raw user input, optional session options, optional idempotency key, priority tier (defaults to {@link
 * QueuedInputPriority#NEXT}), and the {@link Principal} that initiated the input.
 *
 * <p>
 * The optional {@code contextDiscriminator} selects which {@code AgentRuntime} instance the session should
 * bind to when a single agent definition is split into multiple agent-scoped contexts (per tenant, per environment,
 * etc.). When unset the manager derives the bare {@code agent:<agentRef>} id; when set the id becomes
 * {@code agent:<agentRef>:<discriminator>} and must match an entry pre-registered on the application's
 * {@code OrcaAgentRuntimeManager}. Like {@link OpenAttributes}, it is read on cache miss only — once a
 * session is cached, subsequent submits with a different discriminator do not re-open the session.
 *
 * <p>
 * Constructed via {@link Builder}; per project convention "prefer class over record".
 */
public final class SubmitRequest {

    private final SessionId sessionId;
    private final String agentRef;
    private final String contextDiscriminator;
    private final String userInput;
    private final LiveSessionOptions options;
    private final SubmitOptions submitOptions;
    private final OpenAttributes openAttributes;
    private final String idempotencyKey;
    private final QueuedInputPriority priority;
    private final Principal initiator;

    private SubmitRequest(Builder b) {
        this.sessionId = Objects.requireNonNull(b.sessionId, "sessionId must not be null");
        this.agentRef = Objects.requireNonNull(b.agentRef, "agentRef must not be null");
        this.contextDiscriminator = b.contextDiscriminator;
        this.userInput = Objects.requireNonNull(b.userInput, "userInput must not be null");
        this.options = b.options != null ? b.options : LiveSessionOptions.defaults();
        this.submitOptions = b.submitOptions != null ? b.submitOptions : SubmitOptions.empty();
        this.openAttributes = b.openAttributes != null ? b.openAttributes : OpenAttributes.empty();
        this.idempotencyKey = b.idempotencyKey;
        this.priority = b.priority != null ? b.priority : QueuedInputPriority.NEXT;
        this.initiator = Objects.requireNonNull(b.initiator, "initiator must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public SessionId getSessionId() {
        return sessionId;
    }

    public String getAgentRef() {
        return agentRef;
    }

    /**
     * Optional discriminator used to select among multiple {@code AgentRuntime} instances of the same agent
     * (e.g., per tenant). Read by the manager on cache miss to derive the
     * {@link at.aimon.core.agent.AgentRuntimeId} threaded into the {@link LiveSessionOpener}.
     *
     * @return the discriminator, or empty when the bare {@code agent:<agentRef>} context should be used
     */
    public Optional<String> getContextDiscriminator() {
        return Optional.ofNullable(contextDiscriminator);
    }

    public String getUserInput() {
        return userInput;
    }

    public LiveSessionOptions getOptions() {
        return options;
    }

    /**
     * Per-turn options forwarded to {@code LiveSession.submitAsync(input, submitOptions, listener)} when this
     * request is dispatched. Defaults to {@link SubmitOptions#empty()} so legacy callers see unchanged behavior.
     */
    public SubmitOptions getSubmitOptions() {
        return submitOptions;
    }

    /**
     * Caller-provided open attributes forwarded to the configured {@link LiveSessionOpener} on cache miss.
     *
     * <p>
     * Attributes are consumed only when the manager actually invokes the opener for a fresh session — they have no
     * effect on submits that hit an already-cached session. Defaults to {@link OpenAttributes#empty()} so legacy
     * callers see unchanged behavior.
     */
    public OpenAttributes getOpenAttributes() {
        return openAttributes;
    }

    public Optional<String> getIdempotencyKey() {
        return Optional.ofNullable(idempotencyKey);
    }

    public QueuedInputPriority getPriority() {
        return priority;
    }

    public Principal getInitiator() {
        return initiator;
    }

    /** Builder for {@link SubmitRequest}. */
    public static final class Builder {
        private SessionId sessionId;
        private String agentRef;
        private String contextDiscriminator;
        private String userInput;
        private LiveSessionOptions options;
        private SubmitOptions submitOptions;
        private OpenAttributes openAttributes;
        private String idempotencyKey;
        private QueuedInputPriority priority;
        private Principal initiator;

        private Builder() {
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
         * Sets the optional context discriminator. See {@link SubmitRequest#getContextDiscriminator()} for
         * semantics.
         *
         * @param v
         *            the discriminator, or {@code null} for the bare {@code agent:<agentRef>} context. When non-null
         *            it must be non-blank and must not contain {@code ':'} (the segment separator) — the same rules
         *            that {@link at.aimon.core.agent.AgentRuntimeId#fromName(String, String)} enforces.
         * @return this builder
         * @throws IllegalArgumentException
         *             if {@code v} is non-null but blank or contains {@code ':'}
         */
        public Builder contextDiscriminator(String v) {
            if (v != null) {
                if (v.isBlank()) {
                    throw new IllegalArgumentException("contextDiscriminator must not be blank when set");
                }
                if (v.indexOf(':') >= 0) {
                    throw new IllegalArgumentException(
                            "contextDiscriminator must not contain ':' (reserved separator); got: " + v);
                }
            }
            this.contextDiscriminator = v;
            return this;
        }

        public Builder userInput(String v) {
            this.userInput = v;
            return this;
        }

        public Builder options(LiveSessionOptions v) {
            this.options = v;
            return this;
        }

        public Builder submitOptions(SubmitOptions v) {
            this.submitOptions = v;
            return this;
        }

        public Builder openAttributes(OpenAttributes v) {
            this.openAttributes = v;
            return this;
        }

        public Builder idempotencyKey(String v) {
            this.idempotencyKey = v;
            return this;
        }

        public Builder priority(QueuedInputPriority v) {
            this.priority = v;
            return this;
        }

        public Builder initiator(Principal v) {
            this.initiator = v;
            return this;
        }

        public SubmitRequest build() {
            return new SubmitRequest(this);
        }
    }
}
