package at.aimon.core.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

class SpansTest {

    @Test
    @DisplayName("traceCarrier keeps the group key and the two reserved tags, and nothing else")
    void traceCarrierKeepsOnlyTheSpanPosition() {
        final LlmCallMetadata enriched = SpanContext.root("session-1", "trace-1").child("span-iter")
                .writeInto(LlmCallMetadata.builder().component("orca-agent").parentComponent("parent")
                        .feature(LlmCallMetadata.Feature.REACT_LOOP).principal(Principal.user("alice"))
                        .traceId("session-1").tag("custom", "value").build());

        final LlmCallMetadata carrier = Spans.traceCarrier(enriched).orElseThrow();

        assertThat(carrier.getTraceId()).contains("session-1");
        assertThat(carrier.getTags()).containsOnly(Map.entry(SpanContext.TAG_TRACE_ID, "trace-1"),
                Map.entry(SpanContext.TAG_PARENT_SPAN_ID, "span-iter"));
        assertThat(carrier.getComponent()).isEmpty();
        assertThat(carrier.getParentComponent()).isEmpty();
        assertThat(carrier.getFeature()).isEmpty();
        assertThat(carrier.getPrincipal()).isEmpty();
        assertThat(SpanContext.readFrom(carrier)).isEqualTo(SpanContext.readFrom(enriched));
    }

    @Test
    @DisplayName("traceCarrier of metadata with no group key carries the tags alone")
    void traceCarrierWithoutGroupKey() {
        final LlmCallMetadata enriched = SpanContext.root("", "trace-1").writeInto(LlmCallMetadata.empty());

        final LlmCallMetadata carrier = Spans.traceCarrier(enriched).orElseThrow();

        assertThat(carrier.getTraceId()).isEmpty();
        assertThat(carrier.getTags()).containsKeys(SpanContext.TAG_TRACE_ID, SpanContext.TAG_PARENT_SPAN_ID);
    }

    @Test
    @DisplayName("traceCarrier is empty when the metadata names no parent span")
    void traceCarrierEmptyWithoutTags() {
        assertThat(Spans.traceCarrier(LlmCallMetadata.builder().traceId("session-1").build())).isEmpty();
        assertThat(Spans.traceCarrier(
                LlmCallMetadata.builder().traceId("session-1").tag(SpanContext.TAG_TRACE_ID, "trace-1").build()))
                .isEmpty();
    }

    @Test
    @DisplayName("startRootSafely starts a real root, and falls back to a no-op span when the tracer throws")
    void startRootSafely() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop(),
                Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC), () -> "root-1");

        try (Tracer.Span root = Spans.startRootSafely(tracer, "routine:t1", SpanType.ROUTINE, "routine:t1", null)) {
            assertThat(root.context().getTraceId()).isEqualTo("root-1");
        }
        assertThat(store.bySession("routine:t1")).singleElement()
                .satisfies(span -> assertThat(span.getType()).isEqualTo(SpanType.ROUTINE));

        final Tracer throwing = new Tracer() {
            @Override
            public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }

            @Override
            public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }
        };
        final Tracer.Span fallback = Spans.startRootSafely(throwing, "routine:t1", SpanType.ROUTINE, "routine:t1",
                null);
        final LlmCallMetadata base = LlmCallMetadata.builder().traceId("routine:t1").build();
        assertThat(fallback.enrich(base)).isSameAs(base);
        fallback.close();
    }

    @Test
    @DisplayName("each *Safely recording helper swallows a RuntimeException from the span")
    void safelySwallowsRuntimeExceptions() {
        final Tracer.Span span = throwingSpan(new IllegalStateException("span is broken"));
        Spans.setAttributeSafely(span, "k", "v");
        Spans.setAttributeQuietly(span, "k", "v");
        Spans.setOutputsSafely(span, Map.of());
        Spans.errorSafely(span, "message");
        Spans.errorSafely(span, new RuntimeException("work failed"));
        Spans.interruptedSafely(span);
        Spans.closeSafely(span);
        // twice: the second failure of an operation is logged at DEBUG, and still swallowed
        Spans.closeSafely(span);
    }

    @Test
    @DisplayName("each *Safely recording helper lets an Error through, as startChildSafely does")
    void safelyLetsErrorsThrough() {
        final Tracer.Span span = throwingSpan(new AssertionError("fatal"));
        final List<Runnable> operations = List.of(() -> Spans.setAttributeSafely(span, "k", "v"),
                () -> Spans.setAttributeQuietly(span, "k", "v"), () -> Spans.setOutputsSafely(span, null),
                () -> Spans.errorSafely(span, "m"), () -> Spans.errorSafely(span, new RuntimeException()),
                () -> Spans.interruptedSafely(span), () -> Spans.closeSafely(span));
        for (Runnable operation : operations) {
            assertThatThrownBy(operation::run).isInstanceOf(AssertionError.class).hasMessage("fatal");
        }
    }

    @Test
    @DisplayName("the *Safely helpers record on a working span")
    void safelyRecords() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());
        final Tracer.Span span = tracer.startRoot("s", SpanType.TOOL, "t", null);
        Spans.setAttributeSafely(span, "k", "v");
        Spans.setOutputsSafely(span, Map.of("o", 1));
        Spans.errorSafely(span, "bad");
        Spans.closeSafely(span);
        assertThat(store.bySession("s")).singleElement().satisfies(recorded -> {
            assertThat(recorded.getAttributes()).containsEntry("k", "v");
            assertThat(recorded.getStatus()).isEqualTo(SpanStatus.ERROR);
            assertThat(recorded.getOutputs()).isPresent();
        });
    }

    private static Tracer.Span throwingSpan(Throwable failure) {
        return (Tracer.Span) Proxy.newProxyInstance(Tracer.Span.class.getClassLoader(),
                new Class<?>[]{Tracer.Span.class}, (proxy, method, args) -> {
                    throw failure;
                });
    }
}
