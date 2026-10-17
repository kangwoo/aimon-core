package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.task.InMemoryBackgroundTaskStore;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.task.TaskTool;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.core.tracing.impl.TracingLlmClient;

@DisplayName("DefaultSubagentExecutionManager tracing — one SUBAGENT span per run (TRACE-01)")
class DefaultSubagentExecutionManagerTracingTest {

    private static final String SESSION = "s1";
    private static final LlmModel MODEL = LlmModel.builder().name("gpt-4").build();

    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();
    private InMemoryTraceSpanStore store;
    private Tracer tracer;
    private ScriptedLlmClient llm;
    private InMemorySubagentRegistry subagents;
    private DefaultToolRegistry tools;
    private InMemorySubagentBehaviorRegistry behaviors;
    /** Stands in for the launching tool call's TOOL span. */
    private Tracer.Span toolSpan;
    private LlmCallMetadata seededMetadata;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceSpanStore();
        tracer = new DefaultTracer(store, SpanExporter.noop());
        llm = new ScriptedLlmClient();
        subagents = new InMemorySubagentRegistry();
        subagents.register(Subagent.builder().name("explorer").systemPrompt("explore").maxIterations(3).build());
        tools = new DefaultToolRegistry();
        behaviors = new InMemorySubagentBehaviorRegistry();
        final Tracer.Span root = tracer.startRoot(SESSION, SpanType.TURN, "turn", null);
        final Tracer.Span iteration = tracer.startChild(root.context(), SpanType.ITERATION, "iteration#1", null);
        toolSpan = tracer.startChild(iteration.context(), SpanType.TOOL, "Task", null);
        seededMetadata = toolSpan.enrich(LlmCallMetadata.builder().traceId(SESSION).build());
    }

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
    }

    @Test
    @DisplayName("foreground: SUBAGENT under the TOOL span, the fork's ITERATION under SUBAGENT, totals recorded")
    void foregroundRunNestsUnderToolSpan() {
        llm.responses.add(LlmResponse.of("found it", List.of(), TokenUsage.of(3, 2, 5)));

        final SubagentExecutionResult result = manager().execute(launch(seededMetadata), "task-1", "explorer",
                "find the bug", "search");

        assertThat(result.isSuccess()).isTrue();
        final TraceSpan subagent = only(SpanType.SUBAGENT);
        assertThat(subagent.getName()).isEqualTo("subagent:explorer");
        assertThat(subagent.getParentSpanId()).contains(toolSpan.context().getSpanId());
        assertThat(subagent.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(subagent.getAttributes()).containsEntry("invokerType", "SUBAGENT")
                .containsEntry("subagent", "explorer").containsEntry("taskId", "task-1")
                .containsEntry("background", "false").containsEntry("resumed", "false")
                .containsEntry("codeBehavior", "false");
        // Summary-only (the default) records the goal's size, never its text.
        assertThat(inputs(subagent)).containsEntry("goalChars", 12).containsEntry("taskId", "task-1")
                .doesNotContainKey("goal");
        assertThat(outputs(subagent)).containsEntry("success", true).containsEntry("iterations", 1)
                .containsEntry("completionReason", "COMPLETED").containsEntry("finalAnswerChars", 8)
                .doesNotContainKey("finalAnswer");
        assertThat(subagent.getTokenUsage()).hasValueSatisfying(u -> assertThat(u.getTotalTokens()).isEqualTo(5));

        final TraceSpan iteration = only(SpanType.ITERATION);
        assertThat(iteration.getParentSpanId()).contains(subagent.getSpanId());
        assertThat(only(SpanType.LLM).getParentSpanId()).contains(iteration.getSpanId());
    }

    @Test
    @DisplayName("a FULL payload policy also records the goal and the final answer")
    void fullPolicyCapturesFinalAnswer() {
        llm.responses.add(LlmResponse.text("found it"));

        manager().withTracePayloadPolicy(TracePayloadPolicy.full()).execute(launch(seededMetadata), "task-1",
                "explorer", "find the bug", "search");

        final TraceSpan subagent = only(SpanType.SUBAGENT);
        assertThat(inputs(subagent)).containsEntry("goal", "find the bug");
        assertThat(outputs(subagent)).containsEntry("finalAnswer", "found it");
    }

    @Test
    @DisplayName("the goal is truncated to the policy cap; goalChars keeps its full length")
    void goalIsTruncatedToPolicyCap() {
        llm.responses.add(LlmResponse.text("ok"));

        manager().withTracePayloadPolicy(TracePayloadPolicy.full(4)).execute(launch(seededMetadata), "task-1",
                "explorer", "find the bug", "search");

        final Map<String, Object> inputs = inputs(only(SpanType.SUBAGENT));
        assertThat(inputs).containsEntry("goalChars", 12);
        assertThat(inputs.get("goal")).asString().startsWith("find").contains("(truncated");
    }

    @Test
    @DisplayName("an unknown subagent still leaves an ERROR span")
    void unknownSubagentEndsError() {
        final SubagentExecutionResult result = manager().execute(launch(seededMetadata), "task-1", "nobody", "go", "d");

        assertThat(result.isSuccess()).isFalse();
        final TraceSpan subagent = only(SpanType.SUBAGENT);
        assertThat(subagent.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(subagent.getParentSpanId()).contains(toolSpan.context().getSpanId());
    }

    @Test
    @DisplayName("a cancelled run ends INTERRUPTED")
    void cancelledRunEndsInterrupted() {
        final DefaultInterruptCoordinator parent = new DefaultInterruptCoordinator();
        parent.requestInterrupt(InterruptReason.USER_SIGINT);

        manager().execute(launch(seededMetadata).toBuilder().cancellationSignal(parent.getSignal()).build(), "task-1",
                "explorer", "go", "d");

        assertThat(only(SpanType.SUBAGENT).getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
    }

    @Test
    @DisplayName("a run the iteration cap stopped is OK, with the reason in the outputs")
    void boundedStopIsNotAnError() {
        subagents.register(Subagent.builder().name("looper").systemPrompt("loop").maxIterations(1).build());
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("x1", "Missing", Map.of()))));

        final SubagentExecutionResult result = manager().execute(launch(seededMetadata), "task-1", "looper", "go", "d");

        assertThat(result.isSuccess()).isFalse();
        final TraceSpan subagent = only(SpanType.SUBAGENT);
        assertThat(subagent.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(outputs(subagent)).containsEntry("completionReason", "MAX_ITERATIONS").containsKey("errorMessage");
    }

    @Test
    @DisplayName("background: the SUBAGENT span keeps the TOOL span as parent although that span closed first")
    void backgroundRunKeepsParentAfterItCloses() throws Exception {
        llm.responses.add(LlmResponse.text("indexed"));
        // A background Task returns at once, so the launching tool call's span closes before the run starts.
        toolSpan.close();

        manager().executeInBackground(launch(seededMetadata), "task-bg", "explorer", "index", "d").get(5,
                TimeUnit.SECONDS);

        final TraceSpan subagent = only(SpanType.SUBAGENT);
        assertThat(subagent.getParentSpanId()).contains(toolSpan.context().getSpanId());
        assertThat(subagent.getAttributes()).containsEntry("background", "true");
        assertThat(only(SpanType.ITERATION).getParentSpanId()).contains(subagent.getSpanId());
    }

    @Test
    @DisplayName("a code behavior's LLM call nests directly under the SUBAGENT span")
    void codeBehaviorLlmCallNestsUnderSubagent() {
        behaviors.register("explorer", (ctx, req, support) -> {
            final LlmResponse response = support.llmGateway().orElseThrow().sendMessage(SystemPromptParts.empty(),
                    List.of(Message.user("hi")), List.of(), support.resolvedModel(),
                    support.effectiveLlmCallMetadata());
            return support.success(response.getTextContent());
        });
        llm.responses.add(LlmResponse.text("behaved"));

        manager().execute(launch(seededMetadata), "task-1", "explorer", "go", "d");

        final TraceSpan subagent = only(SpanType.SUBAGENT);
        assertThat(subagent.getAttributes()).containsEntry("codeBehavior", "true");
        assertThat(spansOf(SpanType.ITERATION)).isEmpty();
        assertThat(only(SpanType.LLM).getParentSpanId()).contains(subagent.getSpanId());
    }

    @Test
    @DisplayName("parent metadata naming no span produces no span (no orphans)")
    void unseededMetadataProducesNoSpans() {
        llm.responses.add(LlmResponse.text("ok"));

        manager().execute(launch(LlmCallMetadata.builder().traceId("s-unseeded").build()), "task-1", "explorer", "go",
                "d");

        assertThat(store.bySession("s-unseeded")).isEmpty();
        assertThat(store.bySession(SESSION)).isEmpty();
    }

    @Test
    @DisplayName("nested: a Task inside a subagent nests SUBAGENT → ITERATION → TOOL(Task) → SUBAGENT → ITERATION → LLM")
    void nestedSubagentNestsRecursively() {
        subagents.register(Subagent.builder().name("inner").systemPrompt("inner").maxIterations(2).build());
        final DefaultSubagentExecutionManager manager = manager();
        tools.register(new TaskTool(MODEL, subagents, tools, new DefaultHookRegistry(), manager));
        // outer#1 launches inner; inner#1 answers; outer#2 answers.
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", "Task",
                Map.of("subagent_name", "inner", "prompt", "dig", "description", "dig deeper")))));
        llm.responses.add(LlmResponse.text("inner done"));
        llm.responses.add(LlmResponse.text("outer done"));

        final SubagentExecutionResult result = manager.execute(launch(seededMetadata), "task-1", "explorer", "go", "d");

        assertThat(result.isSuccess()).isTrue();
        final TraceSpan outer = subagentNamed("subagent:explorer");
        final TraceSpan inner = subagentNamed("subagent:inner");
        assertThat(outer.getParentSpanId()).contains(toolSpan.context().getSpanId());

        final TraceSpan innerTask = parentOf(inner);
        assertThat(innerTask.getType()).isEqualTo(SpanType.TOOL);
        assertThat(innerTask.getName()).isEqualTo("Task");
        final TraceSpan outerIteration = parentOf(innerTask);
        assertThat(outerIteration.getType()).isEqualTo(SpanType.ITERATION);
        assertThat(outerIteration.getParentSpanId()).contains(outer.getSpanId());

        final TraceSpan innerIteration = spansOf(SpanType.ITERATION).stream()
                .filter(s -> s.getParentSpanId().orElse("").equals(inner.getSpanId())).findFirst().orElseThrow();
        assertThat(spansOf(SpanType.LLM))
                .anySatisfy(s -> assertThat(s.getParentSpanId()).contains(innerIteration.getSpanId()));
    }

    @Test
    @DisplayName("a throwing tracer never breaks the run (fail-safe at the SUBAGENT site)")
    void throwingTracerIsFailSafe() {
        llm.responses.add(LlmResponse.text("found it"));
        final Tracer throwing = new Tracer() {
            @Override
            public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }

            @Override
            public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }
        };
        final DefaultSubagentExecutor executor = new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager()).withTracer(throwing);

        final SubagentExecutionResult result = new DefaultSubagentExecutionManager(executor, bgPool, null, behaviors,
                llm).withTracer(throwing).execute(launch(seededMetadata), "task-1", "explorer", "go", "d");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("found it");
    }

    @Test
    @DisplayName("an Error escaping the run still closes the SUBAGENT span, as ERROR")
    void errorEscapingRunClosesSpanAsError() {
        behaviors.register("explorer", (ctx, req, support) -> {
            throw new AssertionError("boom");
        });

        assertThatThrownBy(() -> manager().execute(launch(seededMetadata), "task-1", "explorer", "go", "d"))
                .isInstanceOf(AssertionError.class);

        final TraceSpan subagent = only(SpanType.SUBAGENT);
        assertThat(subagent.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(subagent.getEndTime()).isPresent();
    }

    // ── ST-2: a manager that built its own executor hands the tracer on ─────────

    @Test
    @DisplayName("ST-2: the LlmClient convenience constructor plus withTracer alone yields SUBAGENT → ITERATION → LLM")
    void convenienceConstructorWithTracerYieldsFullTree() {
        llm.responses.add(LlmResponse.text("found it"));
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(
                new TracingLlmClient(llm, tracer), new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .withTracer(tracer);
        try {
            manager.execute(launch(seededMetadata), "task-1", "explorer", "find the bug", "search");
        } finally {
            manager.close();
        }

        final TraceSpan subagent = only(SpanType.SUBAGENT);
        final TraceSpan iteration = only(SpanType.ITERATION);
        assertThat(iteration.getParentSpanId()).contains(subagent.getSpanId());
        assertThat(only(SpanType.LLM).getParentSpanId()).contains(iteration.getSpanId());
    }

    static Stream<Arguments> convenienceConstructors() {
        final Function<LlmClient, DefaultSubagentExecutionManager> threeArg = client -> new DefaultSubagentExecutionManager(
                client, new DefaultToolExecutionManager(), new DefaultHookExecutionManager());
        final Function<LlmClient, DefaultSubagentExecutionManager> withBehaviors = client -> new DefaultSubagentExecutionManager(
                client, new DefaultToolExecutionManager(), new DefaultHookExecutionManager(),
                new InMemorySubagentBehaviorRegistry());
        final Function<LlmClient, DefaultSubagentExecutionManager> withPoolAndStore = client -> new DefaultSubagentExecutionManager(
                client, new DefaultToolExecutionManager(), new DefaultHookExecutionManager(),
                new InMemorySubagentBehaviorRegistry(), Executors.newSingleThreadExecutor(),
                new InMemoryBackgroundTaskStore());
        final Function<LlmClient, DefaultSubagentExecutionManager> withOptions = client -> new DefaultSubagentExecutionManager(
                client, new DefaultToolExecutionManager(), new DefaultHookExecutionManager(),
                new InMemorySubagentBehaviorRegistry(),
                SubagentBackgroundExecutionOptions.of(Executors.newSingleThreadExecutor()));
        return Stream.of(Arguments.of("(llm, tools, hooks)", threeArg),
                Arguments.of("(llm, tools, hooks, behaviors)", withBehaviors),
                Arguments.of("(llm, tools, hooks, behaviors, pool, store)", withPoolAndStore),
                Arguments.of("(llm, tools, hooks, behaviors, options)", withOptions));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("convenienceConstructors")
    @DisplayName("ST-2: every LlmClient convenience constructor forwards the tracer to the executor it built")
    void everyConvenienceConstructorForwardsTheTracer(String shape,
            Function<LlmClient, DefaultSubagentExecutionManager> constructor) {
        llm.responses.add(LlmResponse.text("found it"));
        final DefaultSubagentExecutionManager manager = constructor.apply(new TracingLlmClient(llm, tracer))
                .withTracer(tracer);
        try {
            manager.execute(launch(seededMetadata), "task-1", "explorer", "go", "d");
        } finally {
            manager.close();
        }

        assertThat(only(SpanType.ITERATION).getParentSpanId()).as(shape).contains(only(SpanType.SUBAGENT).getSpanId());
    }

    @Test
    @DisplayName("ST-2: a manager handed an executor does not touch it — the run stays flat (principle guard)")
    void suppliedExecutorIsNotChanged() {
        llm.responses.add(LlmResponse.text("found it"));
        final TracingLlmClient client = new TracingLlmClient(llm, tracer);
        final DefaultSubagentExecutor untraced = new DefaultSubagentExecutor(client, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager());

        new DefaultSubagentExecutionManager(untraced, bgPool, null, behaviors, client).withTracer(tracer)
                .withTracePayloadPolicy(TracePayloadPolicy.full())
                .execute(launch(seededMetadata), "task-1", "explorer", "go", "d");

        assertThat(spansOf(SpanType.ITERATION)).isEmpty();
        assertThat(only(SpanType.LLM).getParentSpanId()).contains(only(SpanType.SUBAGENT).getSpanId());
    }

    @Test
    @DisplayName("ST-2: withTracePayloadPolicy reaches the owned executor's TOOL span content")
    void payloadPolicyReachesOwnedExecutor() {
        final ContextProbeTool probe = new ContextProbeTool();
        tools.register(probe);
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(
                new TracingLlmClient(llm, tracer), new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .withTracer(tracer).withTracePayloadPolicy(TracePayloadPolicy.full());
        try {
            manager.execute(launch(seededMetadata), "task-1", "explorer", "go", "d");
        } finally {
            manager.close();
        }

        assertThat(outputs(only(SpanType.TOOL))).containsEntry("content", "probed");
    }

    // ── ST-3: queue wait and pool rejection ──────────────────────────────────────

    @Test
    @DisplayName("ST-3: a background run records how long it waited for a worker; a foreground run records nothing")
    void backgroundRunRecordsQueueWait() throws Exception {
        llm.responses.add(LlmResponse.text("indexed"));
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch workerBusy = new CountDownLatch(1);
        // Pin the single worker so the background task has to wait in the queue.
        bgPool.submit(() -> {
            workerBusy.countDown();
            release.await();
            return null;
        });
        assertThat(workerBusy.await(5, TimeUnit.SECONDS)).isTrue();

        final var future = manager().executeInBackground(launch(seededMetadata), "task-bg", "explorer", "index", "d");
        Thread.sleep(150);
        release.countDown();
        future.get(5, TimeUnit.SECONDS);

        final TraceSpan subagent = only(SpanType.SUBAGENT);
        assertThat(subagent.getAttributes()).containsEntry("background", "true").doesNotContainKey("rejected");
        assertThat(Long.parseLong(subagent.getAttributes().get("queuedMs"))).isGreaterThanOrEqualTo(150L);
    }

    @Test
    @DisplayName("ST-3: a foreground run has no queuedMs attribute")
    void foregroundRunHasNoQueueWait() {
        llm.responses.add(LlmResponse.text("ok"));

        manager().execute(launch(seededMetadata), "task-1", "explorer", "go", "d");

        assertThat(only(SpanType.SUBAGENT).getAttributes()).doesNotContainKey("queuedMs");
    }

    @Test
    @DisplayName("ST-3: a task the pool rejects leaves a SUBAGENT ERROR span, closed before the SubagentStop hook")
    void rejectedTaskLeavesErrorSpanBeforeStopHook() throws Exception {
        final ExecutorService rejecting = Executors.newSingleThreadExecutor();
        rejecting.shutdown(); // every submission is now rejected
        final AtomicReference<List<TraceSpan>> seenByStopHook = new AtomicReference<>();
        final DefaultHookExecutionManager hooks = new DefaultHookExecutionManager() {
            @Override
            public List<HookResult> executeSubagentStop(SubagentStopContext context) {
                seenByStopHook.set(spansOf(SpanType.SUBAGENT));
                return super.executeSubagentStop(context);
            }
        };
        final TracingLlmClient client = new TracingLlmClient(llm, tracer);
        final DefaultSubagentExecutor executor = new DefaultSubagentExecutor(client, new DefaultToolExecutionManager(),
                hooks).withTracer(tracer);

        final SubagentExecutionResult result = new DefaultSubagentExecutionManager(executor, rejecting, hooks,
                behaviors, client).withTracer(tracer)
                .executeInBackground(launch(seededMetadata), "task-rej", "explorer", "index", "d")
                .get(5, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isFalse();
        final TraceSpan subagent = only(SpanType.SUBAGENT);
        assertThat(subagent.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(subagent.getParentSpanId()).contains(toolSpan.context().getSpanId());
        assertThat(subagent.getAttributes()).containsEntry("rejected", "true").containsEntry("background", "true")
                .containsEntry("taskId", "task-rej").doesNotContainKey("queuedMs").doesNotContainKey("executionId");
        assertThat(seenByStopHook.get()).as("the span was recorded before the Stop hook ran").singleElement()
                .satisfies(span -> assertThat(span.getEndTime()).isPresent());
    }

    // ── ST-4: the fork's execution id on the SUBAGENT span ───────────────────────

    @Test
    @DisplayName("ST-4: the SUBAGENT span names the execution id the fork's tools ran under")
    void subagentSpanCarriesForkExecutionId() {
        final ContextProbeTool probe = new ContextProbeTool();
        tools.register(probe);
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = manager().execute(launch(seededMetadata), "task-1", "explorer", "go",
                "d");

        final ExecutionId seen = probe.captured.get().get(ToolContextKeys.EXECUTION_ID).orElseThrow();
        assertThat(result.getExecutionId()).contains(seen);
        assertThat(only(SpanType.SUBAGENT).getAttributes()).containsEntry("executionId", seen.value());
    }

    @Test
    @DisplayName("ST-4: a code behavior and an unresolved subagent mint no execution id, so the span has none")
    void noExecutionIdWithoutReActLoop() {
        behaviors.register("explorer", (ctx, req, support) -> support.success("behaved"));

        manager().execute(launch(seededMetadata), "task-1", "explorer", "go", "d");
        manager().execute(launch(seededMetadata), "task-2", "nobody", "go", "d");

        assertThat(spansOf(SpanType.SUBAGENT)).hasSize(2)
                .allSatisfy(span -> assertThat(span.getAttributes()).doesNotContainKey("executionId"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private DefaultSubagentExecutionManager manager() {
        final TracingLlmClient client = new TracingLlmClient(llm, tracer);
        final DefaultSubagentExecutor executor = new DefaultSubagentExecutor(client, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager()).withTracer(tracer);
        return new DefaultSubagentExecutionManager(executor, bgPool, null, behaviors, client).withTracer(tracer);
    }

    private SubagentLaunchContext launch(LlmCallMetadata metadata) {
        return SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(subagents).toolRegistry(tools).hookRegistry(new DefaultHookRegistry())
                .defaultModel(MODEL).parentLlmCallMetadata(metadata).build();
    }

    private List<TraceSpan> spansOf(SpanType type) {
        return store.bySession(SESSION).stream().filter(s -> s.getType() == type).toList();
    }

    private TraceSpan only(SpanType type) {
        final List<TraceSpan> spans = spansOf(type);
        assertThat(spans).hasSize(1);
        return spans.get(0);
    }

    private TraceSpan subagentNamed(String name) {
        return spansOf(SpanType.SUBAGENT).stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    private TraceSpan parentOf(TraceSpan span) {
        return store.get(span.getParentSpanId().orElseThrow()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inputs(TraceSpan span) {
        return (Map<String, Object>) span.getInputs().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputs(TraceSpan span) {
        return (Map<String, Object>) span.getOutputs().orElseThrow();
    }

    /** Captures the context a fork's tool call receives. */
    private static final class ContextProbeTool extends AbstractTool {
        private final AtomicReference<ToolContext> captured = new AtomicReference<>();

        ContextProbeTool() {
            super("Probe", "Captures its tool context", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            captured.set(context);
            return ToolResult.success("probed");
        }
    }

    /** Returns scripted responses in order. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = Collections.synchronizedList(new ArrayList<>());

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            return next();
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
            return next();
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return next();
        }

        private LlmResponse next() {
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
