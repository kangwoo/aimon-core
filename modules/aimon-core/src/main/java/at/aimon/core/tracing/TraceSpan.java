package at.aimon.core.tracing;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.TokenUsage;

/**
 * Immutable record of a single completed (or terminal) unit of work within an agent execution trace.
 *
 * <p>
 * Spans form a tree linked by {@code traceId} (one turn) and {@code parentSpanId}; many turns of one session share
 * that session's {@code sessionId}. See the tracing design doc for the id model.
 *
 * <p>
 * Immutable value object built via {@link Builder}.
 */
public final class TraceSpan {

    private final String sessionId;
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final SpanType type;
    private final String name;
    private final Instant startTime;
    private final Instant endTime;
    private final SpanStatus status;
    private final Object inputs;
    private final Object outputs;
    private final String errorMessage;
    private final TokenUsage tokenUsage;
    private final String model;
    private final Map<String, String> attributes;

    private TraceSpan(Builder builder) {
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId cannot be null");
        this.traceId = Objects.requireNonNull(builder.traceId, "traceId cannot be null");
        this.spanId = Objects.requireNonNull(builder.spanId, "spanId cannot be null");
        this.parentSpanId = builder.parentSpanId;
        this.type = Objects.requireNonNull(builder.type, "type cannot be null");
        this.name = Objects.requireNonNull(builder.name, "name cannot be null");
        this.startTime = Objects.requireNonNull(builder.startTime, "startTime cannot be null");
        this.endTime = builder.endTime;
        this.status = Objects.requireNonNull(builder.status, "status cannot be null");
        this.inputs = builder.inputs;
        this.outputs = builder.outputs;
        this.errorMessage = builder.errorMessage;
        this.tokenUsage = builder.tokenUsage;
        this.model = builder.model;
        this.attributes = builder.attributes != null ? Map.copyOf(builder.attributes) : Map.of();
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
     * Returns the wall-clock latency of this span, or {@link Optional#empty()} if the span has not ended.
     *
     * @return the latency, or empty if not yet ended
     */
    public Optional<Duration> latency() {
        return endTime == null ? Optional.empty() : Optional.of(Duration.between(startTime, endTime));
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public Optional<String> getParentSpanId() {
        return Optional.ofNullable(parentSpanId);
    }

    public SpanType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Optional<Instant> getEndTime() {
        return Optional.ofNullable(endTime);
    }

    public SpanStatus getStatus() {
        return status;
    }

    public Optional<Object> getInputs() {
        return Optional.ofNullable(inputs);
    }

    public Optional<Object> getOutputs() {
        return Optional.ofNullable(outputs);
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public Optional<TokenUsage> getTokenUsage() {
        return Optional.ofNullable(tokenUsage);
    }

    public Optional<String> getModel() {
        return Optional.ofNullable(model);
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TraceSpan that = (TraceSpan) o;
        return spanId.equals(that.spanId);
    }

    @Override
    public int hashCode() {
        return spanId.hashCode();
    }

    @Override
    public String toString() {
        return "TraceSpan{type=" + type + ", name='" + name + "', spanId='" + spanId + "', parentSpanId='"
                + parentSpanId + "', traceId='" + traceId + "', status=" + status + "}";
    }

    /**
     * Builder for {@link TraceSpan}.
     */
    public static final class Builder {
        private String sessionId;
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private SpanType type;
        private String name;
        private Instant startTime;
        private Instant endTime;
        private SpanStatus status = SpanStatus.OK;
        private Object inputs;
        private Object outputs;
        private String errorMessage;
        private TokenUsage tokenUsage;
        private String model;
        private Map<String, String> attributes;

        private Builder() {
        }

        /** Sets the session id (= session id). */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** Sets the trace id (= turn root span id). */
        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        /** Sets this span's id. */
        public Builder spanId(String spanId) {
            this.spanId = spanId;
            return this;
        }

        /** Sets the parent span id (null for a root span). */
        public Builder parentSpanId(String parentSpanId) {
            this.parentSpanId = parentSpanId;
            return this;
        }

        /** Sets the span type. */
        public Builder type(SpanType type) {
            this.type = type;
            return this;
        }

        /** Sets the span name. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Sets the start time. */
        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        /** Sets the end time. */
        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        /** Sets the terminal status (defaults to {@link SpanStatus#OK}). */
        public Builder status(SpanStatus status) {
            this.status = status;
            return this;
        }

        /** Sets the input snapshot. */
        public Builder inputs(Object inputs) {
            this.inputs = inputs;
            return this;
        }

        /** Sets the output snapshot. */
        public Builder outputs(Object outputs) {
            this.outputs = outputs;
            return this;
        }

        /** Sets the error message (for {@link SpanStatus#ERROR}). */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /** Sets the token usage (LLM spans). */
        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        /** Sets the model name (LLM spans). */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the attribute map (copied defensively at build time). */
        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes;
            return this;
        }

        /**
         * Builds the {@link TraceSpan}.
         *
         * @return the immutable span
         */
        public TraceSpan build() {
            return new TraceSpan(this);
        }
    }
}
