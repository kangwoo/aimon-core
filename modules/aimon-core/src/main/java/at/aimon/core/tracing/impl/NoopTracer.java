package at.aimon.core.tracing.impl;

import java.util.Map;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Tracer;

/**
 * A zero-overhead {@link Tracer} that records nothing. The framework default when tracing is disabled, so tracing-off
 * has no behavioural effect.
 */
public final class NoopTracer implements Tracer {

    /** Shared instance. */
    public static final NoopTracer INSTANCE = new NoopTracer();

    private static final SpanContext EMPTY_CONTEXT = SpanContext.of("", "", "", null);
    private static final Span NOOP_SPAN = new NoopSpan();

    private NoopTracer() {
    }

    @Override
    public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
        return NOOP_SPAN;
    }

    @Override
    public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
        return NOOP_SPAN;
    }

    private static final class NoopSpan implements Span {

        @Override
        public SpanContext context() {
            return EMPTY_CONTEXT;
        }

        @Override
        public LlmCallMetadata enrich(LlmCallMetadata base) {
            return base;
        }

        @Override
        public void setOutputs(Object outputs) {
            // no-op
        }

        @Override
        public void setTokenUsage(TokenUsage usage) {
            // no-op
        }

        @Override
        public void setModel(String model) {
            // no-op
        }

        @Override
        public void setAttribute(String key, String value) {
            // no-op
        }

        @Override
        public void error(Throwable t) {
            // no-op
        }

        @Override
        public void error(String message) {
            // no-op
        }

        @Override
        public void interrupted() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
