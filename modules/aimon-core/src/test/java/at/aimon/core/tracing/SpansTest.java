package at.aimon.core.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
}
