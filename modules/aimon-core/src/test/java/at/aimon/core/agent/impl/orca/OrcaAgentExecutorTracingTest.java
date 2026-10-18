package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.context.ContextDecision;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.ContextView;
import at.aimon.core.agent.context.DefaultContextEngine;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.llm.token.TokenEstimator;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentRegistry;
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

@DisplayName("OrcaAgentExecutor end-to-end tracing (TRACE-01)")
class OrcaAgentExecutorTracingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a turn produces a TURN root with LLM and TOOL spans nested under it, sharing one trace")
    void endToEndTraceTree() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

        // Scripted: first call asks to run the Echo tool, second call returns the final answer.
        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.of("", List.of(ToolUse.of("call-1", "Echo", Map.of())), TokenUsage.of(5, 3, 8)));
        stub.enqueue(LlmResponse.text("done"));

        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new EchoTool("Echo"));

        final OrcaAgentExecutor executor = createExecutor(new TracingLlmClient(stub, tracer));
        executor.tracer = tracer;

        executor.execute(createContext(toolRegistry),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-trace-1")).build());

        final List<TraceSpan> spans = store.bySession("conv-trace-1");

        final List<TraceSpan> turns = spans.stream().filter(s -> s.getType() == SpanType.TURN).toList();
        assertThat(turns).hasSize(1);
        final TraceSpan turn = turns.get(0);
        assertThat(turn.getParentSpanId()).isEmpty();
        assertThat(turn.getTraceId()).isEqualTo(turn.getSpanId());
        assertThat(turn.getStatus()).isEqualTo(SpanStatus.OK);

        // Two ReAct steps (iteration 1 calls Echo, iteration 2 returns the final answer), each an ITERATION span
        // nested directly under the turn.
        final List<TraceSpan> iterations = spans.stream().filter(s -> s.getType() == SpanType.ITERATION).toList();
        assertThat(iterations).hasSize(2);
        assertThat(iterations).allSatisfy(s -> assertThat(s.getParentSpanId()).contains(turn.getSpanId()));
        final Set<String> iterationIds = iterations.stream().map(TraceSpan::getSpanId).collect(Collectors.toSet());

        // TOOL span nests under an ITERATION span (not directly under the turn).
        final List<TraceSpan> toolSpans = spans.stream().filter(s -> s.getType() == SpanType.TOOL).toList();
        assertThat(toolSpans).hasSize(1);
        assertThat(toolSpans.get(0).getName()).isEqualTo("Echo");
        assertThat(toolSpans.get(0).getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(iterationIds).contains(toolSpans.get(0).getParentSpanId().orElseThrow());

        // Both LLM calls nest under ITERATION spans.
        final List<TraceSpan> llmSpans = spans.stream().filter(s -> s.getType() == SpanType.LLM).toList();
        assertThat(llmSpans).hasSize(2);
        assertThat(llmSpans).allSatisfy(s -> assertThat(iterationIds).contains(s.getParentSpanId().orElseThrow()));

        // The whole turn shares one trace id (the turn root span id).
        assertThat(spans).allSatisfy(s -> assertThat(s.getTraceId()).isEqualTo(turn.getSpanId()));
    }

    @Test
    @DisplayName("factory-wired: TURN → ITERATION → TOOL(Task) → SUBAGENT → ITERATION → {LLM, TOOL}")
    void taskNestsTheSubagentTreeUnderItsToolSpan() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

        // main#1 launches the helper; helper#1 calls Echo; helper#2 answers; main#2 answers.
        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.of("",
                List.of(ToolUse.of("call-1", "Task",
                        Map.of("subagent_name", "helper", "prompt", "echo once", "description", "echo"))),
                TokenUsage.of(5, 3, 8)));
        stub.enqueue(LlmResponse.of("", List.of(ToolUse.of("call-2", "Echo", Map.of())), TokenUsage.of(4, 2, 6)));
        stub.enqueue(LlmResponse.text("helper done"));
        stub.enqueue(LlmResponse.text("done"));

        // Only the factory is told about the tracer: it must reach the subagent manager and executor it builds.
        final OrcaAgentExecutor executor = new OrcaAgentExecutorFactory().withTracer(tracer).create(
                new TracingLlmClient(stub, tracer), new DefaultTranscriptManager(new InMemorySessionRecordStore()));
        final InMemorySubagentRegistry subagents = new InMemorySubagentRegistry();
        subagents.register(Subagent.builder().name("helper").systemPrompt("help").maxIterations(3).build());
        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new EchoTool("Echo"));
        final DefaultHookRegistry hookRegistry = new DefaultHookRegistry();
        toolRegistry.register(new TaskTool(LlmModel.builder().name("gpt-4").build(), subagents, toolRegistry,
                hookRegistry, executor.getSubagentExecutionManager()));

        final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry, subagents),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-task")).build());

        assertThat(result.isSuccess()).isTrue();
        final List<TraceSpan> spans = store.bySession("conv-task");
        final TraceSpan subagent = spans.stream().filter(s -> s.getType() == SpanType.SUBAGENT).findFirst()
                .orElseThrow();
        assertThat(subagent.getName()).isEqualTo("subagent:helper");

        final TraceSpan task = parentOf(store, subagent);
        assertThat(task.getType()).isEqualTo(SpanType.TOOL);
        assertThat(task.getName()).isEqualTo("Task");
        assertThat(task.getAttributes()).containsEntry("invokerType", "MAIN_AGENT");
        final TraceSpan mainIteration = parentOf(store, task);
        assertThat(mainIteration.getType()).isEqualTo(SpanType.ITERATION);
        final TraceSpan turn = parentOf(store, mainIteration);
        assertThat(turn.getType()).isEqualTo(SpanType.TURN);

        final List<TraceSpan> forkIterations = spans.stream().filter(s -> s.getType() == SpanType.ITERATION)
                .filter(s -> s.getParentSpanId().orElse("").equals(subagent.getSpanId())).toList();
        assertThat(forkIterations).hasSize(2);
        final Set<String> forkIterationIds = forkIterations.stream().map(TraceSpan::getSpanId)
                .collect(Collectors.toSet());
        final TraceSpan echo = spans.stream().filter(s -> s.getType() == SpanType.TOOL && s.getName().equals("Echo"))
                .findFirst().orElseThrow();
        assertThat(forkIterationIds).contains(echo.getParentSpanId().orElseThrow());
        assertThat(echo.getAttributes()).containsEntry("invokerType", "SUBAGENT");
        assertThat(spans.stream().filter(s -> s.getType() == SpanType.LLM)
                .filter(s -> forkIterationIds.contains(s.getParentSpanId().orElse("")))).hasSize(2);

        // One turn, one trace — the fork included.
        assertThat(spans).allSatisfy(s -> assertThat(s.getTraceId()).isEqualTo(turn.getSpanId()));
    }

    @Test
    @DisplayName("a tool started eagerly during the token stream also receives metadata pointing at its TOOL span")
    void eagerlyDispatchedToolSeesItsToolSpan() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());
        final CapturingTool probe = new CapturingTool("Probe");
        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(probe);
        final LlmClient client = new StreamingToolUseClient(ToolUse.of("call-1", "Probe", Map.of()));

        try (DefaultParallelToolDispatcher dispatcher = new DefaultParallelToolDispatcher(
                ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(2).streamingOverlap(true).build())) {
            final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
            final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
            final OrcaAgentExecutor executor = OrcaAgentExecutor.builder().llmClient(client)
                    .transcriptManager(new DefaultTranscriptManager(new InMemorySessionRecordStore()))
                    .toolExecutionManager(toolManager).hookExecutionManager(hookManager)
                    .commandExecutionManager(new DefaultCommandExecutionManager(client))
                    .subagentExecutionManager(new DefaultSubagentExecutionManager(client, toolManager, hookManager))
                    .useStreaming(true).parallelToolDispatcher(dispatcher).tracer(tracer).build();

            executor.execute(createContext(toolRegistry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-eager")).build());
        }

        final TraceSpan toolSpan = store.bySession("conv-eager").stream().filter(s -> s.getType() == SpanType.TOOL)
                .findFirst().orElseThrow();
        assertThat(probe.received).hasSize(1);
        assertThat(SpanContext.readFrom(probe.received.get(0)).orElseThrow().getSpanId())
                .isEqualTo(toolSpan.getSpanId());
    }

    @Test
    @DisplayName("TRACE-02 — a FULL payload policy captures the tool result content in the TOOL span")
    void fullPolicyCapturesToolContent() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.of("", List.of(ToolUse.of("call-1", "Echo", Map.of())), TokenUsage.of(5, 3, 8)));
        stub.enqueue(LlmResponse.text("done"));

        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new EchoTool("Echo"));

        final OrcaAgentExecutor executor = createExecutor(new TracingLlmClient(stub, tracer));
        executor.tracer = tracer;
        executor.tracePayloadPolicy = TracePayloadPolicy.full();

        executor.execute(createContext(toolRegistry),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-full")).build());

        final TraceSpan toolSpan = store.bySession("conv-full").stream().filter(s -> s.getType() == SpanType.TOOL)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        final Map<String, Object> outputs = (Map<String, Object>) toolSpan.getOutputs().orElseThrow();
        assertThat(outputs).containsEntry("isError", false).containsEntry("contentChars", "echoed".length());
        assertThat(outputs.get("content")).isEqualTo("echoed");
    }

    @Test
    @DisplayName("TRACE-02 — a FULL payload policy captures the system prompt in the TURN span")
    void fullPolicyCapturesSystemPrompt() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(new TracingLlmClient(stub, tracer));
        executor.tracer = tracer;
        executor.tracePayloadPolicy = TracePayloadPolicy.full();

        executor.execute(createContext(new DefaultToolRegistry()), OrcaAgentExecutionRequest.builder().userInput("hi")
                .sessionId(SessionId.of("conv-sysprompt-full")).build());

        final TraceSpan turn = store.bySession("conv-sysprompt-full").stream().filter(s -> s.getType() == SpanType.TURN)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        final Map<String, Object> inputs = (Map<String, Object>) turn.getInputs().orElseThrow();
        assertThat(inputs).containsEntry("userMessage", "hi");
        assertThat((int) inputs.get("systemPromptChars")).isPositive();
        assertThat(inputs.get("systemPrompt")).asString().contains("You are a test agent");
    }

    @Test
    @DisplayName("TRACE-02 — a FULL policy truncates a system prompt longer than the cap")
    void fullPolicyTruncatesLongSystemPrompt() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(new TracingLlmClient(stub, tracer));
        executor.tracer = tracer;
        executor.tracePayloadPolicy = TracePayloadPolicy.full(10); // cap below the rendered prompt length

        executor.execute(createContext(new DefaultToolRegistry()), OrcaAgentExecutionRequest.builder().userInput("hi")
                .sessionId(SessionId.of("conv-sysprompt-trunc")).build());

        final TraceSpan turn = store.bySession("conv-sysprompt-trunc").stream()
                .filter(s -> s.getType() == SpanType.TURN).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        final Map<String, Object> inputs = (Map<String, Object>) turn.getInputs().orElseThrow();
        // systemPromptChars is the pre-truncation length (> cap); the captured text carries the truncation marker.
        assertThat((int) inputs.get("systemPromptChars")).isGreaterThan(10);
        assertThat(inputs.get("systemPrompt")).asString().contains("(truncated");
    }

    @Test
    @DisplayName("the default summary-only policy records systemPromptChars but omits the system prompt text")
    void summaryPolicyOmitsSystemPromptText() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(new TracingLlmClient(stub, tracer));
        executor.tracer = tracer; // tracePayloadPolicy left at default summaryOnly

        executor.execute(createContext(new DefaultToolRegistry()), OrcaAgentExecutionRequest.builder().userInput("hi")
                .sessionId(SessionId.of("conv-sysprompt-summary")).build());

        final TraceSpan turn = store.bySession("conv-sysprompt-summary").stream()
                .filter(s -> s.getType() == SpanType.TURN).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        final Map<String, Object> inputs = (Map<String, Object>) turn.getInputs().orElseThrow();
        assertThat(inputs).containsKey("systemPromptChars").doesNotContainKey("systemPrompt");
        assertThat((int) inputs.get("systemPromptChars")).isPositive();
    }

    @Test
    @DisplayName("the default summary-only policy omits tool result content")
    void summaryPolicyOmitsToolContent() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.of("", List.of(ToolUse.of("call-1", "Echo", Map.of())), TokenUsage.of(5, 3, 8)));
        stub.enqueue(LlmResponse.text("done"));

        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new EchoTool("Echo"));

        final OrcaAgentExecutor executor = createExecutor(new TracingLlmClient(stub, tracer));
        executor.tracer = tracer; // tracePayloadPolicy left at default summaryOnly

        executor.execute(createContext(toolRegistry),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-summary")).build());

        final TraceSpan toolSpan = store.bySession("conv-summary").stream().filter(s -> s.getType() == SpanType.TOOL)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        final Map<String, Object> outputs = (Map<String, Object>) toolSpan.getOutputs().orElseThrow();
        assertThat(outputs).containsKey("contentChars").doesNotContainKey("content");
    }

    @Test
    @DisplayName("with the default NoopTracer no spans are recorded (tracing off)")
    void noTracerNoSpans() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();

        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(stub); // tracer left at default Tracer.noop()

        executor.execute(createContext(new DefaultToolRegistry()),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-noop")).build());

        assertThat(store.bySession("conv-noop")).isEmpty();
    }

    @Test
    @DisplayName("a throwing tracer never breaks the turn (fail-safe; conversation still proceeds)")
    void throwingTracerIsFailSafe() {
        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(stub);
        executor.tracer = new Tracer() {
            @Override
            public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }

            @Override
            public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
                throw new IllegalStateException("tracer down");
            }
        };

        final OrcaAgentExecutionResult result = executor.execute(createContext(new DefaultToolRegistry()),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-fs")).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
    }

    @Test
    @DisplayName("ST-5: the main turn's compaction summary call is an LLM span under the TURN, in the turn's session")
    void compactionSummaryCallNestsUnderTheTurn() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());
        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.text("the summary"));
        stub.enqueue(LlmResponse.text("done"));
        final LlmClient traced = new TracingLlmClient(stub, tracer);
        final OrcaAgentExecutor executor = createExecutor(traced);
        executor.tracer = tracer;

        final OrcaAgentExecutionResult result = executor.execute(
                createContext(new DefaultToolRegistry(), null, compactingEngine(traced)),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-compact")).build());

        assertThat(result.isSuccess()).isTrue();
        final List<TraceSpan> spans = store.bySession("conv-compact");
        final TraceSpan turn = spans.stream().filter(s -> s.getType() == SpanType.TURN).findFirst().orElseThrow();
        final TraceSpan summary = spans.stream().filter(s -> s.getType() == SpanType.LLM)
                .filter(s -> LlmCallMetadata.Feature.COMPACTION.equals(s.getAttributes().get("feature"))).findFirst()
                .orElseThrow();
        // The gate runs before the iteration span opens, so the summary call hangs off the turn.
        assertThat(summary.getParentSpanId()).contains(turn.getSpanId());
        assertThat(summary.getSessionId()).isEqualTo(turn.getSessionId());
        assertThat(summary.getTraceId()).isEqualTo(turn.getSpanId());

        // Usage attribution stays the engine's: the turn's react-loop feature did not leak into the summary call.
        final LlmCallMetadata summaryCall = stub.metadata.stream()
                .filter(m -> m.getFeature().filter(LlmCallMetadata.Feature.COMPACTION::equals).isPresent()).findFirst()
                .orElseThrow();
        assertThat(summaryCall.getComponent()).contains("compaction-engine");
        assertThat(summaryCall.getTraceId()).contains("conv-compact");
    }

    @Test
    @DisplayName("ST-5: tracing off, the main turn's context request carries no call metadata")
    void compactionRequestCarriesNoMetadataWhenTracingOff() {
        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.text("done"));
        final List<ContextRequest> requests = new CopyOnWriteArrayList<>();
        final OrcaAgentExecutor executor = createExecutor(stub); // tracer left at default Tracer.noop()

        executor.execute(createContext(new DefaultToolRegistry(), null, capturingEngine(requests)),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-off")).build());

        assertThat(requests).isNotEmpty().allSatisfy(r -> assertThat(r.getCallMetadata()).isEmpty());
    }

    @Test
    @DisplayName("ST-9: the main turn's compaction summary call is booked to the turn's principal, as /compact's is")
    void compactionSummaryCallCarriesThePrincipal() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());
        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.text("the summary"));
        stub.enqueue(LlmResponse.text("done"));
        final LlmClient traced = new TracingLlmClient(stub, tracer);
        final OrcaAgentExecutor executor = createExecutor(traced);
        executor.tracer = tracer;
        final Principal alice = Principal.user("alice");

        executor.execute(createContext(new DefaultToolRegistry(), null, compactingEngine(traced)),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-principal"))
                        .principal(alice).build());

        final LlmCallMetadata summaryCall = stub.metadata.stream()
                .filter(m -> m.getFeature().filter(LlmCallMetadata.Feature.COMPACTION::equals).isPresent()).findFirst()
                .orElseThrow();
        assertThat(summaryCall.getPrincipal()).contains(alice);
        // The principal is the only attribution the turn hands over; the rest stays the engine's.
        assertThat(summaryCall.getComponent()).contains("compaction-engine");
        assertThat(SpanContext.readFrom(summaryCall)).isPresent();
    }

    @Test
    @DisplayName("ST-9: tracing off, the main turn's context request still carries the principal, and only it")
    void compactionRequestCarriesThePrincipalWhenTracingOff() {
        final RecordingLlmClient stub = new RecordingLlmClient();
        stub.enqueue(LlmResponse.text("the summary"));
        stub.enqueue(LlmResponse.text("done"));
        final OrcaAgentExecutor executor = createExecutor(stub); // tracer left at default Tracer.noop()
        final Principal alice = Principal.user("alice");

        executor.execute(createContext(new DefaultToolRegistry(), null, compactingEngine(stub)),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-principal-off"))
                        .principal(alice).build());

        final LlmCallMetadata summaryCall = stub.metadata.stream()
                .filter(m -> m.getFeature().filter(LlmCallMetadata.Feature.COMPACTION::equals).isPresent()).findFirst()
                .orElseThrow();
        assertThat(summaryCall.getPrincipal()).contains(alice);
        assertThat(summaryCall.getComponent()).contains("compaction-engine");
        assertThat(summaryCall.getTraceId()).contains("conv-principal-off");
        assertThat(summaryCall.getTags()).isEmpty();
    }

    // ── wiring helpers (mirrors OrcaAgentExecutorParallelToolTest) ───────────────

    /** The default engine with every estimate fixed in the auto-compact band, so iteration 1 compacts. */
    private static ContextEngine compactingEngine(LlmClient client) {
        final TokenEstimator fixed = new TokenEstimator() {
            @Override
            public int estimate(String systemPrompt, List<Message> messages) {
                return 7_500;
            }

            @Override
            public int estimateMessage(Message message) {
                return 0;
            }

            @Override
            public int estimateText(String text) {
                return 0;
            }
        };
        final CompactionEngine compactionEngine = DefaultCompactionEngine.withDefaults(client, fixed,
                new DefaultHookExecutionManager());
        final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine,
                InMemoryModelContextWindowRegistry.builder()
                        .defaultLimits(ModelContextLimits.builder().contextWindow(10_000).reservedOutputTokens(1_000)
                                .autoCompactBuffer(2_000).warningBuffer(1_000).blockingBuffer(500).build())
                        .build(),
                fixed);
        return DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(compactionEngine)
                .tokenEstimator(fixed).build();
    }

    /** Records every request and answers with the transcript unchanged. */
    private static ContextEngine capturingEngine(List<ContextRequest> requests) {
        return new ContextEngine() {
            @Override
            public ContextDecision prepare(ContextRequest request) {
                requests.add(request);
                return ContextEngine.passthrough().prepare(request);
            }

            @Override
            public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
                return Optional.empty();
            }

            @Override
            public CompactionResult compactNow(ContextRequest request, String instructions) {
                throw new UnsupportedOperationException("not under test");
            }
        };
    }

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    private static TraceSpan parentOf(InMemoryTraceSpanStore store, TraceSpan span) {
        return store.get(span.getParentSpanId().orElseThrow()).orElseThrow();
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        return createContext(toolRegistry, null);
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry, SubagentRegistry subagentRegistry) {
        return createContext(toolRegistry, subagentRegistry, null);
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry, SubagentRegistry subagentRegistry,
            ContextEngine contextEngine) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:trace-1"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(subagentRegistry != null
                        ? subagentRegistry
                        : new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).controlFileSystem(fileSystem)
                .executionEnvironmentProvider(TestExecutionEnvironments.provider(fileSystem))
                .contextEngine(contextEngine).build();
    }

    /** Echoes a fixed success result. */
    private static final class EchoTool extends AbstractTool {
        EchoTool(String name) {
            super(name, "echo tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("echoed");
        }
    }

    /** A CONCURRENT_SAFE tool recording the LLM call metadata its ToolContext carried. */
    private static final class CapturingTool extends AbstractTool {
        private final List<LlmCallMetadata> received = new CopyOnWriteArrayList<>();

        CapturingTool(String name) {
            super(name, "capturing tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            context.get(ToolContextKeys.LLM_CALL_METADATA_KEY).ifPresent(received::add);
            return ToolResult.success("ok");
        }
    }

    /** Streams one closed tool_use block (eligible for eager dispatch), then a plain final answer. */
    private static final class StreamingToolUseClient implements LlmClient {
        private static final TokenUsage USAGE = TokenUsage.of(10, 10, 20);
        private final ToolUse toolUse;
        private int calls;

        StreamingToolUseClient(ToolUse toolUse) {
            this.toolUse = toolUse;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("streaming-only fake");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            if (calls++ == 0) {
                sink.accept(LlmStreamChunk.toolUseReady(0, toolUse));
                sink.accept(LlmStreamChunk.streamEnd(1, USAGE, Optional.of("tool_use"), StopReason.TOOL_USE));
                return LlmResponse.of("", List.of(toolUse), USAGE, StopReason.TOOL_USE);
            }
            sink.accept(LlmStreamChunk.textDelta(0, "done"));
            sink.accept(LlmStreamChunk.streamEnd(1, USAGE, Optional.of("end_turn"), StopReason.END_TURN));
            return LlmResponse.of("done", List.of(), USAGE, StopReason.END_TURN);
        }

        @Override
        public String getProviderName() {
            return "StreamingToolUse";
        }
    }

    /** Minimal scripted LLM client: returns enqueued responses in order, recording each call's metadata. */
    private static final class RecordingLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private final List<LlmCallMetadata> metadata = new CopyOnWriteArrayList<>();

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.remove(0);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata callMetadata) {
            metadata.add(callMetadata);
            return sendMessage(systemPrompt, messages, tools, modelConfig);
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }

    }
}
