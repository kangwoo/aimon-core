package at.aimon.core.hook.rewake;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Serializable resume payload describing a single in-flight rewake.
 *
 * <p>
 * The framework constructs an envelope when a hook returns {@link RewakeSpec}. The envelope is what the scheduler
 * persists, what the resolver matches external events against, and what is delivered back to the originating hook on
 * the follow-up firing.
 *
 * <p>
 * Fields:
 * <ul>
 * <li>{@link #getEnvelopeId() envelopeId} — opaque framework-assigned identifier (UUID by default). Stable across
 * scheduler restarts.
 * <li>{@link #getAgentRuntimeId() agentRuntimeId} — agent-scoped context id; required so the
 * follow-up firing dispatches against the right
 * {@link at.aimon.core.agent.AgentRuntime AgentRuntime}.
 * <li>{@link #getTrigger() trigger} — the {@link RewakeTrigger} that drives scheduling. Carried on the envelope (not
 * just on the originating {@link RewakeSpec}) so a persistent scheduler can resume cron / event triggers across JVM
 * restarts.
 * <li>{@link #getOriginalEventType() originalEventType} — typed token of the event being replayed (e.g.
 * {@link HookEventType#PRE_TOOL}).
 * <li>{@link #getOriginatingHookId() originatingHookId} — stable id of the hook that emitted the spec; used by the
 * registry to re-dispatch only to that hook on resume.
 * <li>{@link #getOriginalToolName() originalToolName} / {@link #getOriginalToolInput() originalToolInput} — captured
 * tool dispatch context (only meaningful for tool-scoped events; both null for lifecycle events).
 * <li>{@link #getAttemptNumber() attemptNumber} — 1-based fire counter. The first follow-up firing is attempt
 * {@code 2}; the spec's {@link RewakeSpec#getMaxAttempts() maxAttempts} caps the total fires including the initial
 * one.
 * <li>{@link #getMaxAttempts() maxAttempts} — copy of {@link RewakeSpec#getMaxAttempts() spec.maxAttempts} carried
 * onto the envelope so per-fire bounds (notably for cron, where the same envelope is re-delivered without going
 * back through hook chaining) can be enforced without retaining the spec.
 * <li>{@link #getTimeout() timeout} — copy of {@link RewakeSpec#getTimeout() spec.timeout}. Combined with
 * {@link #getFirstScheduledAt() firstScheduledAt}, this defines the hard upper bound on the rewake window. The
 * Quartz cron handler maps this to an {@code endAt} on the trigger so the scheduler stops firing autonomously.
 * <li>{@link #getFirstScheduledAt() firstScheduledAt} — wall-clock timestamp when the envelope was first persisted.
 * Used to enforce the per-spec timeout independently of the trigger.
 * <li>{@link #getPayload() payload} — opaque verbatim copy of {@link RewakeSpec#getPayload()}.
 * <li>{@link #getReason() reason} — human-readable reason carried over from the spec for logs and {@code
 * OnConfigReload} traces.
 * </ul>
 *
 * <p>
 * Immutable; safe to share across threads. Build via {@link #builder()}.
 */
public final class RewakeEnvelope {

    private final String envelopeId;
    private final AgentRuntimeId agentRuntimeId;
    private final RewakeTrigger trigger;
    private final HookEventType<? extends ExecutionHook<?>> originalEventType;
    private final String originatingHookId;
    private final String originalToolName;
    private final ToolInput originalToolInput;
    private final int attemptNumber;
    private final int maxAttempts;
    private final Duration timeout;
    private final Instant firstScheduledAt;
    private final Map<String, String> payload;
    private final String reason;

    private RewakeEnvelope(Builder builder) {
        this.envelopeId = Objects.requireNonNull(builder.envelopeId, "envelopeId must be set");
        if (envelopeId.isBlank()) {
            throw new IllegalArgumentException("envelopeId cannot be blank");
        }
        this.agentRuntimeId = Objects.requireNonNull(builder.agentRuntimeId, "agentRuntimeId must be set");
        this.trigger = Objects.requireNonNull(builder.trigger, "trigger must be set");
        this.originalEventType = Objects.requireNonNull(builder.originalEventType, "originalEventType must be set");
        this.originatingHookId = Objects.requireNonNull(builder.originatingHookId, "originatingHookId must be set");
        if (originatingHookId.isBlank()) {
            throw new IllegalArgumentException("originatingHookId cannot be blank");
        }
        this.originalToolName = builder.originalToolName;
        this.originalToolInput = builder.originalToolInput;
        if (builder.attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1, got: " + builder.attemptNumber);
        }
        this.attemptNumber = builder.attemptNumber;
        if (builder.maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + builder.maxAttempts);
        }
        this.maxAttempts = builder.maxAttempts;
        this.timeout = Objects.requireNonNull(builder.timeout, "timeout must be set");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be strictly positive, got: " + timeout);
        }
        this.firstScheduledAt = Objects.requireNonNull(builder.firstScheduledAt, "firstScheduledAt must be set");
        this.payload = Map.copyOf(builder.payload);
        this.reason = Objects.requireNonNull(builder.reason, "reason must be set");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason cannot be blank");
        }
    }

    /**
     * Returns a new builder with {@link #getAttemptNumber() attemptNumber} defaulted to {@code 1}.
     *
     * @return builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return opaque envelope id (never null or blank)
     */
    public String getEnvelopeId() {
        return envelopeId;
    }

    /**
     * @return agent-scoped context id (never null)
     */
    public AgentRuntimeId getAgentRuntimeId() {
        return agentRuntimeId;
    }

    /**
     * @return the trigger that drives scheduling (never null)
     */
    public RewakeTrigger getTrigger() {
        return trigger;
    }

    /**
     * @return event-type token of the event being replayed (never null)
     */
    public HookEventType<? extends ExecutionHook<?>> getOriginalEventType() {
        return originalEventType;
    }

    /**
     * @return stable id of the hook that emitted the spec (never null or blank)
     */
    public String getOriginatingHookId() {
        return originatingHookId;
    }

    /**
     * @return optional tool name; empty for lifecycle events
     */
    public Optional<String> getOriginalToolName() {
        return Optional.ofNullable(originalToolName);
    }

    /**
     * @return optional captured tool input; empty for lifecycle events
     */
    public Optional<ToolInput> getOriginalToolInput() {
        return Optional.ofNullable(originalToolInput);
    }

    /**
     * @return 1-based fire counter (always &gt;= 1)
     */
    public int getAttemptNumber() {
        return attemptNumber;
    }

    /**
     * @return cap on the total fires for this envelope (always &gt;= 1)
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * @return hard upper bound on the rewake window measured from {@link #getFirstScheduledAt() firstScheduledAt}
     *         (never null, always strictly positive)
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * @return wall-clock timestamp when the envelope was first persisted (never null)
     */
    public Instant getFirstScheduledAt() {
        return firstScheduledAt;
    }

    /**
     * @return verbatim copy of the spec payload (never null; possibly empty)
     */
    public Map<String, String> getPayload() {
        return payload;
    }

    /**
     * @return human-readable reason carried over from the spec (never null or blank)
     */
    public String getReason() {
        return reason;
    }

    /**
     * Returns a copy of this envelope with {@link #getAttemptNumber() attemptNumber} incremented by one. Used by the
     * rewake executor when scheduling the next firing.
     *
     * @return next-attempt envelope (never null)
     */
    public RewakeEnvelope withIncrementedAttempt() {
        return toBuilder().attemptNumber(attemptNumber + 1).build();
    }

    /**
     * Returns a builder pre-populated with this envelope's fields. Useful for evolving envelopes (e.g. incrementing the
     * attempt counter) without losing the immutability guarantee.
     *
     * @return builder (never null)
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.envelopeId = envelopeId;
        b.agentRuntimeId = agentRuntimeId;
        b.trigger = trigger;
        b.originalEventType = originalEventType;
        b.originatingHookId = originatingHookId;
        b.originalToolName = originalToolName;
        b.originalToolInput = originalToolInput;
        b.attemptNumber = attemptNumber;
        b.maxAttempts = maxAttempts;
        b.timeout = timeout;
        b.firstScheduledAt = firstScheduledAt;
        b.payload.putAll(payload);
        b.reason = reason;
        return b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RewakeEnvelope that)) {
            return false;
        }
        return attemptNumber == that.attemptNumber && maxAttempts == that.maxAttempts
                && envelopeId.equals(that.envelopeId) && agentRuntimeId.equals(that.agentRuntimeId)
                && trigger.equals(that.trigger) && originalEventType.equals(that.originalEventType)
                && originatingHookId.equals(that.originatingHookId)
                && Objects.equals(originalToolName, that.originalToolName)
                && Objects.equals(originalToolInput, that.originalToolInput) && timeout.equals(that.timeout)
                && firstScheduledAt.equals(that.firstScheduledAt) && payload.equals(that.payload)
                && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(envelopeId, agentRuntimeId, trigger, originalEventType, originatingHookId, originalToolName,
                originalToolInput, attemptNumber, maxAttempts, timeout, firstScheduledAt, payload, reason);
    }

    @Override
    public String toString() {
        return "RewakeEnvelope{envelopeId='" + envelopeId + "', agentRuntimeId=" + agentRuntimeId + ", trigger="
                + trigger + ", originalEventType=" + originalEventType + ", originatingHookId='" + originatingHookId
                + "', originalToolName=" + originalToolName + ", attemptNumber=" + attemptNumber + ", maxAttempts="
                + maxAttempts + ", timeout=" + timeout + ", firstScheduledAt=" + firstScheduledAt + ", reason='"
                + reason + "'}";
    }

    /** Builder for {@link RewakeEnvelope}. */
    public static final class Builder {
        private String envelopeId;
        private AgentRuntimeId agentRuntimeId;
        private RewakeTrigger trigger;
        private HookEventType<? extends ExecutionHook<?>> originalEventType;
        private String originatingHookId;
        private String originalToolName;
        private ToolInput originalToolInput;
        private int attemptNumber = 1;
        private int maxAttempts = RewakeSpec.DEFAULT_MAX_ATTEMPTS;
        private Duration timeout = RewakeSpec.DEFAULT_TIMEOUT;
        private Instant firstScheduledAt;
        private final Map<String, String> payload = new LinkedHashMap<>();
        private String reason;

        private Builder() {
        }

        /**
         * Sets the envelope id (required).
         *
         * @param envelopeId
         *            opaque id (must not be null or blank)
         * @return this builder
         */
        public Builder envelopeId(String envelopeId) {
            this.envelopeId = Objects.requireNonNull(envelopeId, "envelopeId cannot be null");
            return this;
        }

        /**
         * Sets the agent-scoped context id (required).
         *
         * @param agentRuntimeId
         *            context id (must not be null)
         * @return this builder
         */
        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
            return this;
        }

        /**
         * Sets the trigger that drives scheduling (required).
         *
         * @param trigger
         *            trigger (must not be null)
         * @return this builder
         */
        public Builder trigger(RewakeTrigger trigger) {
            this.trigger = Objects.requireNonNull(trigger, "trigger cannot be null");
            return this;
        }

        /**
         * Sets the event type being replayed (required).
         *
         * @param originalEventType
         *            event type token (must not be null)
         * @return this builder
         */
        public Builder originalEventType(HookEventType<? extends ExecutionHook<?>> originalEventType) {
            this.originalEventType = Objects.requireNonNull(originalEventType, "originalEventType cannot be null");
            return this;
        }

        /**
         * Sets the originating hook id (required).
         *
         * @param originatingHookId
         *            stable hook id (must not be null or blank)
         * @return this builder
         */
        public Builder originatingHookId(String originatingHookId) {
            this.originatingHookId = Objects.requireNonNull(originatingHookId, "originatingHookId cannot be null");
            return this;
        }

        /**
         * Sets the captured tool name (optional; only for tool-scoped events).
         *
         * @param originalToolName
         *            tool name (may be null)
         * @return this builder
         */
        public Builder originalToolName(String originalToolName) {
            this.originalToolName = originalToolName;
            return this;
        }

        /**
         * Sets the captured tool input (optional; only for tool-scoped events).
         *
         * @param originalToolInput
         *            tool input (may be null)
         * @return this builder
         */
        public Builder originalToolInput(ToolInput originalToolInput) {
            this.originalToolInput = originalToolInput;
            return this;
        }

        /**
         * Sets the 1-based attempt counter (defaults to {@code 1}).
         *
         * @param attemptNumber
         *            counter (must be &gt;= 1)
         * @return this builder
         */
        public Builder attemptNumber(int attemptNumber) {
            this.attemptNumber = attemptNumber;
            return this;
        }

        /**
         * Sets the cap on total fires for this envelope (defaults to
         * {@link RewakeSpec#DEFAULT_MAX_ATTEMPTS}).
         *
         * @param maxAttempts
         *            cap (must be &gt;= 1)
         * @return this builder
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the hard upper bound on the rewake window (defaults to
         * {@link RewakeSpec#DEFAULT_TIMEOUT}).
         *
         * @param timeout
         *            timeout (must not be null and must be strictly positive)
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout cannot be null");
            return this;
        }

        /**
         * Sets the first-scheduled timestamp (required).
         *
         * @param firstScheduledAt
         *            wall-clock instant (must not be null)
         * @return this builder
         */
        public Builder firstScheduledAt(Instant firstScheduledAt) {
            this.firstScheduledAt = Objects.requireNonNull(firstScheduledAt, "firstScheduledAt cannot be null");
            return this;
        }

        /**
         * Adds a single payload entry. Existing keys are overwritten.
         *
         * @param key
         *            payload key (must not be null)
         * @param value
         *            payload value (must not be null)
         * @return this builder
         */
        public Builder payload(String key, String value) {
            Objects.requireNonNull(key, "payload key cannot be null");
            Objects.requireNonNull(value, "payload value cannot be null");
            this.payload.put(key, value);
            return this;
        }

        /**
         * Adds every entry from {@code entries} (defensive copy taken at build).
         *
         * @param entries
         *            payload entries (must not be null; values must not be null)
         * @return this builder
         */
        public Builder payload(Map<String, String> entries) {
            Objects.requireNonNull(entries, "entries cannot be null");
            entries.forEach(this::payload);
            return this;
        }

        /**
         * Sets the human-readable reason (required).
         *
         * @param reason
         *            reason (must not be null or blank)
         * @return this builder
         */
        public Builder reason(String reason) {
            this.reason = Objects.requireNonNull(reason, "reason cannot be null");
            return this;
        }

        /**
         * Builds the envelope.
         *
         * @return new {@link RewakeEnvelope} (never null)
         * @throws NullPointerException
         *             if a required field was not set
         * @throws IllegalArgumentException
         *             on blank id / reason or non-positive attempt number
         */
        public RewakeEnvelope build() {
            return new RewakeEnvelope(this);
        }
    }
}
