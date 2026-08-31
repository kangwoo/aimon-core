package at.aimon.core.tracing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;

class TracingLlmClientTest {

    private static final LlmModel MODEL = LlmModel.builder().name("gpt-4").build();
    private static final List<Message> MESSAGES = List.of(Message.user("hi"));
    private static final List<ToolDefinition> TOOLS = List.of();

    @Test
    @DisplayName("enriched call records an LLM span under the parent with model + tokens")
    void enrichedCallRecordsLlmSpan() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final RecordingLlmClient delegate = new RecordingLlmClient(
                LlmResponse.of("hello", List.of(), TokenUsage.of(10, 5, 15)));
        final LlmClient client = new TracingLlmClient(delegate, tracer(store));

        final LlmResponse response = client.sendMessage("sys", MESSAGES, TOOLS, MODEL, enrichedMetadata());

        assertThat(response.getTextContent()).isEqualTo("hello");
        assertThat(delegate.calls).isEqualTo(1);

        final TraceSpan span = store.byTrace("span-root").stream().findFirst().orElseThrow();
        assertThat(span.getType()).isEqualTo(SpanType.LLM);
        assertThat(span.getName()).isEqualTo("llm:gpt-4");
        assertThat(span.getParentSpanId()).contains("span-iter");
        assertThat(span.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(span.getModel()).contains("gpt-4");
        assertThat(span.getTokenUsage()).contains(TokenUsage.of(10, 5, 15));
    }

    @Test
    @DisplayName("un-enriched call delegates without producing an orphan span")
    void bareCallProducesNoSpan() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final RecordingLlmClient delegate = new RecordingLlmClient(LlmResponse.text("hi"));
        final LlmClient client = new TracingLlmClient(delegate, tracer(store));

        final LlmCallMetadata bare = LlmCallMetadata.builder().traceId("conv-1").build();
        final LlmResponse response = client.sendMessage("sys", MESSAGES, TOOLS, MODEL, bare);

        assertThat(response.getTextContent()).isEqualTo("hi");
        assertThat(delegate.calls).isEqualTo(1);
        assertThat(store.bySession("conv-1")).isEmpty();
    }

    @Test
    @DisplayName("output summary omits totalTokens when the response carries no usage")
    void outputsOmitTokensWhenAbsent() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final RecordingLlmClient delegate = new RecordingLlmClient(LlmResponse.text("hi")); // no token usage
        final LlmClient client = new TracingLlmClient(delegate, tracer(store));

        client.sendMessage("sys", MESSAGES, TOOLS, MODEL, enrichedMetadata());

        final TraceSpan span = store.byTrace("span-root").stream().findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        final Map<String, Object> outputs = (Map<String, Object>) span.getOutputs().orElseThrow();
        assertThat(outputs).containsKey("textChars").doesNotContainKey("totalTokens");
        assertThat(span.getTokenUsage()).isEmpty();
    }

    @Test
    @DisplayName("TRACE-02 — summary-only policy omits the response text")
    void summaryPolicyOmitsText() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final RecordingLlmClient delegate = new RecordingLlmClient(LlmResponse.text("a long answer"));
        final LlmClient client = new TracingLlmClient(delegate, tracer(store), TracePayloadPolicy.summaryOnly());

        client.sendMessage("sys", MESSAGES, TOOLS, MODEL, enrichedMetadata());

        @SuppressWarnings("unchecked")
        final Map<String, Object> outputs = (Map<String, Object>) store.byTrace("span-root").stream().findFirst()
                .orElseThrow().getOutputs().orElseThrow();
        assertThat(outputs).containsEntry("textChars", 13).doesNotContainKey("text");
    }

    @Test
    @DisplayName("TRACE-02 — full policy captures the response text, truncated to the cap")
    void fullPolicyCapturesTruncatedText() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final RecordingLlmClient delegate = new RecordingLlmClient(LlmResponse.text("abcdefghij")); // 10 chars
        final LlmClient client = new TracingLlmClient(delegate, tracer(store), TracePayloadPolicy.full(4));

        client.sendMessage("sys", MESSAGES, TOOLS, MODEL, enrichedMetadata());

        @SuppressWarnings("unchecked")
        final Map<String, Object> outputs = (Map<String, Object>) store.byTrace("span-root").stream().findFirst()
                .orElseThrow().getOutputs().orElseThrow();
        assertThat(outputs).containsEntry("textChars", 10);
        assertThat(outputs.get("text")).isEqualTo("abcd…(truncated 6 chars)");
    }

    @Test
    @DisplayName("non-metadata methods delegate transparently")
    void nonMetadataMethodsDelegate() {
        final RecordingLlmClient delegate = new RecordingLlmClient(LlmResponse.text("x"));
        final LlmClient client = new TracingLlmClient(delegate, tracer(new InMemoryTraceSpanStore()));

        client.sendMessage("sys", MESSAGES, TOOLS, MODEL);

        assertThat(delegate.calls).isEqualTo(1);
        assertThat(client.getProviderName()).isEqualTo("recording");
    }

    @Test
    @DisplayName("delegate failure marks the span ERROR and rethrows")
    void failureMarksSpanError() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final LlmClient delegate = new RecordingLlmClient(new IllegalStateException("api down"));
        final LlmClient client = new TracingLlmClient(delegate, tracer(store));

        assertThatThrownBy(() -> client.sendMessage("sys", MESSAGES, TOOLS, MODEL, enrichedMetadata()))
                .isInstanceOf(IllegalStateException.class).hasMessage("api down");

        final TraceSpan span = store.byTrace("span-root").stream().findFirst().orElseThrow();
        assertThat(span.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(span.getErrorMessage()).contains("api down");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Tracer tracer(InMemoryTraceSpanStore store) {
        final AtomicInteger counter = new AtomicInteger();
        final Supplier<String> ids = () -> "llm-span-" + counter.incrementAndGet();
        return new DefaultTracer(store, at.aimon.core.tracing.SpanExporter.noop(),
                Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC), ids);
    }

    private static LlmCallMetadata enrichedMetadata() {
        final SpanContext iteration = SpanContext.root("conv-1", "span-root").child("span-iter");
        return iteration.writeInto(LlmCallMetadata.builder().component("orca-agent").traceId("conv-1").build());
    }

    /** Minimal {@link LlmClient} fake: returns a canned response (or throws) and counts calls. */
    private static final class RecordingLlmClient implements LlmClient {
        private final LlmResponse response;
        private final RuntimeException failure;
        private int calls;

        RecordingLlmClient(LlmResponse response) {
            this.response = response;
            this.failure = null;
        }

        RecordingLlmClient(RuntimeException failure) {
            this.response = null;
            this.failure = failure;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public String getProviderName() {
            return "recording";
        }

    }
}
