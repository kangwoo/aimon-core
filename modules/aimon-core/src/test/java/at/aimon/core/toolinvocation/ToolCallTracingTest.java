package at.aimon.core.toolinvocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.interrupt.CancelledExecutionException;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

@DisplayName("ToolCallTracing.recordFailure — a thrown tool call ends its TOOL span INTERRUPTED or ERROR")
class ToolCallTracingTest {

    private InMemoryTraceSpanStore store;
    private Tracer.Span toolSpan;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());
        final Tracer.Span root = tracer.startRoot("s1", SpanType.TURN, "turn", null);
        toolSpan = tracer.startChild(root.context(), SpanType.TOOL, "Task", Map.of());
    }

    @Test
    @DisplayName("a CancelledExecutionException ends the span INTERRUPTED")
    void cancellationExceptionIsInterrupted() {
        ToolCallTracing.recordFailure(toolSpan, new CancelledExecutionException(InterruptReason.USER_SIGINT),
                ToolContext.empty());

        assertThat(closed().getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
    }

    @Test
    @DisplayName("any throwable while the context's cancellation signal is tripped ends the span INTERRUPTED")
    void trippedSignalIsInterrupted() {
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
        final ToolContext context = ToolContext.builder()
                .put(InterruptToolKeys.CANCELLATION_SIGNAL, coordinator.getSignal()).build();

        ToolCallTracing.recordFailure(toolSpan, new IllegalStateException("unwound"), context);

        assertThat(closed().getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
    }

    @Test
    @DisplayName("an Error with no cancellation ends the span ERROR")
    void errorIsError() {
        ToolCallTracing.recordFailure(toolSpan, new AssertionError("boom"), ToolContext.empty());

        assertThat(closed().getStatus()).isEqualTo(SpanStatus.ERROR);
    }

    private TraceSpan closed() {
        toolSpan.close();
        return store.get(toolSpan.context().getSpanId()).orElseThrow();
    }
}
