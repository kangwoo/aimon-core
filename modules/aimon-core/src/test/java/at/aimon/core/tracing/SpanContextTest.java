package at.aimon.core.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmCallMetadata;

class SpanContextTest {

    @Test
    @DisplayName("root: traceId equals spanId and there is no parent")
    void rootHasNoParentAndTraceEqualsSpan() {
        final SpanContext root = SpanContext.root("conv-1", "span-root");

        assertThat(root.getSessionId()).isEqualTo("conv-1");
        assertThat(root.getTraceId()).isEqualTo("span-root");
        assertThat(root.getSpanId()).isEqualTo("span-root");
        assertThat(root.getParentSpanId()).isEmpty();
    }

    @Test
    @DisplayName("child: inherits session/trace and parents under the current span")
    void childInheritsTraceAndParents() {
        final SpanContext root = SpanContext.root("conv-1", "span-root");

        final SpanContext child = root.child("span-iter");

        assertThat(child.getSessionId()).isEqualTo("conv-1");
        assertThat(child.getTraceId()).isEqualTo("span-root");
        assertThat(child.getSpanId()).isEqualTo("span-iter");
        assertThat(child.getParentSpanId()).contains("span-root");
    }

    @Test
    @DisplayName("writeInto/readFrom round-trips the parent linkage via reserved tags")
    void writeIntoReadFromRoundTrip() {
        final SpanContext iteration = SpanContext.root("conv-1", "span-root").child("span-iter");
        // The session id lives in the existing metadata.traceId field, as the executor sets it.
        final LlmCallMetadata base = LlmCallMetadata.builder().component("orca-agent").traceId("conv-1").build();

        final LlmCallMetadata enriched = iteration.writeInto(base);

        assertThat(enriched.getComponent()).contains("orca-agent");
        assertThat(enriched.getTags()).containsEntry(SpanContext.TAG_TRACE_ID, "span-root")
                .containsEntry(SpanContext.TAG_PARENT_SPAN_ID, "span-iter");

        final Optional<SpanContext> parent = SpanContext.readFrom(enriched);
        assertThat(parent).isPresent();
        // A span created from the read context nests under the iteration span.
        assertThat(parent.get().getSessionId()).isEqualTo("conv-1");
        assertThat(parent.get().getTraceId()).isEqualTo("span-root");
        assertThat(parent.get().getSpanId()).isEqualTo("span-iter");

        final SpanContext llm = parent.get().child("span-llm");
        assertThat(llm.getParentSpanId()).contains("span-iter");
        assertThat(llm.getTraceId()).isEqualTo("span-root");
    }

    @Test
    @DisplayName("readFrom: empty when reserved tags are absent (call not enriched)")
    void readFromEmptyWhenNotEnriched() {
        final LlmCallMetadata bare = LlmCallMetadata.builder().component("orca-agent").traceId("conv-1").build();

        assertThat(SpanContext.readFrom(bare)).isEmpty();
    }

    @Test
    @DisplayName("readFrom: null metadata is rejected")
    void readFromRejectsNull() {
        assertThatThrownBy(() -> SpanContext.readFrom(null)).isInstanceOf(NullPointerException.class);
    }
}
