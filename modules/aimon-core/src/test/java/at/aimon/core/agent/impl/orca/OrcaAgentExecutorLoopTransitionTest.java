package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.loop.LoopTransition;
import at.aimon.core.agent.loop.LoopTransitionReason;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
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
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.core.tracing.impl.TracingLlmClient;

/**
 * Loop re-entry tagging and the exception-free max-iterations termination.
 */
@DisplayName("OrcaAgentExecutor loop transitions")
class OrcaAgentExecutorLoopTransitionTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("resolveLoopTransition")
    class ResolveLoopTransition {

        @Test
        @DisplayName("no queued input and no forced compaction resolves to NEXT_ITERATION with no note")
        void plainContinue() {
            LoopTransition t = OrcaAgentExecutor.resolveLoopTransition(2, false, 0);

            assertThat(t.getReason()).isEqualTo(LoopTransitionReason.NEXT_ITERATION);
            assertThat(t.getIteration()).isEqualTo(2);
            assertThat(t.getNote()).isEmpty();
        }

        @Test
        @DisplayName("a drained queued input resolves to QUEUED_INPUT with a count note")
        void queuedInput() {
            LoopTransition t = OrcaAgentExecutor.resolveLoopTransition(3, false, 2);

            assertThat(t.getReason()).isEqualTo(LoopTransitionReason.QUEUED_INPUT);
            assertThat(t.getNote()).contains("2 queued input(s) drained");
        }

        @Test
        @DisplayName("a forced compaction resolves to BUDGET_COMPACT")
        void budgetCompaction() {
            LoopTransition t = OrcaAgentExecutor.resolveLoopTransition(4, true, 0);

            assertThat(t.getReason()).isEqualTo(LoopTransitionReason.BUDGET_COMPACT);
            assertThat(t.getNote()).isEmpty();
        }

        @Test
        @DisplayName("BUDGET_COMPACT wins over QUEUED_INPUT but preserves the drain in the note")
        void budgetCompactionOutranksQueuedInput() {
            LoopTransition t = OrcaAgentExecutor.resolveLoopTransition(5, true, 1);

            assertThat(t.getReason()).isEqualTo(LoopTransitionReason.BUDGET_COMPACT);
            assertThat(t.getNote()).contains("1 queued input(s) drained");
        }
    }

    @Nested
    @DisplayName("iteration-span annotation")
    class SpanAnnotation {

        @Test
        @DisplayName("the second iteration's span carries loop.transition=NEXT_ITERATION; the first carries none")
        void secondIterationTaggedNextIteration() {
            final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
            final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

            // iteration 1 runs a tool; iteration 2 returns the final answer.
            final RecordingLlmClient stub = new RecordingLlmClient();
            stub.enqueue(LlmResponse.of("", List.of(ToolUse.of("call-1", "Echo", Map.of())), TokenUsage.of(5, 3, 8)));
            stub.enqueue(LlmResponse.text("done"));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new EchoTool("Echo"));

            final OrcaAgentExecutor executor = createExecutor(new TracingLlmClient(stub, tracer));
            executor.tracer = tracer;

            executor.execute(createContext(toolRegistry, 5), OrcaAgentExecutionRequest.builder().userInput("hi")
                    .sessionId(SessionId.of("conv-lt-nextiter")).build());

            final TraceSpan iter1 = iterationSpan(store, "conv-lt-nextiter", 1);
            final TraceSpan iter2 = iterationSpan(store, "conv-lt-nextiter", 2);

            assertThat(iter1.getAttributes()).doesNotContainKey(OrcaAgentExecutor.LOOP_TRANSITION_ATTR);
            assertThat(iter2.getAttributes())
                    .containsEntry(OrcaAgentExecutor.LOOP_TRANSITION_ATTR, LoopTransitionReason.NEXT_ITERATION.name())
                    .containsEntry(OrcaAgentExecutor.LOOP_TRANSITION_ITERATION_ATTR, "2")
                    .doesNotContainKey(OrcaAgentExecutor.LOOP_TRANSITION_NOTE_ATTR);
        }

        @Test
        @DisplayName("a queued input drained at the tail tags the next iteration's span QUEUED_INPUT with a note")
        void queuedInputTaggedOnNextIterationSpan() {
            final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
            final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

            // iteration 1 runs a tool (keeps the loop alive); iteration 2 returns the final answer.
            final RecordingLlmClient stub = new RecordingLlmClient();
            stub.enqueue(LlmResponse.of("", List.of(ToolUse.of("call-1", "Echo", Map.of())), TokenUsage.of(5, 3, 8)));
            stub.enqueue(LlmResponse.text("done"));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new EchoTool("Echo"));

            final MessageQueueManager queueManager = new DefaultMessageQueueManager(
                    new InMemoryMessageQueueRepository());
            final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:loop-transition-queued");
            final OrcaAgentRuntime context = createContext(agentRuntimeId, toolRegistry, 5);

            final OrcaAgentExecutor executor = createExecutor(new TracingLlmClient(stub, tracer), queueManager);
            executor.tracer = tracer;

            // Queue a user input scoped to THIS context BEFORE running so it is drained at iteration 1's tail; the
            // drained count must then tag iteration 2's span QUEUED_INPUT with a "drained" note.
            queueManager.enqueue(QueuedInput.builder().agentRuntimeId(agentRuntimeId).inputText("also check the logs")
                    .priority(QueuedInputPriority.NEXT).build());

            executor.execute(context, OrcaAgentExecutionRequest.builder().userInput("hi")
                    .sessionId(SessionId.of("conv-lt-queued")).build());

            final TraceSpan iter2 = iterationSpan(store, "conv-lt-queued", 2);
            assertThat(iter2.getAttributes())
                    .containsEntry(OrcaAgentExecutor.LOOP_TRANSITION_ATTR, LoopTransitionReason.QUEUED_INPUT.name())
                    .containsEntry(OrcaAgentExecutor.LOOP_TRANSITION_ITERATION_ATTR, "2");
            assertThat(iter2.getAttributes().get(OrcaAgentExecutor.LOOP_TRANSITION_NOTE_ATTR))
                    .contains("queued input(s) drained");
        }
    }

    @Nested
    @DisplayName("max-iterations termination")
    class MaxIterations {

        @Test
        @DisplayName("hitting the iteration ceiling returns a failure carrying CompletionReason.MAX_ITERATIONS")
        void maxIterationsReturnsMaxIterationsReason() {
            // The model never stops calling tools, so the loop is driven to the ceiling.
            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new EchoTool("Echo"));

            final OrcaAgentExecutor executor = createExecutor(new AlwaysToolUseLlmClient());

            final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry, 2),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("conv-lt-maxiter"))
                            .build());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
            assertThat(result.getIterationCount()).isEqualTo(2);
        }
    }

    private static TraceSpan iterationSpan(InMemoryTraceSpanStore store, String sessionId, int iteration) {
        return store.bySession(sessionId).stream()
                .filter(s -> s.getType() == SpanType.ITERATION && ("iteration#" + iteration).equals(s.getName()))
                .findFirst().orElseThrow();
    }

    // ── wiring helpers (mirrors OrcaAgentExecutorTracingTest) ────────────────────

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    private OrcaAgentExecutor createExecutor(LlmClient client, MessageQueueManager queueManager) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutorFactory().withMessageQueueManager(queueManager).create(client,
                new DefaultTranscriptManager(new InMemorySessionRecordStore()), toolManager, hookManager,
                commandManager, subagentManager);
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry, int maxIterations) {
        return createContext(AgentRuntimeId.of("agent:loop-transition"), toolRegistry, maxIterations);
    }

    private OrcaAgentRuntime createContext(AgentRuntimeId agentRuntimeId, DefaultToolRegistry toolRegistry,
            int maxIterations) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(agentRuntimeId)
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(maxIterations)
                        .systemPrompt("You are a test agent").build())
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

    /** Always asks to run the Echo tool, so the ReAct loop never terminates on its own. */
    private static final class AlwaysToolUseLlmClient implements LlmClient {
        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.of("", List.of(ToolUse.of("call", "Echo", Map.of())), TokenUsage.of(1, 1, 2));
        }

        @Override
        public String getProviderName() {
            return "AlwaysToolUse";
        }

    }
}
