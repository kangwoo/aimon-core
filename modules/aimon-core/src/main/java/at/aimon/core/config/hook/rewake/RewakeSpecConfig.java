package at.aimon.core.config.hook.rewake;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jackson-binding DTO for the {@code asyncRewake} block on a {@code hooks.json} handler entry.
 *
 * <p>
 * The block carries:
 * <ul>
 * <li>{@link #getTrigger() trigger} — required tagged-union object selecting one of {@code delay} / {@code cron} /
 * {@code event}. The {@link RewakeSpecParser} validates that exactly one shape is present.
 * <li>{@link #getTimeout() timeout} — optional duration string (shorthand like {@code "5m"} / {@code "1h30m"} or
 * ISO-8601 like {@code "PT1H"}). Defaults to {@link at.aimon.core.hook.rewake.RewakeSpec#DEFAULT_TIMEOUT}.
 * <li>{@link #getMaxAttempts() maxAttempts} — optional cap on rewake fires; defaults to
 * {@link at.aimon.core.hook.rewake.RewakeSpec#DEFAULT_MAX_ATTEMPTS}.
 * <li>{@link #getPayload() payload} — optional opaque map carried verbatim into the
 * {@link at.aimon.core.hook.rewake.RewakeEnvelope RewakeEnvelope}.
 * <li>{@link #getReason() reason} — required human-readable reason surfaced in logs.
 * </ul>
 *
 * <p>
 * The DTO is a wire-format mirror only — duration parsing, trigger-shape validation, and conversion to the runtime
 * {@link at.aimon.core.hook.rewake.RewakeSpec RewakeSpec} value object live in {@link RewakeSpecParser}. Unknown JSON
 * fields are tolerated (forwards-compat hatch for follow-up rewake fields).
 *
 * <p>
 * Immutable; thread-safe.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RewakeSpecConfig {

    private final TriggerConfig trigger;
    private final String timeout;
    private final Integer maxAttempts;
    private final Map<String, String> payload;
    private final String reason;

    private RewakeSpecConfig(Builder b) {
        this.trigger = b.trigger;
        this.timeout = b.timeout;
        this.maxAttempts = b.maxAttempts;
        this.payload = b.payload == null ? Map.of() : Map.copyOf(b.payload);
        this.reason = b.reason;
    }

    /**
     * @return the tagged-union trigger config (may be null when the JSON block omits it; the parser rejects that)
     */
    @JsonProperty("trigger")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public TriggerConfig getTrigger() {
        return trigger;
    }

    /**
     * @return the raw timeout string (may be null, in which case the parser falls back to the default)
     */
    @JsonProperty("timeout")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getTimeout() {
        return timeout;
    }

    /**
     * @return the optional max-attempts cap (may be null, in which case the parser falls back to the default)
     */
    @JsonProperty("maxAttempts")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * @return the immutable opaque payload map (never null; possibly empty)
     */
    @JsonProperty("payload")
    public Map<String, String> getPayload() {
        return payload;
    }

    /**
     * @return the human-readable reason (may be null in the wire form; the parser rejects null/blank)
     */
    @JsonProperty("reason")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getReason() {
        return reason;
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Jackson constructor.
     *
     * @param trigger
     *            the tagged-union trigger object
     * @param timeout
     *            optional duration string
     * @param maxAttempts
     *            optional max-attempts cap
     * @param payload
     *            optional opaque map
     * @param reason
     *            human-readable reason
     * @return spec config (never null)
     */
    @JsonCreator
    public static RewakeSpecConfig fromJson(@JsonProperty("trigger") TriggerConfig trigger,
            @JsonProperty("timeout") String timeout, @JsonProperty("maxAttempts") Integer maxAttempts,
            @JsonProperty("payload") Map<String, String> payload, @JsonProperty("reason") String reason) {
        return builder().trigger(trigger).timeout(timeout).maxAttempts(maxAttempts).payload(payload).reason(reason)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RewakeSpecConfig that)) {
            return false;
        }
        return Objects.equals(trigger, that.trigger) && Objects.equals(timeout, that.timeout)
                && Objects.equals(maxAttempts, that.maxAttempts) && payload.equals(that.payload)
                && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trigger, timeout, maxAttempts, payload, reason);
    }

    @Override
    public String toString() {
        return "RewakeSpecConfig{trigger=" + trigger + ", timeout='" + timeout + "', maxAttempts=" + maxAttempts
                + ", reason='" + reason + "'}";
    }

    /** Builder for {@link RewakeSpecConfig}. */
    public static final class Builder {
        private TriggerConfig trigger;
        private String timeout;
        private Integer maxAttempts;
        private Map<String, String> payload;
        private String reason;

        private Builder() {
        }

        /**
         * @param trigger
         *            tagged-union trigger config
         * @return this builder
         */
        public Builder trigger(TriggerConfig trigger) {
            this.trigger = trigger;
            return this;
        }

        /**
         * @param timeout
         *            raw duration string
         * @return this builder
         */
        public Builder timeout(String timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * @param maxAttempts
         *            max-attempts cap
         * @return this builder
         */
        public Builder maxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * @param payload
         *            opaque payload map
         * @return this builder
         */
        public Builder payload(Map<String, String> payload) {
            this.payload = payload;
            return this;
        }

        /**
         * @param reason
         *            human-readable reason
         * @return this builder
         */
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * @return new {@link RewakeSpecConfig}
         */
        public RewakeSpecConfig build() {
            return new RewakeSpecConfig(this);
        }
    }

    /**
     * Tagged-union DTO for the {@code trigger} sub-object. Exactly one of {@code delay} / {@code cron} / {@code event}
     * must be set; the parser rejects ambiguous or empty configurations.
     *
     * <h2>JSON shapes</h2>
     *
     * <pre>
     * { "delay": "5m" }
     * { "cron":  "*&#47;5 * * * *", "zone": "Asia/Tokyo" }     // five-field; zone optional, defaults UTC
     * { "event": { "type": "webhook", "key": "ticket-${tool_input.id}" } }
     * </pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TriggerConfig {
        private final String delay;
        private final String cron;
        private final String zone;
        private final EventConfig event;

        /**
         * Jackson constructor.
         *
         * @param delay
         *            duration string for one-shot delay triggers
         * @param cron
         *            cron expression for recurring triggers
         * @param zone
         *            time zone (only meaningful with cron; defaults UTC)
         * @param event
         *            event-trigger sub-object
         */
        @JsonCreator
        public TriggerConfig(@JsonProperty("delay") String delay, @JsonProperty("cron") String cron,
                @JsonProperty("zone") String zone, @JsonProperty("event") EventConfig event) {
            this.delay = delay;
            this.cron = cron;
            this.zone = zone;
            this.event = event;
        }

        /** @return one-shot delay string (may be null) */
        @JsonProperty("delay")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getDelay() {
            return delay;
        }

        /** @return cron expression (may be null) */
        @JsonProperty("cron")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getCron() {
            return cron;
        }

        /** @return cron time zone (may be null) */
        @JsonProperty("zone")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getZone() {
            return zone;
        }

        /** @return event-trigger sub-object (may be null) */
        @JsonProperty("event")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public EventConfig getEvent() {
            return event;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TriggerConfig that)) {
                return false;
            }
            return Objects.equals(delay, that.delay) && Objects.equals(cron, that.cron)
                    && Objects.equals(zone, that.zone) && Objects.equals(event, that.event);
        }

        @Override
        public int hashCode() {
            return Objects.hash(delay, cron, zone, event);
        }

        @Override
        public String toString() {
            return "TriggerConfig{delay='" + delay + "', cron='" + cron + "', zone='" + zone + "', event=" + event
                    + '}';
        }
    }

    /** DTO for the {@code event} sub-object inside {@link TriggerConfig}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class EventConfig {
        private final String type;
        private final String key;

        /**
         * Jackson constructor.
         *
         * @param type
         *            channel discriminator (must not be null/blank in the parsed form)
         * @param key
         *            opaque event key (must not be null/blank in the parsed form)
         */
        @JsonCreator
        public EventConfig(@JsonProperty("type") String type, @JsonProperty("key") String key) {
            this.type = type;
            this.key = key;
        }

        /** @return channel discriminator (may be null in the wire form) */
        @JsonProperty("type")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getType() {
            return type;
        }

        /** @return opaque event key (may be null in the wire form) */
        @JsonProperty("key")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getKey() {
            return key;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof EventConfig that)) {
                return false;
            }
            return Objects.equals(type, that.type) && Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, key);
        }

        @Override
        public String toString() {
            return "EventConfig{type='" + type + "', key='" + key + "'}";
        }
    }
}
