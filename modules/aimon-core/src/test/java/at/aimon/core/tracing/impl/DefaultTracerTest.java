package at.aimon.core.tracing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.TokenUsage;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanRedactor;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.TraceSpanStore;
import at.aimon.core.tracing.Tracer;

class DefaultTracerTest {

    @Test
    @DisplayName("startRoot mints a root (trace == span, no parent) and records on close")
    void startRootRecordsOnClose() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final List<TraceSpan> exported = new ArrayList<>();
        final Tracer tracer = new DefaultTracer(store, exported::add,
                steppingClock(Instant.parse("2026-06-12T00:00:00Z"), Instant.parse("2026-06-12T00:00:02Z")),
                sequentialIds());

        final Tracer.Span span = tracer.startRoot("conv-1", SpanType.TURN, "turn", Map.of("user", "hi"));
        final SpanContext ctx = span.context();
        span.close();

        assertThat(ctx.getSpanId()).isEqualTo("span-1");
        assertThat(ctx.getTraceId()).isEqualTo("span-1");
        assertThat(ctx.getParentSpanId()).isEmpty();

        final TraceSpan recorded = store.get("span-1").orElseThrow();
        assertThat(recorded.getSessionId()).isEqualTo("conv-1");
        assertThat(recorded.getType()).isEqualTo(SpanType.TURN);
        assertThat(recorded.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(recorded.latency().orElseThrow().getSeconds()).isEqualTo(2);
        assertThat(exported).extracting(TraceSpan::getSpanId).containsExactly("span-1");
    }

    @Test
    @DisplayName("startChild nests under the parent and inherits trace/session")
    void startChildParents() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop(), fixedClock(), sequentialIds());

        final Tracer.Span root = tracer.startRoot("conv-1", SpanType.TURN, "turn", null);
        final Tracer.Span child = tracer.startChild(root.context(), SpanType.LLM, "llm:gpt-4", Map.of("messages", 2));
        child.setModel("gpt-4");
        child.setTokenUsage(TokenUsage.of(10, 5, 15));
        child.close();
        root.close();

        final TraceSpan llm = store.get("span-2").orElseThrow();
        assertThat(llm.getTraceId()).isEqualTo("span-1");
        assertThat(llm.getParentSpanId()).contains("span-1");
        assertThat(llm.getModel()).contains("gpt-4");
        assertThat(llm.getTokenUsage()).contains(TokenUsage.of(10, 5, 15));
        assertThat(store.byTrace("span-1")).extracting(TraceSpan::getSpanId).containsExactlyInAnyOrder("span-1",
                "span-2");
    }

    @Test
    @DisplayName("error() and interrupted() set terminal status")
    void terminalStatuses() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop(), fixedClock(), sequentialIds());

        final Tracer.Span failed = tracer.startRoot("c", SpanType.TURN, "t", null);
        failed.error(new IllegalStateException("boom"));
        failed.close();

        final Tracer.Span interrupted = tracer.startRoot("c", SpanType.TURN, "t", null);
        interrupted.interrupted();
        interrupted.close();

        assertThat(store.get("span-1").orElseThrow().getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(store.get("span-1").orElseThrow().getErrorMessage()).contains("boom");
        assertThat(store.get("span-2").orElseThrow().getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
    }

    @Test
    @DisplayName("close is idempotent — records exactly once")
    void closeIdempotent() {
        final List<TraceSpan> exported = new ArrayList<>();
        final Tracer tracer = new DefaultTracer(new InMemoryTraceSpanStore(), exported::add, fixedClock(),
                sequentialIds());

        final Tracer.Span span = tracer.startRoot("c", SpanType.TURN, "t", null);
        span.close();
        span.close();

        assertThat(exported).hasSize(1);
    }

    @Test
    @DisplayName("fail-safe — a throwing store/exporter never propagates from close()")
    void failSafeOnClose() {
        final TraceSpanStore throwingStore = new TraceSpanStore() {
            @Override
            public void record(TraceSpan span) {
                throw new RuntimeException("store down");
            }

            @Override
            public Optional<TraceSpan> get(String spanId) {
                return Optional.empty();
            }

            @Override
            public List<TraceSpan> byTrace(String traceId) {
                return List.of();
            }

            @Override
            public List<TraceSpan> bySession(String sessionId) {
                return List.of();
            }

            @Override
            public void deleteOlderThan(Instant cutoff) {
                // no-op
            }
        };
        final SpanExporter throwingExporter = span -> {
            throw new RuntimeException("exporter down");
        };
        final Tracer tracer = new DefaultTracer(throwingStore, throwingExporter, fixedClock(), sequentialIds());

        final Tracer.Span span = tracer.startRoot("c", SpanType.TURN, "t", null);
        assertThatCode(span::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TRACE-02 — the redactor masks inputs and outputs at record time")
    void redactorMasksInputsAndOutputs() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop(), SpanRedactor.defaultRedactor(),
                fixedClock(), sequentialIds());

        final Tracer.Span span = tracer.startRoot("c", SpanType.TOOL, "Bash", Map.of("token", "abc", "cmd", "ls"));
        span.setOutputs(Map.of("password", "p", "isError", false));
        span.close();

        final TraceSpan recorded = store.get("span-1").orElseThrow();
        @SuppressWarnings("unchecked")
        final Map<String, Object> inputs = (Map<String, Object>) recorded.getInputs().orElseThrow();
        @SuppressWarnings("unchecked")
        final Map<String, Object> outputs = (Map<String, Object>) recorded.getOutputs().orElseThrow();
        assertThat(inputs.get("token")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(inputs.get("cmd")).isEqualTo("ls");
        assertThat(outputs.get("password")).isEqualTo(KeyPatternSpanRedactor.REDACTED);
        assertThat(outputs.get("isError")).isEqualTo(false);
    }

    @Test
    @DisplayName("the default tracer applies no redaction (noop) — inputs pass through unchanged")
    void defaultTracerDoesNotRedact() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop(), fixedClock(), sequentialIds());

        final Tracer.Span span = tracer.startRoot("c", SpanType.TOOL, "Bash", Map.of("token", "abc"));
        span.close();

        @SuppressWarnings("unchecked")
        final Map<String, Object> inputs = (Map<String, Object>) store.get("span-1").orElseThrow().getInputs()
                .orElseThrow();
        assertThat(inputs.get("token")).isEqualTo("abc");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static java.util.function.Supplier<String> sequentialIds() {
        final AtomicInteger counter = new AtomicInteger();
        return () -> "span-" + counter.incrementAndGet();
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC);
    }

    private static Clock steppingClock(Instant... sequence) {
        final Deque<Instant> instants = new ArrayDeque<>(List.of(sequence));
        return new Clock() {
            @Override
            public Instant instant() {
                return instants.size() > 1 ? instants.poll() : instants.peek();
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
    }
}
