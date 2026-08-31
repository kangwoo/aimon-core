package at.aimon.core.tracing.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;

class InMemoryTraceSpanStoreTest {

    @Test
    @DisplayName("record then get/byTrace/bySession resolve the span")
    void recordAndQuery() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        store.record(span("conv-1", "trace-1", "span-1", null, Instant.parse("2026-06-12T00:00:00Z")));
        store.record(span("conv-1", "trace-1", "span-2", "span-1", Instant.parse("2026-06-12T00:00:01Z")));
        store.record(span("conv-1", "trace-2", "span-3", null, Instant.parse("2026-06-12T00:01:00Z")));

        assertThat(store.get("span-2")).isPresent();
        assertThat(store.byTrace("trace-1")).extracting(TraceSpan::getSpanId).containsExactly("span-1", "span-2");
        assertThat(store.byTrace("trace-2")).extracting(TraceSpan::getSpanId).containsExactly("span-3");
        assertThat(store.bySession("conv-1")).hasSize(3);
        assertThat(store.bySession("unknown")).isEmpty();
    }

    @Test
    @DisplayName("byTrace is ordered by start time")
    void byTraceOrderedByStart() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        store.record(span("c", "t", "late", null, Instant.parse("2026-06-12T00:00:05Z")));
        store.record(span("c", "t", "early", null, Instant.parse("2026-06-12T00:00:01Z")));

        assertThat(store.byTrace("t")).extracting(TraceSpan::getSpanId).containsExactly("early", "late");
    }

    @Test
    @DisplayName("bounded: oldest spans are evicted past capacity")
    void evictsOldestOverCapacity() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore(2);
        store.record(span("c", "t", "span-1", null, Instant.parse("2026-06-12T00:00:00Z")));
        store.record(span("c", "t", "span-2", null, Instant.parse("2026-06-12T00:00:01Z")));
        store.record(span("c", "t", "span-3", null, Instant.parse("2026-06-12T00:00:02Z")));

        assertThat(store.get("span-1")).isEmpty();
        assertThat(store.get("span-2")).isPresent();
        assertThat(store.get("span-3")).isPresent();
        assertThat(store.byTrace("t")).extracting(TraceSpan::getSpanId).containsExactly("span-2", "span-3");
    }

    @Test
    @DisplayName("eviction stays consistent across repeated over-capacity inserts")
    void repeatedEvictionConsistent() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore(2);
        for (int i = 1; i <= 5; i++) {
            store.record(span("c", "t", "span-" + i, null, Instant.parse("2026-06-12T00:00:0" + i + "Z")));
        }

        // Only the last two survive, in insertion order — the FIFO queue was not corrupted by the index/queue split.
        assertThat(store.byTrace("t")).extracting(TraceSpan::getSpanId).containsExactly("span-4", "span-5");
        assertThat(store.get("span-3")).isEmpty();
        assertThat(store.bySession("c")).hasSize(2);
    }

    @Test
    @DisplayName("deleteOlderThan followed by eviction does not resurrect deleted spans")
    void deleteThenEvictNoResurrection() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore(2);
        store.record(span("c", "t", "old", null, Instant.parse("2026-06-12T00:00:00Z")));
        store.deleteOlderThan(Instant.parse("2026-06-12T00:30:00Z"));
        // "old" was removed from both indexes and the queue; subsequent inserts must not evict it again.
        store.record(span("c", "t", "a", null, Instant.parse("2026-06-12T01:00:00Z")));
        store.record(span("c", "t", "b", null, Instant.parse("2026-06-12T01:00:01Z")));

        assertThat(store.byTrace("t")).extracting(TraceSpan::getSpanId).containsExactly("a", "b");
    }

    @Test
    @DisplayName("deleteOlderThan removes spans ended before the cutoff")
    void deleteOlderThan() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        store.record(span("c", "t", "old", null, Instant.parse("2026-06-12T00:00:00Z")));
        store.record(span("c", "t", "new", null, Instant.parse("2026-06-12T01:00:00Z")));

        store.deleteOlderThan(Instant.parse("2026-06-12T00:30:00Z"));

        assertThat(store.get("old")).isEmpty();
        assertThat(store.get("new")).isPresent();
        assertThat(store.byTrace("t")).extracting(TraceSpan::getSpanId).containsExactly("new");
    }

    @Test
    @DisplayName("record is null-safe")
    void recordNullSafe() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        store.record(null);
        assertThat(store.bySession("c")).isEmpty();
    }

    private static TraceSpan span(String session, String trace, String id, String parent, Instant start) {
        return TraceSpan.builder().sessionId(session).traceId(trace).spanId(id).parentSpanId(parent).type(SpanType.TOOL)
                .name("Read").startTime(start).endTime(start.plusMillis(10)).build();
    }
}
