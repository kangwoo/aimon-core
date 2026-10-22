package at.aimon.core.tools.monitor;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.tools.bash.BackgroundBashOwner;

/**
 * What a monitor was asked for: the kind of source and its parameters, who asked, and for how long.
 *
 * <p>
 * Built by the {@code Monitor} tool from the model's call and passed to {@link MonitorManager#start(MonitorRequest)},
 * which hands it to the kind's {@link MonitorSourceFactory}. Immutable; thread-safe.
 */
public final class MonitorRequest {

    private final BackgroundBashOwner owner;
    private final String kind;
    private final Map<String, Object> params;
    private final String description;
    private final Duration timeout;
    private final ToolContext context;

    private MonitorRequest(Builder builder) {
        this.owner = Objects.requireNonNull(builder.owner, "owner must not be null");
        this.kind = requireText(builder.kind, "kind");
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(builder.params));
        this.description = requireText(builder.description, "description");
        this.timeout = builder.timeout;
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.context = Objects.requireNonNull(builder.context, "context must not be null");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return who the monitor is started for; its events go to that owner's session (never null)
     */
    public BackgroundBashOwner getOwner() {
        return owner;
    }

    /**
     * @return the kind of source, naming its {@link MonitorSourceFactory} (never null)
     */
    public String getKind() {
        return kind;
    }

    /**
     * The kind's parameters as the model gave them — {@code command} for {@code shell}, whatever a host kind takes.
     * Values are what JSON decoding produced, so a factory checks their types.
     *
     * @return the parameters (unmodifiable, never null)
     */
    public Map<String, Object> getParams() {
        return params;
    }

    /**
     * Reads a string parameter.
     *
     * @param name
     *            the parameter
     * @return its value when present and a string, otherwise empty
     */
    public Optional<String> getStringParam(String name) {
        return params.get(name) instanceof String value ? Optional.of(value) : Optional.empty();
    }

    /**
     * @return the model's short description of what is watched, shown with every event (never null)
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return how long the monitor may run, or empty for the manager's default
     */
    public Optional<Duration> getTimeout() {
        return Optional.ofNullable(timeout);
    }

    /**
     * The calling tool context, for a factory that needs the execution's environment or identity. A source must not
     * keep it beyond what it reads at creation: the context belongs to one call.
     *
     * @return the context (never null)
     */
    public ToolContext getContext() {
        return context;
    }

    /** Builder for {@link MonitorRequest}. */
    public static final class Builder {

        private BackgroundBashOwner owner;
        private String kind;
        private final Map<String, Object> params = new LinkedHashMap<>();
        private String description;
        private Duration timeout;
        private ToolContext context = ToolContext.empty();

        private Builder() {
        }

        /**
         * @param owner
         *            who the monitor is started for (must not be null)
         * @return this builder
         */
        public Builder owner(BackgroundBashOwner owner) {
            this.owner = owner;
            return this;
        }

        /**
         * @param kind
         *            the kind of source (must not be blank)
         * @return this builder
         */
        public Builder kind(String kind) {
            this.kind = kind;
            return this;
        }

        /**
         * Adds one parameter; a null value is left out.
         *
         * @param name
         *            the parameter (must not be null)
         * @param value
         *            its value, or null
         * @return this builder
         */
        public Builder param(String name, Object value) {
            Objects.requireNonNull(name, "name must not be null");
            if (value != null) {
                params.put(name, value);
            }
            return this;
        }

        /**
         * Adds every entry of {@code values}; null values are left out.
         *
         * @param values
         *            the parameters (must not be null)
         * @return this builder
         */
        public Builder params(Map<String, ?> values) {
            Objects.requireNonNull(values, "values must not be null").forEach(this::param);
            return this;
        }

        /**
         * @param description
         *            what is watched (must not be blank)
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * @param timeout
         *            how long the monitor may run, or null for the manager's default; must be positive
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * @param context
         *            the calling tool context (must not be null); defaults to an empty context
         * @return this builder
         */
        public Builder context(ToolContext context) {
            this.context = context;
            return this;
        }

        /**
         * @return the request
         * @throws NullPointerException
         *             if a required field is missing
         * @throws IllegalArgumentException
         *             if a text field is blank or the timeout is not positive
         */
        public MonitorRequest build() {
            return new MonitorRequest(this);
        }
    }
}
