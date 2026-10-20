package at.aimon.core.tracing.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
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
    @DisplayName("the call's feature becomes a span attribute; a call without one records none")
    void featureBecomesAttribute() {
        final InMemoryTraceSpanStore compactionStore = new InMemoryTraceSpanStore();
        new TracingLlmClient(new RecordingLlmClient(LlmResponse.text("x")), tracer(compactionStore)).sendMessage("sys",
                MESSAGES, TOOLS, MODEL, enrichedMetadata()
                        .withDefaults(LlmCallMetadata.builder().feature(LlmCallMetadata.Feature.COMPACTION).build()));
        final InMemoryTraceSpanStore plainStore = new InMemoryTraceSpanStore();
        new TracingLlmClient(new RecordingLlmClient(LlmResponse.text("x")), tracer(plainStore)).sendMessage("sys",
                MESSAGES, TOOLS, MODEL, enrichedMetadata());

        assertThat(compactionStore.byTrace("span-root")).singleElement().satisfies(
                span -> assertThat(span.getAttributes()).containsEntry("feature", LlmCallMetadata.Feature.COMPACTION));
        assertThat(plainStore.byTrace("span-root")).singleElement()
                .satisfies(span -> assertThat(span.getAttributes()).doesNotContainKey("feature"));
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

    @Test
    @DisplayName("a reported stop reason is recorded in the outputs; an unknown one is left out")
    void stopReasonRecordedWhenReported() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final LlmClient client = new TracingLlmClient(
                new RecordingLlmClient(LlmResponse.of("cut", List.of(), TokenUsage.of(1, 1, 2), StopReason.MAX_TOKENS)),
                tracer(store));
        client.sendMessage("sys", MESSAGES, TOOLS, MODEL, enrichedMetadata());
        assertThat(outputs(store)).containsEntry("stopReason", "MAX_TOKENS");

        final InMemoryTraceSpanStore unknownStore = new InMemoryTraceSpanStore();
        new TracingLlmClient(new RecordingLlmClient(LlmResponse.text("ok")), tracer(unknownStore)).sendMessage("sys",
                MESSAGES, TOOLS, MODEL, enrichedMetadata());
        assertThat(outputs(unknownStore)).doesNotContainKey("stopReason");
    }

    @Test
    @DisplayName("a streaming call is marked streaming, records ttftMs and forwards every chunk unchanged")
    void streamingCallRecordsTimeToFirstChunk() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final LlmClient client = new TracingLlmClient(new RecordingLlmClient(LlmResponse.text("hello")), tracer(store));
        final List<LlmStreamChunk> received = new ArrayList<>();

        final LlmResponse response = client.sendMessageStreaming(SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL,
                enrichedMetadata(), LlmStreamingOptions.defaults(), received::add);

        assertThat(response.getTextContent()).isEqualTo("hello");
        assertThat(received).extracting(LlmStreamChunk::getKind).containsExactly(LlmStreamChunk.Kind.TEXT_DELTA,
                LlmStreamChunk.Kind.STREAM_END);
        final TraceSpan span = store.byTrace("span-root").stream().findFirst().orElseThrow();
        assertThat(span.getAttributes()).containsEntry(TracingLlmClient.STREAMING_ATTR, "true");
        assertThat(Long.parseLong(span.getAttributes().get(TracingLlmClient.TTFT_ATTR))).isNotNegative();
    }

    @Test
    @DisplayName("a stream with no content chunk records no ttftMs; a non-streaming call is not marked streaming")
    void ttftAbsentWithoutContent() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        new TracingLlmClient(new RecordingLlmClient(LlmResponse.of("", List.of())), tracer(store)).sendMessageStreaming(
                SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL, enrichedMetadata(), LlmStreamingOptions.defaults(),
                chunk -> {
                });
        final TraceSpan span = store.byTrace("span-root").stream().findFirst().orElseThrow();
        assertThat(span.getAttributes()).containsEntry(TracingLlmClient.STREAMING_ATTR, "true")
                .doesNotContainKey(TracingLlmClient.TTFT_ATTR);

        final InMemoryTraceSpanStore plainStore = new InMemoryTraceSpanStore();
        new TracingLlmClient(new RecordingLlmClient(LlmResponse.text("hi")), tracer(plainStore)).sendMessage("sys",
                MESSAGES, TOOLS, MODEL, enrichedMetadata());
        final TraceSpan plain = plainStore.byTrace("span-root").stream().findFirst().orElseThrow();
        assertThat(plain.getAttributes()).doesNotContainKeys(TracingLlmClient.STREAMING_ATTR,
                TracingLlmClient.TTFT_ATTR);
    }

    @Test
    @DisplayName("an un-enriched streaming call hands the caller's own sink to the delegate and records nothing")
    void bareStreamingCallPassesSinkThrough() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final List<LlmStreamChunk> received = new ArrayList<>();
        new TracingLlmClient(new RecordingLlmClient(LlmResponse.text("hi")), tracer(store)).sendMessageStreaming(
                SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL, LlmCallMetadata.builder().build(),
                LlmStreamingOptions.defaults(), received::add);
        assertThat(received).hasSize(2);
        assertThat(store.byTrace("span-root")).isEmpty();
    }

    @Test
    @DisplayName("a cancel nobody requested (no token to cancel) is an ordinary ERROR, and rethrows")
    void unrequestedCancelIsError() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final LlmClient client = new TracingLlmClient(
                new RecordingLlmClient(new LlmCallCancelledException("cancelled")), tracer(store));

        assertThatThrownBy(() -> client.sendMessage("sys", MESSAGES, TOOLS, MODEL, enrichedMetadata()))
                .isInstanceOf(LlmCallCancelledException.class);

        assertThat(store.byTrace("span-root").get(0).getStatus()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    @DisplayName("a cancel the token did not request is ERROR even on the cancellation-aware overload")
    void cancelWithUncancelledTokenIsError() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        assertThatThrownBy(
                () -> new TracingLlmClient(new RecordingLlmClient(new LlmCallCancelledException("cancelled")),
                        tracer(store)).sendMessage(SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL,
                                enrichedMetadata(), LlmCancellation.none()))
                .isInstanceOf(LlmCallCancelledException.class);
        assertThat(store.byTrace("span-root").get(0).getStatus()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    @DisplayName("a span whose setters throw neither fails a successful call nor replaces a failed call's exception")
    void throwingSettersDoNotChangeTheOutcome() {
        final Tracer throwingSpans = new Tracer() {
            @Override
            public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }

            @Override
            public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
                return new ThrowingAttributeSpan(Tracer.noop().startChild(parent, type, name, inputs));
            }
        };
        final LlmCallMetadata withFeature = enrichedMetadata()
                .withDefaults(LlmCallMetadata.builder().feature("f").build());

        assertThat(new TracingLlmClient(new RecordingLlmClient(LlmResponse.text("ok")), throwingSpans)
                .sendMessage("sys", MESSAGES, TOOLS, MODEL, withFeature).getTextContent()).isEqualTo("ok");
        assertThatThrownBy(
                () -> new TracingLlmClient(new RecordingLlmClient(new IllegalStateException("api down")), throwingSpans)
                        .sendMessage("sys", MESSAGES, TOOLS, MODEL, withFeature))
                .hasMessage("api down");
    }

    @Test
    @DisplayName("a tracer that fails to start the span leaves the call untraced, not failed")
    void failingTracerDoesNotFailTheCall() {
        final Tracer failing = new Tracer() {
            @Override
            public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }

            @Override
            public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }
        };
        final RecordingLlmClient delegate = new RecordingLlmClient(LlmResponse.text("still answered"));

        final LlmResponse response = new TracingLlmClient(delegate, failing).sendMessage("sys", MESSAGES, TOOLS, MODEL,
                enrichedMetadata());

        assertThat(response.getTextContent()).isEqualTo("still answered");
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("a reasoning or tool_use chunk counts as the first chunk; later chunks do not restamp; chunks pass by identity")
    void firstContentChunkOfAnyKindStampsOnce() {
        for (LlmStreamChunk first : List.of(LlmStreamChunk.reasoningDelta(0, "thinking"),
                LlmStreamChunk.toolUseReady(0, ToolUse.of("call-1", "Echo", Map.of())))) {
            final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
            final RecordingSpanTracer tracer = new RecordingSpanTracer(tracer(store));
            final List<LlmStreamChunk> chunks = List.of(first, LlmStreamChunk.textDelta(1, "a"),
                    LlmStreamChunk.textDelta(2, "b"), streamEnd(3));
            final List<LlmStreamChunk> received = new ArrayList<>();

            new TracingLlmClient(new StreamingFake(chunks, null), tracer).sendMessageStreaming(
                    SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL, enrichedMetadata(),
                    LlmStreamingOptions.defaults(), received::add);

            assertThat(received).hasSize(4);
            for (int i = 0; i < chunks.size(); i++) {
                assertThat(received.get(i)).isSameAs(chunks.get(i));
            }
            assertThat(tracer.ttftStamps).hasValue(1);
            assertThat(store.byTrace("span-root").get(0).getAttributes()).containsKey(TracingLlmClient.TTFT_ATTR);
        }
    }

    @Test
    @DisplayName("a stream that only ends records no ttftMs")
    void endOnlyStreamHasNoTtft() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        new TracingLlmClient(new StreamingFake(List.of(streamEnd(0)), null), tracer(store)).sendMessageStreaming(
                SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL, enrichedMetadata(), LlmStreamingOptions.defaults(),
                chunk -> {
                });
        assertThat(store.byTrace("span-root").get(0).getAttributes())
                .containsEntry(TracingLlmClient.STREAMING_ATTR, "true").doesNotContainKey(TracingLlmClient.TTFT_ATTR);
    }

    @Test
    @DisplayName("a streaming call cancelled after its first chunk ends INTERRUPTED with ttftMs; another failure is ERROR")
    void streamingCancelAndFailure() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        assertThatThrownBy(() -> new TracingLlmClient(new StreamingFake(List.of(LlmStreamChunk.textDelta(0, "par")),
                new LlmCallCancelledException("cancelled")), tracer(store))
                .sendMessageStreaming(SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL, enrichedMetadata(),
                        LlmStreamingOptions.defaults(), chunk -> {
                        }, CANCELLED))
                .isInstanceOf(LlmCallCancelledException.class);
        final TraceSpan cancelled = store.byTrace("span-root").get(0);
        assertThat(cancelled.getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
        assertThat(cancelled.getAttributes()).containsKey(TracingLlmClient.TTFT_ATTR);

        final InMemoryTraceSpanStore failedStore = new InMemoryTraceSpanStore();
        assertThatThrownBy(
                () -> new TracingLlmClient(new StreamingFake(List.of(), new IllegalStateException("stream broke")),
                        tracer(failedStore)).sendMessageStreaming(SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL,
                                enrichedMetadata(), LlmStreamingOptions.defaults(), chunk -> {
                                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(failedStore.byTrace("span-root").get(0).getStatus()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    @DisplayName("a streaming call under a tracer whose spans throw still streams every chunk and returns")
    void throwingSpanDoesNotBreakTheStream() {
        final Tracer throwingSpans = new Tracer() {
            @Override
            public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }

            @Override
            public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
                return new ThrowingAttributeSpan(Tracer.noop().startChild(parent, type, name, inputs));
            }
        };
        final List<LlmStreamChunk> received = new ArrayList<>();

        final LlmResponse response = new TracingLlmClient(
                new StreamingFake(List.of(LlmStreamChunk.textDelta(0, "hi"), streamEnd(1)), null), throwingSpans)
                .sendMessageStreaming(SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL, enrichedMetadata(),
                        LlmStreamingOptions.defaults(), received::add);

        assertThat(response.getTextContent()).isEqualTo("streamed");
        assertThat(received).hasSize(2);
    }

    @Test
    @DisplayName("a call whose token was cancelled ends INTERRUPTED, not ERROR, and rethrows")
    void cancellationOverloadIsInterrupted() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        assertThatThrownBy(
                () -> new TracingLlmClient(new RecordingLlmClient(new LlmCallCancelledException("cancelled")),
                        tracer(store))
                        .sendMessage(SystemPromptParts.empty(), MESSAGES, TOOLS, MODEL, enrichedMetadata(), CANCELLED))
                .isInstanceOf(LlmCallCancelledException.class);
        assertThat(store.byTrace("span-root").get(0).getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** A token whose cancel was requested. */
    private static final LlmCancellation CANCELLED = new LlmCancellation() {
        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public void onCancel(Runnable abort) {
        }
    };

    private static LlmStreamChunk streamEnd(int index) {
        return LlmStreamChunk.streamEnd(index, TokenUsage.of(1, 1, 2), Optional.empty());
    }

    /** Streams scripted chunks to the sink, then returns a canned response or throws {@code failure}. */
    private static final class StreamingFake implements LlmClient {
        private final List<LlmStreamChunk> chunks;
        private final RuntimeException failure;

        StreamingFake(List<LlmStreamChunk> chunks, RuntimeException failure) {
            this.chunks = chunks;
            this.failure = failure;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("streaming only");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink) {
            chunks.forEach(sink::accept);
            if (failure != null) {
                throw failure;
            }
            return LlmResponse.text("streamed");
        }

        @Override
        public String getProviderName() {
            return "streaming-fake";
        }
    }

    /** Delegating tracer that counts how many times a span's ttftMs attribute is set. */
    private static final class RecordingSpanTracer implements Tracer {
        private final Tracer delegate;
        private final AtomicInteger ttftStamps = new AtomicInteger();

        RecordingSpanTracer(Tracer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
            return delegate.startRoot(sessionId, type, name, inputs);
        }

        @Override
        public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
            final Span span = delegate.startChild(parent, type, name, inputs);
            return new ForwardingSpan(span) {
                @Override
                public void setAttribute(String key, String value) {
                    if (TracingLlmClient.TTFT_ATTR.equals(key)) {
                        ttftStamps.incrementAndGet();
                    }
                    super.setAttribute(key, value);
                }
            };
        }
    }

    /** A span that forwards every call to {@code delegate}; tests override the one call they observe. */
    private static class ForwardingSpan implements Tracer.Span {
        private final Tracer.Span delegate;

        ForwardingSpan(Tracer.Span delegate) {
            this.delegate = delegate;
        }

        @Override
        public SpanContext context() {
            return delegate.context();
        }

        @Override
        public LlmCallMetadata enrich(LlmCallMetadata base) {
            return delegate.enrich(base);
        }

        @Override
        public void setOutputs(Object outputs) {
            delegate.setOutputs(outputs);
        }

        @Override
        public void setTokenUsage(TokenUsage usage) {
            delegate.setTokenUsage(usage);
        }

        @Override
        public void setModel(String model) {
            delegate.setModel(model);
        }

        @Override
        public void setAttribute(String key, String value) {
            delegate.setAttribute(key, value);
        }

        @Override
        public void error(Throwable t) {
            delegate.error(t);
        }

        @Override
        public void error(String message) {
            delegate.error(message);
        }

        @Override
        public void interrupted() {
            delegate.interrupted();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** A span whose setters and error call throw, standing in for a misbehaving custom tracer. */
    private static final class ThrowingAttributeSpan extends ForwardingSpan {
        ThrowingAttributeSpan(Tracer.Span delegate) {
            super(delegate);
        }

        @Override
        public void setAttribute(String key, String value) {
            throw new IllegalStateException("attribute store down");
        }

        @Override
        public void setModel(String model) {
            throw new IllegalStateException("span store down");
        }

        @Override
        public void setOutputs(Object outputs) {
            throw new IllegalStateException("span store down");
        }

        @Override
        public void error(Throwable t) {
            throw new IllegalStateException("span store down");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputs(InMemoryTraceSpanStore store) {
        return (Map<String, Object>) store.byTrace("span-root").stream().findFirst().orElseThrow().getOutputs()
                .orElseThrow();
    }

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
