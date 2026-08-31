package at.aimon.core.hook.rewake;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hook-emitted directive describing how the framework should re-fire a hook later.
 *
 * <p>
 * A spec carries:
 * <ul>
 * <li>A {@link RewakeTrigger} — exactly one of delay / cron / external-event.
 * <li>An optional {@link #getTimeout() timeout} — hard upper bound on the rewake window. Required for cron and event
 * triggers (otherwise an unmatched event would linger forever); optional for delay triggers (where the trigger itself
 * fixes the firing time).
 * <li>{@link #getMaxAttempts() maxAttempts} — cap on how many times the rewake can refire (default 3). Hooks that
 * return {@code asyncRewake} again on a follow-up firing increment {@code attemptNumber}; once the cap is reached the
 * envelope is dropped and a WARN is logged.
 * <li>An opaque {@link #getPayload() payload} carried verbatim into the {@link RewakeEnvelope} for the resolver / hook
 * to inspect.
 * <li>A human-readable {@link #getReason() reason} surfaced in logs and {@code OnConfigReload} notifications.
 * </ul>
 *
 * <p>
 * Immutable; safe to share across threads. Build via {@link #builder()}.
 */
public final class RewakeSpec {

    /** Default {@link #getTimeout() timeout} when the caller does not specify one. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofHours(1);

    /** Default {@link #getMaxAttempts() maxAttempts} when the caller does not specify one. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final RewakeTrigger trigger;
    private final Duration timeout;
    private final int maxAttempts;
    private final Map<String, String> payload;
    private final String reason;

    private RewakeSpec(Builder builder) {
        this.trigger = Objects.requireNonNull(builder.trigger, "trigger must be set");
        this.timeout = Objects.requireNonNull(builder.timeout, "timeout must be set");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be strictly positive, got: " + timeout);
        }
        if (builder.maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + builder.maxAttempts);
        }
        this.maxAttempts = builder.maxAttempts;
        this.payload = Map.copyOf(builder.payload);
        this.reason = Objects.requireNonNull(builder.reason, "reason must be set");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason cannot be blank");
        }
    }

    /**
     * Returns a new builder pre-populated with default {@link #DEFAULT_TIMEOUT} and {@link #DEFAULT_MAX_ATTEMPTS}.
     *
     * @return builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the trigger.
     *
     * @return trigger (never null)
     */
    public RewakeTrigger getTrigger() {
        return trigger;
    }

    /**
     * Returns the hard upper bound on the rewake window.
     *
     * @return timeout (never null, always positive)
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Returns the cap on rewake attempts.
     *
     * @return max attempts (always &gt;= 1)
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Returns the opaque payload (defensive copy taken at construction).
     *
     * @return immutable map (never null; possibly empty)
     */
    public Map<String, String> getPayload() {
        return payload;
    }

    /**
     * Returns the human-readable reason.
     *
     * @return reason (never null or blank)
     */
    public String getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RewakeSpec that)) {
            return false;
        }
        return maxAttempts == that.maxAttempts && trigger.equals(that.trigger) && timeout.equals(that.timeout)
                && payload.equals(that.payload) && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trigger, timeout, maxAttempts, payload, reason);
    }

    @Override
    public String toString() {
        return "RewakeSpec{trigger=" + trigger + ", timeout=" + timeout + ", maxAttempts=" + maxAttempts + ", payload="
                + payload + ", reason='" + reason + "'}";
    }

    /** Builder for {@link RewakeSpec}. */
    public static final class Builder {
        private RewakeTrigger trigger;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private final Map<String, String> payload = new LinkedHashMap<>();
        private String reason;

        private Builder() {
        }

        /**
         * Sets the trigger (required).
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
         * Sets the timeout (defaults to {@link #DEFAULT_TIMEOUT}).
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
         * Sets the max attempts (defaults to {@link #DEFAULT_MAX_ATTEMPTS}).
         *
         * @param maxAttempts
         *            max attempts (must be &gt;= 1)
         * @return this builder
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
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
         * Builds the spec.
         *
         * @return new {@link RewakeSpec} (never null)
         * @throws NullPointerException
         *             if a required field was not set
         * @throws IllegalArgumentException
         *             on invalid timeout / maxAttempts / blank reason
         */
        public RewakeSpec build() {
            return new RewakeSpec(this);
        }
    }
}
