package at.aimon.core.toolinvocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.compact.CompactionBlockedByHookException;
import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionObserver;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionStart;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

@DisplayName("TracingCompactionObserver: the COMPACTION span (ST-5)")
class TracingCompactionObserverTest {

    private static final CompactionStart AUTO_START = CompactionStart.builder().trigger(CompactionTrigger.AUTO)
            .strategy(CompactionStart.IN_PLACE).blockingForced(true).messagesBefore(7).build();
    private static final CompactionStart MANUAL_START = CompactionStart.builder().trigger(CompactionTrigger.MANUAL)
            .strategy(CompactionStart.VIEW).messagesBefore(4).build();

    private InMemoryTraceSpanStore store;
    private Tracer tracer;
    private Tracer.Span root;
    private LlmCallMetadata parent;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceSpanStore();
        tracer = new DefaultTracer(store, SpanExporter.noop());
        root = tracer.startRoot("session-1", SpanType.TURN, "turn", null);
        parent = root.enrich(LlmCallMetadata.builder().traceId("session-1").build());
    }

    private CompactionObserver observer(TracePayloadPolicy policy, DefaultInterruptCoordinator coordinator) {
        return TracingCompactionObserver
                .under(tracer, parent, policy, coordinator == null ? null : coordinator.getSignal(),
                        Map.of("iteration", "3", "invokerType", "MAIN_AGENT"))
                .orElseThrow();
    }

    private TraceSpan compactionSpan() {
        final List<TraceSpan> spans = store.bySession("session-1").stream()
                .filter(s -> s.getType() == SpanType.COMPACTION).toList();
        assertThat(spans).hasSize(1);
        return spans.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputsOf(TraceSpan span) {
        return (Map<String, Object>) span.getOutputs().orElseThrow();
    }

    private static CompactionMetadata.Builder metadata(CompactionTrigger trigger) {
        final Instant now = Instant.now();
        return CompactionMetadata.builder().trigger(trigger).startedAt(now).completedAt(now);
    }

    @Test
    @DisplayName("under() is empty when the parent names no span")
    void emptyWithoutParent() {
        assertThat(TracingCompactionObserver.under(tracer, LlmCallMetadata.builder().traceId("s").build(),
                TracePayloadPolicy.summaryOnly(), null, Map.of())).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the span nests under the parent, is named by its trigger, and carries the start and the attributes")
    void parentNameInputsAttributes() {
        try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null).begin(AUTO_START)) {
            attempt.record(CompactionResult.success("sum", metadata(CompactionTrigger.AUTO).build()));
        }

        final TraceSpan span = compactionSpan();
        assertThat(span.getParentSpanId()).contains(root.context().getSpanId());
        assertThat(span.getName()).isEqualTo("compaction:auto");
        assertThat(span.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(span.getAttributes()).containsEntry("iteration", "3").containsEntry("invokerType", "MAIN_AGENT");
        assertThat(span.getInputs()).hasValueSatisfying(inputs -> assertThat((Map<String, Object>) inputs)
                .containsEntry("trigger", "AUTO").containsEntry("strategy", "in-place")
                .containsEntry("blockingForced", true).containsEntry("messagesBefore", 7));
        assertThat(span.getTokenUsage()).as("the LLM children carry the usage").isEmpty();
    }

    @Test
    @DisplayName("a manual compaction is named compaction:manual")
    void manualName() {
        try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null)
                .begin(MANUAL_START)) {
            attempt.record(CompactionResult.success("sum", metadata(CompactionTrigger.MANUAL).build()));
        }

        assertThat(compactionSpan().getName()).isEqualTo("compaction:manual");
    }

    @Test
    @DisplayName("enrich rotates only the reserved tags; the caller's attribution is kept")
    void enrichRotatesOnlyTheReservedTags() {
        final LlmCallMetadata base = parent.withDefaults(LlmCallMetadata.builder().component("compact-command")
                .feature(LlmCallMetadata.Feature.COMPACTION).principal(Principal.user("alice")).build());
        final LlmCallMetadata enriched;
        final String spanId;
        try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null).begin(AUTO_START)) {
            enriched = attempt.enrich(base);
            spanId = SpanContext.readFrom(enriched).orElseThrow().getSpanId();
            assertThat(attempt.enrich(null).getTags()).as("no base: the parent carrier, rotated")
                    .containsEntry(SpanContext.TAG_PARENT_SPAN_ID, spanId);
            attempt.record(CompactionResult.success("sum", metadata(CompactionTrigger.AUTO).build()));
        }

        assertThat(spanId).isEqualTo(compactionSpan().getSpanId());
        assertThat(enriched.getComponent()).contains("compact-command");
        assertThat(enriched.getFeature()).contains(LlmCallMetadata.Feature.COMPACTION);
        assertThat(enriched.getPrincipal()).contains(Principal.user("alice"));
        assertThat(enriched.getTraceId()).contains("session-1");
        assertThat(enriched.getTags()).containsEntry(SpanContext.TAG_TRACE_ID, root.context().getTraceId());
    }

    @Nested
    @DisplayName("outputs")
    class Outputs {

        @Test
        @DisplayName("a full compaction records sizes and counts, and no summary text by default")
        void full() {
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null)
                    .begin(AUTO_START)) {
                attempt.record(CompactionResult.success("the summary",
                        metadata(CompactionTrigger.AUTO).preCompactTokenCount(9000).postCompactTokenCount(1200)
                                .messagesSummarized(12).summaryCalls(2).blockingLimit(8500)
                                .discoveredToolNames(List.of("Read", "Edit")).build()));
            }

            final Map<String, Object> outputs = outputsOf(compactionSpan());
            assertThat(outputs).containsEntry("success", true).containsEntry("outcome", "compacted")
                    .containsEntry("kind", "FULL").containsEntry("trigger", "AUTO").containsEntry("preTokens", 9000)
                    .containsEntry("postTokens", 1200).containsEntry("messagesSummarized", 12)
                    .containsEntry("summaryCalls", 2).containsEntry("summaryChars", 11)
                    .containsEntry("blockingLimit", 8500).containsEntry("overBlockingLimit", false)
                    .containsEntry("discoveredToolCount", 2);
            assertThat(outputs).doesNotContainKeys("summary", "summaryTokens", "headTokens", "absorbedFromSeq",
                    "errorType");
        }

        @Test
        @DisplayName("a rolling compaction adds the view shape and the absorbed range")
        void rolling() {
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null)
                    .begin(AUTO_START)) {
                attempt.record(
                        CompactionResult.success("s", metadata(CompactionTrigger.AUTO).kind(CompactionKind.ROLLING)
                                .viewShape(10, 20, 30).summaryTokens(15).absorbedRange(3, 9).build()));
            }

            assertThat(outputsOf(compactionSpan())).containsEntry("kind", "ROLLING").containsEntry("summaryTokens", 15)
                    .containsEntry("headTokens", 10).containsEntry("spanTokens", 20).containsEntry("tailTokens", 30)
                    .containsEntry("absorbedFromSeq", 3L).containsEntry("absorbedToSeq", 9L)
                    .doesNotContainKey("blockingLimit");
        }

        @Test
        @DisplayName("a prune records its kind and zero summary characters")
        void prune() {
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null)
                    .begin(AUTO_START)) {
                attempt.record(CompactionResult.success("", metadata(CompactionTrigger.AUTO).kind(CompactionKind.PRUNE)
                        .preCompactTokenCount(700).postCompactTokenCount(100).build()));
            }

            assertThat(outputsOf(compactionSpan())).containsEntry("kind", "PRUNE").containsEntry("summaryChars", 0)
                    .containsEntry("postTokens", 100).doesNotContainKey("summaryTokens");
        }

        @Test
        @DisplayName("the summary text is captured, truncated, only under a full payload policy")
        void summaryOnlyUnderFull() {
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.full(4), null).begin(AUTO_START)) {
                attempt.record(CompactionResult.success("abcdefgh", metadata(CompactionTrigger.AUTO).build()));
            }

            assertThat(outputsOf(compactionSpan())).containsEntry("summary", "abcd…(truncated 4 chars)")
                    .containsEntry("summaryChars", 8);
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("closed without a recorded result: ERROR, did not complete")
        void notRecorded() {
            observer(TracePayloadPolicy.summaryOnly(), null).begin(AUTO_START).close();

            final TraceSpan span = compactionSpan();
            assertThat(span.getStatus()).isEqualTo(SpanStatus.ERROR);
            assertThat(span.getErrorMessage()).hasValue(TracingCompactionObserver.DID_NOT_COMPLETE);
        }

        @Test
        @DisplayName("a failure while the execution is cancelled: INTERRUPTED")
        void interrupted() {
            final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), coordinator)
                    .begin(AUTO_START)) {
                coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
                attempt.record(CompactionResult.failure(new LlmCallCancelledException("aborted"),
                        metadata(CompactionTrigger.AUTO).build()));
            }

            final TraceSpan span = compactionSpan();
            assertThat(span.getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
            assertThat(outputsOf(span)).containsEntry("outcome", "interrupted").containsEntry("success", false)
                    .containsEntry("errorType", "LlmCallCancelledException");
        }

        @Test
        @DisplayName("a wrapped cancel, or a PreCompact block, while the execution is cancelled: INTERRUPTED")
        void interruptedThroughCauseOrBlock() {
            for (Exception error : List.of(
                    new IllegalStateException("summary failed", new LlmCallCancelledException("aborted")),
                    new CompactionBlockedByHookException(List.of("vetoed")))) {
                setUp();
                final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
                try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), coordinator)
                        .begin(AUTO_START)) {
                    coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
                    attempt.record(CompactionResult.failure(error, metadata(CompactionTrigger.AUTO).build()));
                }
                assertThat(compactionSpan().getStatus()).as(error.toString()).isEqualTo(SpanStatus.INTERRUPTED);
            }
        }

        @Test
        @DisplayName("a provider failure that merely coincides with a cancel: ERROR, not INTERRUPTED")
        void unrelatedFailureWhileCancelled() {
            final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), coordinator)
                    .begin(AUTO_START)) {
                coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
                attempt.record(CompactionResult.failure(new IllegalStateException("provider 500"),
                        metadata(CompactionTrigger.AUTO).build()));
            }

            final TraceSpan span = compactionSpan();
            assertThat(span.getStatus()).isEqualTo(SpanStatus.ERROR);
            assertThat(outputsOf(span)).containsEntry("outcome", "failed");
        }

        @Test
        @DisplayName("a cancelled summary call without a tripped signal: ERROR")
        void cancelledCallWithoutSignal() {
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(),
                    new DefaultInterruptCoordinator()).begin(AUTO_START)) {
                attempt.record(CompactionResult.failure(new LlmCallCancelledException("aborted"),
                        metadata(CompactionTrigger.AUTO).build()));
            }

            assertThat(compactionSpan().getStatus()).isEqualTo(SpanStatus.ERROR);
        }

        @Test
        @DisplayName("a PreCompact block: OK, outcome blocked")
        void blockedByHook() {
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null)
                    .begin(AUTO_START)) {
                attempt.record(CompactionResult.failure(new CompactionBlockedByHookException(List.of("vetoed")),
                        metadata(CompactionTrigger.AUTO).build()));
            }

            final TraceSpan span = compactionSpan();
            assertThat(span.getStatus()).isEqualTo(SpanStatus.OK);
            assertThat(outputsOf(span)).containsEntry("outcome", "blocked").containsEntry("errorType",
                    "CompactionBlockedByHookException");
        }

        @Test
        @DisplayName("any other failure: ERROR with the error recorded")
        void failure() {
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null)
                    .begin(AUTO_START)) {
                attempt.record(CompactionResult.failure(new IllegalStateException("provider down"),
                        metadata(CompactionTrigger.AUTO).build()));
            }

            final TraceSpan span = compactionSpan();
            assertThat(span.getStatus()).isEqualTo(SpanStatus.ERROR);
            assertThat(span.getErrorMessage()).hasValueSatisfying(m -> assertThat(m).contains("provider down"));
            assertThat(outputsOf(span)).containsEntry("outcome", "failed").containsEntry("errorType",
                    "IllegalStateException");
        }

        @Test
        @DisplayName("a success still over the blocking limit: OK")
        void overBlockingLimitIsOk() {
            try (CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null)
                    .begin(AUTO_START)) {
                attempt.record(CompactionResult.success("s",
                        metadata(CompactionTrigger.AUTO).blockingLimit(100).postCompactTokenCount(150).build()));
            }

            final TraceSpan span = compactionSpan();
            assertThat(span.getStatus()).isEqualTo(SpanStatus.OK);
            assertThat(outputsOf(span)).containsEntry("overBlockingLimit", true);
        }
    }

    @Nested
    @DisplayName("fail-safety")
    class FailSafety {

        @Test
        @DisplayName("a throwing startChild yields a no-op attempt that leaves metadata as it was")
        void throwingTracer() {
            final Tracer throwing = new Tracer() {
                @Override
                public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
                    throw new IllegalStateException("tracer down");
                }

                @Override
                public Span startChild(SpanContext parentContext, SpanType type, String name,
                        Map<String, Object> inputs) {
                    throw new IllegalStateException("tracer down");
                }
            };
            final CompactionObserver observer = TracingCompactionObserver
                    .under(throwing, parent, TracePayloadPolicy.summaryOnly(), null, Map.of()).orElseThrow();
            final LlmCallMetadata base = LlmCallMetadata.builder().component("c").build();

            try (CompactionObserver.Attempt attempt = observer.begin(AUTO_START)) {
                assertThat(attempt.enrich(base)).isSameAs(base);
            }
        }

        @Test
        @DisplayName("recording or closing twice is harmless; one span is recorded")
        void doubleRecordAndClose() {
            final CompactionObserver.Attempt attempt = observer(TracePayloadPolicy.summaryOnly(), null)
                    .begin(AUTO_START);
            attempt.record(CompactionResult.success("s", metadata(CompactionTrigger.AUTO).build()));
            attempt.record(CompactionResult.success("s", metadata(CompactionTrigger.AUTO).build()));
            attempt.close();

            assertThatCode(attempt::close).doesNotThrowAnyException();
            assertThat(compactionSpan().getStatus()).isEqualTo(SpanStatus.OK);
        }
    }
}
