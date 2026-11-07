package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.ToolSpans;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

/**
 * The trace of the calls a program makes (ptc.md §12.2): a {@code TOOL} span per nested call under the
 * {@code ExecuteCode} call's own span, a zero-length one for a call the bridge refused, and nothing that can fail a
 * call when tracing does.
 */
@DisplayName("DefaultToolCallBridge — a TOOL span per nested call")
class DefaultToolCallBridgeTracingTest {

    private static final ToolDescriptor READ = ToolDescriptor.of(TestTool.reader("Read"));

    private InMemoryTraceSpanStore store;
    private Tracer tracer;
    private Tracer.Span executeCodeSpan;
    private ToolContext parentContext;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceSpanStore();
        tracer = new DefaultTracer(store, SpanExporter.noop());
        final Tracer.Span root = tracer.startRoot("s1", SpanType.TURN, "turn", null);
        executeCodeSpan = tracer.startChild(root.context(), SpanType.TOOL, ExecuteCodeTool.TOOL_NAME, Map.of());
        parentContext = ToolContext.builder().put(ToolContextKeys.TRACER, tracer)
                .put(ToolContextKeys.LLM_CALL_METADATA_KEY, executeCodeSpan.enrich(LlmCallMetadata.empty()))
                .put(ToolContextKeys.ITERATION_COUNT, 3).build();
    }

    @Test
    @DisplayName("two nested calls: two TOOL spans under the ExecuteCode span, tool.path=code, ids #1 and #2")
    void nestedCalls() {
        final List<ToolContext> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        final DefaultToolCallBridge bridge = bridge(recording(seen), parentContext, CodeExecutionLimits.defaults());
        bridge.callAsync("Read", Map.of("path", "a")).join();
        bridge.callAsync("Read", Map.of("path", "b")).join();
        bridge.close(Duration.ofSeconds(1));

        final List<TraceSpan> nested = nestedSpans();
        assertThat(nested).hasSize(2).allSatisfy(span -> {
            assertThat(span.getParentSpanId()).contains(executeCodeSpan.context().getSpanId());
            assertThat(span.getType()).isEqualTo(SpanType.TOOL);
            assertThat(span.getName()).isEqualTo("Read");
            assertThat(span.getAttributes()).containsEntry(ToolSpans.PATH, ToolSpans.PATH_CODE)
                    .containsEntry(CodeSpanAttributes.PARENT_TOOL_USE_ID, "tu")
                    .containsEntry(CodeSpanAttributes.LANGUAGE, "javascript").containsEntry("iteration", "3");
            assertThat(span.getStatus()).isEqualTo(SpanStatus.OK);
            // The summary only: the content is data the program filters.
            assertThat(span.getOutputs()).hasValueSatisfying(outputs -> assertThat(outputs(outputs))
                    .containsEntry("isError", false).containsKey("contentChars").doesNotContainKey("content"));
        });
        assertThat(nested).extracting(span -> (Object) outputs(span.getInputs().orElseThrow()).get("toolUseId"))
                .containsExactlyInAnyOrder("tu#1", "tu#2");
        // Each call's tool context is rotated under its own span, which the tool can annotate.
        assertThat(seen).hasSize(2).allSatisfy(context -> {
            assertThat(context.get(ToolContextKeys.LLM_CALL_METADATA_KEY).flatMap(SpanContext::readFrom))
                    .hasValueSatisfying(
                            parent -> assertThat(nested).extracting(TraceSpan::getSpanId).contains(parent.getSpanId()));
            assertThat(context.get(ToolContextKeys.TOOL_SPAN)).isPresent();
        });
    }

    @Test
    @DisplayName("a refused call gets a zero-length TOOL span with ptc.refused, ended in error")
    void refusalSpan() {
        final DefaultToolCallBridge bridge = bridge(recording(new java.util.ArrayList<>()), parentContext,
                CodeExecutionLimits.builder().maxArgumentBytes(16).build());
        bridge.callAsync("Read", Map.of("path", "x".repeat(100))).join();
        bridge.callAsync("Write", Map.of()).join();
        bridge.close(Duration.ofSeconds(1));
        bridge.callAsync("Read", Map.of()).join();

        assertThat(nestedSpans()).hasSize(3).allSatisfy(span -> {
            assertThat(span.getParentSpanId()).contains(executeCodeSpan.context().getSpanId());
            assertThat(span.getStatus()).isEqualTo(SpanStatus.ERROR);
            assertThat(span.getAttributes()).containsEntry(ToolSpans.PATH, ToolSpans.PATH_CODE);
        }).extracting(span -> span.getAttributes().get(CodeSpanAttributes.REFUSED))
                .containsExactlyInAnyOrder("argument size", "not callable", "closed");
    }

    @Test
    @DisplayName("a result that arrives after the bridge closed is still recorded on its span")
    void lateResult() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ToolCallDispatcher slow = dispatcher((toolUse, context) -> {
            started.countDown();
            await(release);
            return ToolDispatchResult.builder().toolUseId(toolUse.getId()).text("late").build();
        });
        final DefaultToolCallBridge bridge = bridge(slow, parentContext, CodeExecutionLimits.defaults());
        final CompletableFuture<ToolDispatchResult> call = bridge.callAsync("Read", Map.of());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        bridge.close(Duration.ofMillis(50));
        release.countDown();
        assertThat(call.get(5, TimeUnit.SECONDS).getText()).contains("has ended");
        final long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (nestedSpans().isEmpty() && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(nestedSpans()).singleElement().satisfies(span -> assertThat(span.getOutputs())
                .hasValueSatisfying(outputs -> assertThat(outputs(outputs)).containsEntry("contentChars", 4)));
    }

    @Test
    @DisplayName("a throwing tracer never fails a call; without a tracer nothing is recorded")
    void tracingNeverFailsACall() {
        final ToolContext throwing = ToolContext.builder().put(ToolContextKeys.TRACER, new ThrowingTracer())
                .put(ToolContextKeys.LLM_CALL_METADATA_KEY, executeCodeSpan.enrich(LlmCallMetadata.empty())).build();
        final DefaultToolCallBridge bridge = bridge(recording(new java.util.ArrayList<>()), throwing,
                CodeExecutionLimits.defaults());
        assertThat(bridge.callAsync("Read", Map.of()).join().isError()).isFalse();
        assertThat(bridge.callAsync("Write", Map.of()).join().isError()).isTrue();
        bridge.close(Duration.ofSeconds(1));

        final ToolContext untraced = ToolContext.builder()
                .put(ToolContextKeys.LLM_CALL_METADATA_KEY, executeCodeSpan.enrich(LlmCallMetadata.empty())).build();
        final DefaultToolCallBridge plain = bridge(recording(new java.util.ArrayList<>()), untraced,
                CodeExecutionLimits.defaults());
        assertThat(plain.callAsync("Read", Map.of()).join().isError()).isFalse();
        plain.close(Duration.ofSeconds(1));
        assertThat(nestedSpans()).isEmpty();
    }

    @Test
    @DisplayName("concurrent CONCURRENT_SAFE calls get distinct spans")
    void concurrentCalls() {
        final CountDownLatch together = new CountDownLatch(3);
        final ToolCallDispatcher meeting = dispatcher((toolUse, context) -> {
            together.countDown();
            await(together);
            return ToolDispatchResult.builder().toolUseId(toolUse.getId()).text("ok").build();
        });
        final DefaultToolCallBridge bridge = bridge(meeting, parentContext,
                CodeExecutionLimits.builder().maxConcurrentCalls(4).build());
        CompletableFuture.allOf(bridge.callAsync("Read", Map.of()), bridge.callAsync("Read", Map.of()),
                bridge.callAsync("Read", Map.of())).join();
        bridge.close(Duration.ofSeconds(1));
        assertThat(nestedSpans()).hasSize(3).extracting(TraceSpan::getSpanId).doesNotHaveDuplicates();
    }

    private List<TraceSpan> nestedSpans() {
        return store.byTrace(executeCodeSpan.context().getTraceId()).stream().filter(
                span -> span.getParentSpanId().filter(executeCodeSpan.context().getSpanId()::equals).isPresent())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputs(Object snapshot) {
        return (Map<String, Object>) snapshot;
    }

    private static DefaultToolCallBridge bridge(ToolCallDispatcher dispatcher, ToolContext context,
            CodeExecutionLimits limits) {
        return new DefaultToolCallBridge(dispatcher, context, "tu", "javascript", List.of(READ), limits, null);
    }

    private static ToolCallDispatcher recording(List<ToolContext> seen) {
        return dispatcher((toolUse, context) -> {
            seen.add(context);
            return ToolDispatchResult.builder().toolUseId(toolUse.getId()).text("ok").build();
        });
    }

    private interface Answer {
        ToolDispatchResult answer(ToolUse toolUse, ToolContext context);
    }

    private static ToolCallDispatcher dispatcher(Answer answer) {
        return new ToolCallDispatcher() {
            @Override
            public ToolDispatchResult dispatch(ToolUse toolUse, ToolCallOrigin origin, ToolContext context) {
                return answer.answer(toolUse, context);
            }

            @Override
            public ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools) {
                return this;
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A tracer whose every start throws, as a broken exporter wiring would. */
    private static final class ThrowingTracer implements Tracer {
        @Override
        public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
            throw new IllegalStateException("tracer is broken");
        }

        @Override
        public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
            throw new IllegalStateException("tracer is broken");
        }
    }
}
