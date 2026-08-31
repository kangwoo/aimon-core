package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;
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

    // ── wiring helpers (mirrors OrcaAgentExecutorParallelToolTest) ───────────────

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:trace-1"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
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

    /** Minimal scripted LLM client: returns enqueued responses in order. */
    private static final class RecordingLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }

    }
}
