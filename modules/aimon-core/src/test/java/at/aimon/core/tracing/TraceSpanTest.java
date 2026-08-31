package at.aimon.core.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.TokenUsage;

class TraceSpanTest {

    @Test
    @DisplayName("builder retains all fields and copies attributes immutably")
    void builderRetainsFields() {
        final Instant start = Instant.parse("2026-06-12T00:00:00Z");
        final Instant end = Instant.parse("2026-06-12T00:00:01Z");

        final TraceSpan span = TraceSpan.builder().sessionId("conv-1").traceId("trace-1").spanId("span-1")
                .parentSpanId("parent-1").type(SpanType.LLM).name("llm:gpt-4").startTime(start).endTime(end)
                .status(SpanStatus.OK).inputs(Map.of("messages", 3)).outputs(Map.of("textChars", 42))
                .tokenUsage(TokenUsage.of(10, 5, 15)).model("gpt-4").attributes(Map.of("iteration", "2")).build();

        assertThat(span.getSessionId()).isEqualTo("conv-1");
        assertThat(span.getTraceId()).isEqualTo("trace-1");
        assertThat(span.getSpanId()).isEqualTo("span-1");
        assertThat(span.getParentSpanId()).contains("parent-1");
        assertThat(span.getType()).isEqualTo(SpanType.LLM);
        assertThat(span.getModel()).contains("gpt-4");
        assertThat(span.getTokenUsage()).contains(TokenUsage.of(10, 5, 15));
        assertThat(span.getAttributes()).containsEntry("iteration", "2");
        assertThat(span.latency()).contains(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("latency is empty until the span has ended")
    void latencyEmptyWhenNotEnded() {
        final TraceSpan span = TraceSpan.builder().sessionId("s").traceId("t").spanId("span-1").type(SpanType.TURN)
                .name("turn").startTime(Instant.now()).build();

        assertThat(span.getEndTime()).isEmpty();
        assertThat(span.latency()).isEmpty();
        assertThat(span.getStatus()).isEqualTo(SpanStatus.OK);
    }

    @Test
    @DisplayName("equality is by span id")
    void equalityBySpanId() {
        final TraceSpan.Builder base = TraceSpan.builder().sessionId("s").traceId("t").type(SpanType.TOOL).name("Read")
                .startTime(Instant.now());
        final TraceSpan a = base.spanId("span-x").build();
        final TraceSpan b = TraceSpan.builder().sessionId("other").traceId("other").type(SpanType.LLM).name("llm")
                .startTime(Instant.now()).spanId("span-x").build();
        final TraceSpan c = base.spanId("span-y").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("missing required field is rejected at build")
    void missingRequiredFieldRejected() {
        assertThatThrownBy(() -> TraceSpan.builder().sessionId("s").traceId("t").type(SpanType.TURN).name("turn")
                .startTime(Instant.now()).build()).isInstanceOf(NullPointerException.class);
    }
}
