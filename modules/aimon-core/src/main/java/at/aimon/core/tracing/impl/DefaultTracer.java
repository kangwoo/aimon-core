package at.aimon.core.tracing.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanRedactor;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.TraceSpanStore;
import at.aimon.core.tracing.Tracer;

/**
 * Default {@link Tracer}: stamps span ids and timings, then on {@link Span#close()} records the immutable
 * {@link TraceSpan} to a {@link TraceSpanStore} and exports it via a {@link at.aimon.core.tracing.SpanExporter}.
 *
 * <p>
 * Fail-safe: {@link Span#close()} swallows all errors (WARN logged) so tracing never breaks execution. {@link Clock}
 * and
 * the id generator are injectable for deterministic tests.
 *
 * <p>
 * TRACE-02: an optional {@link SpanRedactor} (default {@link SpanRedactor#noop()}) masks the {@code inputs}/
 * {@code outputs} snapshot just before the span is recorded, so secret-bearing tool inputs and captured content are
 * masked uniformly across span types.
 */
public final class DefaultTracer implements Tracer {

    private static final Logger log = LoggerFactory.getLogger(DefaultTracer.class);

    private final TraceSpanStore store;
    private final SpanExporter exporter;
    private final SpanRedactor redactor;
    private final Clock clock;
    private final Supplier<String> idGenerator;

    /**
     * Creates a tracer with a system clock and random UUID span ids.
     *
     * @param store
     *            the span store (must not be null)
     * @param exporter
     *            the span exporter (must not be null)
     */
    public DefaultTracer(TraceSpanStore store, SpanExporter exporter) {
        this(store, exporter, SpanRedactor.noop(), Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    /**
     * Creates a tracer with the given redactor, a system clock and random UUID span ids.
     *
     * @param store
     *            the span store (must not be null)
     * @param exporter
     *            the span exporter (must not be null)
     * @param redactor
     *            the redactor applied to inputs/outputs at record time (must not be null; use
     *            {@link SpanRedactor#noop()} for no masking)
     */
    public DefaultTracer(TraceSpanStore store, SpanExporter exporter, SpanRedactor redactor) {
        this(store, exporter, redactor, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    /**
     * @param store
     *            the span store (must not be null)
     * @param exporter
     *            the span exporter (must not be null)
     * @param clock
     *            the clock for span timings (must not be null)
     * @param idGenerator
     *            supplier of unique span ids (must not be null)
     */
    public DefaultTracer(TraceSpanStore store, SpanExporter exporter, Clock clock, Supplier<String> idGenerator) {
        this(store, exporter, SpanRedactor.noop(), clock, idGenerator);
    }

    /**
     * @param store
     *            the span store (must not be null)
     * @param exporter
     *            the span exporter (must not be null)
     * @param redactor
     *            the redactor applied to inputs/outputs at record time (must not be null)
     * @param clock
     *            the clock for span timings (must not be null)
     * @param idGenerator
     *            supplier of unique span ids (must not be null)
     */
    public DefaultTracer(TraceSpanStore store, SpanExporter exporter, SpanRedactor redactor, Clock clock,
            Supplier<String> idGenerator) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.exporter = Objects.requireNonNull(exporter, "exporter cannot be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator cannot be null");
    }

    @Override
    public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        final SpanContext context = SpanContext.root(sessionId, idGenerator.get());
        return new DefaultSpan(context, type, name, inputs);
    }

    @Override
    public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
        Objects.requireNonNull(parent, "parent cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        return new DefaultSpan(parent.child(idGenerator.get()), type, name, inputs);
    }

    private final class DefaultSpan implements Span {

        private final SpanContext context;
        private final SpanType type;
        private final String name;
        private final Instant startTime;
        private final Object inputs;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private volatile Object outputs;
        private volatile TokenUsage tokenUsage;
        private volatile String model;
        private volatile SpanStatus status = SpanStatus.OK;
        private volatile String errorMessage;

        private DefaultSpan(SpanContext context, SpanType type, String name, Map<String, Object> inputs) {
            this.context = context;
            this.type = type;
            this.name = name;
            this.startTime = clock.instant();
            this.inputs = inputs;
        }

        @Override
        public SpanContext context() {
            return context;
        }

        @Override
        public LlmCallMetadata enrich(LlmCallMetadata base) {
            return context.writeInto(base);
        }

        @Override
        public void setOutputs(Object outputs) {
            this.outputs = outputs;
        }

        @Override
        public void setTokenUsage(TokenUsage usage) {
            this.tokenUsage = usage;
        }

        @Override
        public void setModel(String model) {
            this.model = model;
        }

        @Override
        public synchronized void setAttribute(String key, String value) {
            Objects.requireNonNull(key, "key cannot be null");
            Objects.requireNonNull(value, "value cannot be null");
            attributes.put(key, value);
        }

        @Override
        public void error(Throwable t) {
            this.status = SpanStatus.ERROR;
            this.errorMessage = t == null ? null : t.getMessage();
        }

        @Override
        public void error(String message) {
            this.status = SpanStatus.ERROR;
            this.errorMessage = message;
        }

        @Override
        public void interrupted() {
            this.status = SpanStatus.INTERRUPTED;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                final TraceSpan span;
                synchronized (this) {
                    span = TraceSpan.builder().sessionId(context.getSessionId()).traceId(context.getTraceId())
                            .spanId(context.getSpanId()).parentSpanId(context.getParentSpanId().orElse(null)).type(type)
                            .name(name).startTime(startTime).endTime(clock.instant()).status(status)
                            .inputs(redactor.redact(inputs)).outputs(redactor.redact(outputs))
                            .errorMessage(errorMessage).tokenUsage(tokenUsage).model(model)
                            .attributes(new LinkedHashMap<>(attributes)).build();
                }
                store.record(span);
                exporter.export(span);
            } catch (RuntimeException e) {
                log.warn("Failed to close trace span (ignored): {}", e.getMessage());
            }
        }
    }
}
