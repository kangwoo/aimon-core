package at.aimon.core.hook.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * Which answer {@link HookContext#getExecutionCancellation()} gives is a property of the event (EE-80): a gate hands
 * out the execution's signal always, a report only while it has not tripped — so the rule is applied when a hook
 * reads the context, per hook, and not once when the chain's context is built.
 */
@DisplayName("HookContext#getExecutionCancellation(): gates keep the signal, reports drop it once tripped (EE-80)")
class ExecutionCancellationContextsTest {

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();
    private static final ToolUse TOOL_USE = ToolUse.of("call-1", "Bash", Map.of());

    private static final List<Function<CancellationSignal, HookContext>> GATES = List.of(
            signal -> OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                    .hookRegistry(REGISTRY).executionCancellation(signal).userMessage("hi").build(),
            signal -> PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                    .hookRegistry(REGISTRY).executionCancellation(signal).trigger(CompactionTrigger.AUTO).build(),
            signal -> PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                    .hookRegistry(REGISTRY).executionCancellation(signal).toolUse(TOOL_USE).iterationCount(1).build(),
            signal -> PermissionRequestContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                    .hookRegistry(REGISTRY).executionCancellation(signal).toolName("Bash")
                    .toolInput(ToolInput.of(Map.of())).build());

    private static final List<Function<CancellationSignal, HookContext>> REPORTS = List.of(
            ExecutionCancellationContextsTest::onStop, ExecutionCancellationContextsTest::postCompact,
            signal -> SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                    .hookRegistry(REGISTRY).executionCancellation(signal).subagentName("Explore").taskId("t-1")
                    .goal("map the module graph").build(),
            signal -> SubagentStopContext
                    .builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(REGISTRY)
                    .executionCancellation(signal).subagentName("Explore").taskId("t-1").success(true).build(),
            ExecutionCancellationContextsTest::postTool,
            signal -> PermissionDeniedContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                    .hookRegistry(REGISTRY).executionCancellation(signal).toolName("Bash")
                    .toolInput(ToolInput.of(Map.of())).denyReason("no").build());

    @Test
    void aGateHandsOutTheSignalBeforeAndAfterItTrips() {
        for (Function<CancellationSignal, HookContext> gate : GATES) {
            try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
                final HookContext context = gate.apply(coordinator.getSignal());
                assertThat(context.getExecutionCancellation()).as(context.getClass().getSimpleName())
                        .containsSame(coordinator.getSignal());

                coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

                assertThat(context.getExecutionCancellation()).as(context.getClass().getSimpleName())
                        .containsSame(coordinator.getSignal());
            }
        }
    }

    @Test
    void aReportHandsOutTheSignalOnlyWhileItHasNotTripped() {
        for (Function<CancellationSignal, HookContext> report : REPORTS) {
            try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
                final HookContext context = report.apply(coordinator.getSignal());
                assertThat(context.getExecutionCancellation()).as(context.getClass().getSimpleName())
                        .containsSame(coordinator.getSignal());

                // The same context object, as the second hook of a chain would read it after an interrupt.
                coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

                assertThat(context.getExecutionCancellation()).as(context.getClass().getSimpleName()).isEmpty();
            }
        }
    }

    @Test
    void everyContextBuiltWithoutASignalAnswersEmpty() {
        for (Function<CancellationSignal, HookContext> event : concat(GATES, REPORTS)) {
            final HookContext context = event.apply(null);
            assertThat(context.getExecutionCancellation()).as(context.getClass().getSimpleName()).isEmpty();
        }
    }

    @Test
    void postToolKeepsTheRuleAcrossARewrittenOutput() {
        try (DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator()) {
            final PostToolContext rewritten = ((PostToolContext) postTool(coordinator.getSignal()))
                    .withCurrentOutput(ToolResult.success("[redacted]"));
            assertThat(rewritten.getExecutionCancellation()).containsSame(coordinator.getSignal());

            coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

            assertThat(rewritten.getExecutionCancellation()).isEmpty();
        }
    }

    private static HookContext onStop(CancellationSignal signal) {
        final Instant now = Instant.now();
        return OnStopContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(REGISTRY)
                .executionCancellation(signal).success(true).finalAnswer("done")
                .metadata(ExecutionMetadata.builder().iterationCount(1).duration(Duration.ofMillis(50))
                        .startTime(now.minusMillis(50)).endTime(now).build())
                .build();
    }

    private static HookContext postCompact(CancellationSignal signal) {
        final Instant now = Instant.now();
        return PostCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(REGISTRY).executionCancellation(signal).trigger(CompactionTrigger.AUTO)
                .compactionMetadata(CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).startedAt(now)
                        .completedAt(now).build())
                .compactSummary("summary").transcriptBuffer(new TranscriptBuffer(SessionId.generate())).build();
    }

    private static HookContext postTool(CancellationSignal signal) {
        return PostToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(REGISTRY).executionCancellation(signal).toolUse(TOOL_USE)
                .toolUseResult(ToolUseResult.success("call-1", "ok")).iterationCount(1).build();
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}
