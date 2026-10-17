/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.scheduling.event.ScheduledTaskEvent;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.StepCompletedEvent;
import at.aimon.core.scheduling.event.StepFailedEvent;
import at.aimon.core.scheduling.event.TaskCompletedEvent;
import at.aimon.core.scheduling.event.TaskFailedEvent;
import at.aimon.core.scheduling.event.TaskInterruptedEvent;
import at.aimon.core.scheduling.event.TaskStartedEvent;
import at.aimon.core.subagent.execution.SubagentLlmDefaults;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Spans;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.core.tracing.impl.TracingLlmClient;

/**
 * ST-1.2: a routine run is a {@code ROUTINE} root span with a {@code TOOL} span per step attempt, and whatever a step
 * starts nests under that attempt — all of it filed under the {@code routine:<taskId>} group key.
 */
class RoutineExecutorTracingTest {

    private DefaultAgentRuntimeRegistry agentRuntimeRegistry;
    private SimpleScheduledTaskEventPublisher eventPublisher;
    private RoutineExecutor executor;
    private InMemoryTraceSpanStore store;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        eventPublisher = new SimpleScheduledTaskEventPublisher();
        executor = new RoutineExecutor(agentRuntimeRegistry, eventPublisher);
        store = new InMemoryTraceSpanStore();
        tracer = new DefaultTracer(store, SpanExporter.noop());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("a step that starts a subagent nests ROUTINE → TOOL → SUBAGENT → ITERATION → LLM, all under the task's group key")
    void stepThatForksNestsTheWholeTreeUnderTheRoutineGroupKey() {
        executor.withTracer(tracer);
        final ForkingTool fork = new ForkingTool("Task", tracer);
        final ScheduledTask task = register(List.of(fork), step("Task", 0));
        final String groupKey = "routine:" + task.getId().value();

        assertThat(executor.execute(task).isSuccess()).isTrue();

        final List<TraceSpan> spans = store.bySession(groupKey);
        assertThat(spans).extracting(TraceSpan::getType).containsExactlyInAnyOrder(SpanType.ROUTINE, SpanType.TOOL,
                SpanType.SUBAGENT, SpanType.ITERATION, SpanType.LLM);
        // B1 regression guard: every descendant, not only the root, is filed under the group key.
        assertThat(spans).allSatisfy(span -> assertThat(span.getSessionId()).isEqualTo(groupKey));
        assertThat(store.bySession("")).isEmpty();

        final TraceSpan root = only(spans, SpanType.ROUTINE);
        final TraceSpan tool = only(spans, SpanType.TOOL);
        final TraceSpan subagent = only(spans, SpanType.SUBAGENT);
        final TraceSpan iteration = only(spans, SpanType.ITERATION);
        final TraceSpan llm = only(spans, SpanType.LLM);
        assertThat(root.getParentSpanId()).isEmpty();
        assertThat(root.getName()).isEqualTo(groupKey);
        assertThat(root.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(tool.getParentSpanId()).contains(root.getSpanId());
        assertThat(subagent.getParentSpanId()).contains(tool.getSpanId());
        assertThat(iteration.getParentSpanId()).contains(subagent.getSpanId());
        assertThat(llm.getParentSpanId()).contains(iteration.getSpanId());
        assertThat(spans).allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(root.getTraceId()));

        final ExecutionId stepExecutionId = fork.context.get().get(ToolContextKeys.EXECUTION_ID).orElseThrow();
        assertThat(root.getAttributes()).containsEntry("executionId", stepExecutionId.value())
                .containsEntry("taskId", task.getId().value())
                .containsEntry("agentRuntimeId", task.getBoundRuntimeId().value())
                .containsEntry("principal", "USER:alice");
        assertThat(outputs(root)).containsEntry("success", true).containsEntry("completedSteps", 1)
                .containsEntry("totalSteps", 1);

        assertThat(tool.getName()).isEqualTo("Task");
        assertThat(tool.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(tool.getAttributes()).containsEntry("invoker", "ROUTINE").containsEntry("stepIndex", "0")
                .containsEntry("attempt", "1").doesNotContainKey("invokerType");

        // The fork's own calls inherit the group key as their usage trace id.
        assertThat(fork.llm.metadata.get().getTraceId()).contains(groupKey);
    }

    @Test
    @DisplayName("each retry is its own TOOL span: attempt 1 ERROR, attempt 2 OK")
    void eachAttemptIsItsOwnToolSpan() {
        executor.withTracer(tracer);
        final AtomicInteger calls = new AtomicInteger();
        final Tool flaky = new ScriptedTool("Flaky", InterruptBehavior.NON_INTERRUPTIBLE,
                context -> calls.incrementAndGet() == 1
                        ? ToolResult.error("first try fails")
                        : ToolResult.success("ok"));
        final ScheduledTask task = register(List.of(flaky), step("Flaky", 1));

        assertThat(executor.execute(task).isSuccess()).isTrue();

        final List<TraceSpan> tools = ofType(store.bySession("routine:" + task.getId().value()), SpanType.TOOL);
        assertThat(tools).hasSize(2);
        final TraceSpan first = tools.stream().filter(s -> "1".equals(s.getAttributes().get("attempt"))).findFirst()
                .orElseThrow();
        final TraceSpan second = tools.stream().filter(s -> "2".equals(s.getAttributes().get("attempt"))).findFirst()
                .orElseThrow();
        assertThat(first.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(first.getErrorMessage()).contains("first try fails");
        assertThat(second.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(tools).allSatisfy(span -> assertThat(span.getAttributes()).containsEntry("stepIndex", "0"));
    }

    @Test
    @DisplayName("a timed-out attempt ends its TOOL span ERROR, and the run's root ERROR")
    void timeoutEndsToolAndRootInError() {
        executor.withTracer(tracer);
        final Tool slow = new ScriptedTool("Slow", InterruptBehavior.NON_INTERRUPTIBLE, context -> {
            Thread.sleep(5_000);
            return ToolResult.success("late");
        });
        final ScheduledTask task = register(List.of(slow), RoutineStep.builder().tool("Slow").toolParams("{}")
                .maxRetries(0).timeout(Duration.ofMillis(100)).build());

        assertThat(executor.execute(task).isSuccess()).isFalse();

        final List<TraceSpan> spans = store.bySession("routine:" + task.getId().value());
        final TraceSpan tool = only(spans, SpanType.TOOL);
        assertThat(tool.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(tool.getErrorMessage()).hasValueSatisfying(m -> assertThat(m).contains("timed out"));
        final TraceSpan root = only(spans, SpanType.ROUTINE);
        assertThat(root.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(root.getErrorMessage()).hasValueSatisfying(m -> assertThat(m).contains("timed out"));
    }

    @Test
    @DisplayName("a run interrupted mid-step ends its root INTERRUPTED, and a step that threw on the cancel too")
    void interruptedRunEndsRootInterrupted() {
        executor.withTracer(tracer);
        final AtomicReference<ScheduledTaskId> taskId = new AtomicReference<>();
        final Tool stoppable = new ScriptedTool("Stoppable", InterruptBehavior.COOPERATIVE, context -> {
            executor.interrupt(taskId.get(), InterruptReason.TASK_CANCELLED);
            throw new IllegalStateException("aborted by the interrupt");
        });
        final ScheduledTask task = register(List.of(stoppable), step("Stoppable", 0));
        taskId.set(task.getId());

        assertThat(executor.execute(task).isCancelled()).isTrue();

        final List<TraceSpan> spans = store.bySession("routine:" + task.getId().value());
        assertThat(only(spans, SpanType.ROUTINE).getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
        assertThat(only(spans, SpanType.TOOL).getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
    }

    @Test
    @DisplayName("an attempt that never reached its tool leaves no TOOL span; the root carries the error")
    void attemptThatNeverRanLeavesNoToolSpan() {
        executor.withTracer(tracer);
        final ScheduledTask task = register(List.of(), step("Missing", 0));

        assertThat(executor.execute(task).isSuccess()).isFalse();

        final List<TraceSpan> spans = store.bySession("routine:" + task.getId().value());
        assertThat(spans).extracting(TraceSpan::getType).containsExactly(SpanType.ROUTINE);
        assertThat(spans.get(0).getErrorMessage()).hasValueSatisfying(m -> assertThat(m).contains("Tool not found"));
    }

    @Test
    @DisplayName("tracing off: nothing recorded, and the step context carries no call metadata — as before")
    void tracingOffLeavesContextUnchanged() {
        final ContextCapturingTool capture = new ContextCapturingTool("Capture");
        final ScheduledTask task = register(List.of(capture), step("Capture", 0));

        assertThat(executor.execute(task).isSuccess()).isTrue();

        assertThat(capture.context.get().get(ToolContextKeys.LLM_CALL_METADATA_KEY)).isEmpty();
        assertThat(store.bySession("routine:" + task.getId().value())).isEmpty();
    }

    @Test
    @DisplayName("a throwing tracer changes nothing: same result, same events, no call metadata")
    void throwingTracerDoesNotChangeTheRun() {
        final RecordingListener untracedEvents = new RecordingListener();
        eventPublisher.addListener(untracedEvents);
        final ContextCapturingTool capture = new ContextCapturingTool("Capture");
        final ScheduledTask untraced = register(List.of(capture), step("Capture", 0));
        final RoutineResult untracedResult = executor.execute(untraced);
        eventPublisher.removeListener(untracedEvents);

        final RecordingListener tracedEvents = new RecordingListener();
        eventPublisher.addListener(tracedEvents);
        executor.withTracer(new ThrowingTracer());
        final RoutineResult tracedResult = executor.execute(untraced);

        assertThat(tracedResult.isSuccess()).isEqualTo(untracedResult.isSuccess()).isTrue();
        assertThat(tracedResult.getCompletedStepCount()).isEqualTo(untracedResult.getCompletedStepCount());
        assertThat(tracedEvents.types()).isEqualTo(untracedEvents.types());
        assertThat(capture.context.get().get(ToolContextKeys.LLM_CALL_METADATA_KEY)).isEmpty();
    }

    @Test
    @DisplayName("two fires of one task group together under routine:<id>, each fire its own trace")
    void twoFiresGroupUnderOneKeyAndSplitByTrace() {
        executor.withTracer(tracer);
        final ForkingTool fork = new ForkingTool("Task", tracer);
        final ScheduledTask task = register(List.of(fork), step("Task", 0));
        final String groupKey = "routine:" + task.getId().value();

        assertThat(executor.execute(task).isSuccess()).isTrue();
        assertThat(executor.execute(task).isSuccess()).isTrue();

        final List<TraceSpan> spans = store.bySession(groupKey);
        assertThat(spans).hasSize(10);
        final Map<String, List<TraceSpan>> byTrace = spans.stream()
                .collect(Collectors.groupingBy(TraceSpan::getTraceId));
        assertThat(byTrace).hasSize(2);
        assertThat(byTrace.values()).allSatisfy(
                trace -> assertThat(trace).extracting(TraceSpan::getType).containsExactlyInAnyOrder(SpanType.ROUTINE,
                        SpanType.TOOL, SpanType.SUBAGENT, SpanType.ITERATION, SpanType.LLM));
        assertThat(ofType(spans, SpanType.ROUTINE)).extracting(s -> s.getAttributes().get("executionId"))
                .doesNotHaveDuplicates();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ScheduledTask register(List<Tool> tools, RoutineStep step) {
        final Agent agent = DefaultAgent.builder().name("orca-trace").systemPrompt("test").build();
        final AgentRuntimeId runtimeId = AgentRuntimeId.from(agent);
        if (agentRuntimeRegistry.get(runtimeId).isEmpty()) {
            agentRuntimeRegistry.register(new StubAgentRuntime(runtimeId, agent, tools));
        }
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("traced-task").cronExpression("*/5 * * * *")
                .routine(List.of(step)).owner(Principal.user("alice")).boundRuntimeId(runtimeId).enabled(true).build();
    }

    private static RoutineStep step(String tool, int maxRetries) {
        return RoutineStep.builder().tool(tool).toolParams("{}").maxRetries(maxRetries).retryDelay(Duration.ZERO)
                .timeout(Duration.ofSeconds(5)).build();
    }

    private static TraceSpan only(List<TraceSpan> spans, SpanType type) {
        final List<TraceSpan> matching = ofType(spans, type);
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    private static List<TraceSpan> ofType(List<TraceSpan> spans, SpanType type) {
        return spans.stream().filter(s -> s.getType() == type).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputs(TraceSpan span) {
        return (Map<String, Object>) span.getOutputs().orElseThrow();
    }

    /**
     * Stands in for {@code Task}: reads the call metadata the step was handed and does what the subagent manager and
     * executor do with it — a SUBAGENT span under it, an ITERATION under that, and one traced LLM call.
     */
    private static final class ForkingTool extends AbstractTool {

        private final Tracer tracer;
        private final CapturingLlmClient llm = new CapturingLlmClient();
        private final AtomicReference<ToolContext> context = new AtomicReference<>();

        ForkingTool(String name, Tracer tracer) {
            super(name, "test tool", Map.of("type", "object", "properties", Map.of()));
            this.tracer = tracer;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext toolContext) {
            context.set(toolContext);
            final LlmCallMetadata parent = toolContext.get(ToolContextKeys.LLM_CALL_METADATA_KEY).orElseThrow();
            try (Tracer.Span subagent = Spans.startChildOf(tracer, parent, SpanType.SUBAGENT, "subagent:reporter", null)
                    .orElseThrow()) {
                final LlmCallMetadata forked = SubagentLlmDefaults.effectiveMetadata("reporter",
                        Spans.enrichSafely(subagent, parent));
                try (Tracer.Span iteration = Spans.startChildOf(tracer, forked, SpanType.ITERATION, "iteration#1", null)
                        .orElseThrow()) {
                    new TracingLlmClient(llm, tracer).sendMessage("sys", List.of(Message.user("go")), List.of(),
                            LlmModel.builder().name("m").build(), Spans.enrichSafely(iteration, forked));
                }
            }
            return ToolResult.success("forked");
        }
    }

    private static final class CapturingLlmClient implements LlmClient {
        private final AtomicReference<LlmCallMetadata> metadata = new AtomicReference<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata callMetadata) {
            metadata.set(callMetadata);
            return LlmResponse.text("done");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "capturing";
        }
    }

    private static final class ContextCapturingTool extends AbstractTool {
        private final AtomicReference<ToolContext> context = new AtomicReference<>();

        ContextCapturingTool(String name) {
            super(name, "test tool", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext toolContext) {
            context.set(toolContext);
            return ToolResult.success("ok");
        }
    }

    private static final class ScriptedTool extends AbstractTool {
        private final InterruptBehavior behavior;
        private final Body body;

        ScriptedTool(String name, InterruptBehavior behavior, Body body) {
            super(name, "test tool", Map.of("type", "object", "properties", Map.of()));
            this.behavior = behavior;
            this.body = body;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return behavior;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            try {
                return body.apply(context);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            }
        }
    }

    @FunctionalInterface
    private interface Body {
        ToolResult apply(ToolContext context) throws InterruptedException;
    }

    private static final class ThrowingTracer implements Tracer {
        @Override
        public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
            throw new IllegalStateException("tracer down");
        }

        @Override
        public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
            throw new IllegalStateException("tracer down");
        }
    }

    private static final class RecordingListener implements ScheduledTaskEventListener {
        private final List<ScheduledTaskEvent> received = new ArrayList<>();

        synchronized List<Class<?>> types() {
            return received.stream().<Class<?>>map(Object::getClass).toList();
        }

        private synchronized void record(ScheduledTaskEvent event) {
            received.add(event);
        }

        @Override
        public void onTaskStarted(TaskStartedEvent event) {
            record(event);
        }

        @Override
        public void onStepCompleted(StepCompletedEvent event) {
            record(event);
        }

        @Override
        public void onStepFailed(StepFailedEvent event) {
            record(event);
        }

        @Override
        public void onTaskCompleted(TaskCompletedEvent event) {
            record(event);
        }

        @Override
        public void onTaskFailed(TaskFailedEvent event) {
            record(event);
        }

        @Override
        public void onTaskInterrupted(TaskInterruptedEvent event) {
            record(event);
        }
    }

    private static final class StubAgentRuntime implements AgentRuntime {
        private final AgentRuntimeId id;
        private final Agent agent;
        private final List<Tool> tools;

        StubAgentRuntime(AgentRuntimeId id, Agent agent, List<Tool> tools) {
            this.id = Objects.requireNonNull(id);
            this.agent = Objects.requireNonNull(agent);
            this.tools = List.copyOf(tools);
        }

        @Override
        public AgentRuntimeId getId() {
            return id;
        }

        @Override
        public Agent getAgent() {
            return agent;
        }

        @Override
        public List<Tool> getAvailableTools() {
            return tools;
        }
    }
}
