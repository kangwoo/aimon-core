package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
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
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Guards the <b>session well-formedness</b> invariant across every terminal path of the ReAct loop: the
 * session a turn leaves behind must pair every assistant {@code tool_use} with a matching {@code tool_result}.
 *
 * <p>
 * The loop commits the assistant message carrying the {@code tool_use} blocks and the message carrying their results as
 * two separate {@code addMessage} calls (see {@code OrcaAgentExecutor#executeReActLoop}). Every terminal handler
 * (stalled iteration, budget stop, interrupt, max-iterations, execution error) then snapshots whatever state
 * {@code TranscriptBuffer} happens to be in, and the loop's {@code finally} block persists that same state through
 * {@code TranscriptManager#saveSilently}. An unpaired {@code tool_use} is therefore not a transient glitch: it is
 * written to the store and replayed on the <em>next</em> turn, where every major LLM provider rejects the request
 * ("tool_use ids were found without tool_result blocks") — an unrecoverable failure for that session.
 *
 * <p>
 * The existing terminal-path suites ({@code OrcaAgentExecutorBudgetTest}, {@code OrcaAgentExecutorInterruptTest},
 * {@code OrcaAgentExecutorStalledIterationTest}, {@code OrcaAgentExecutorLoopTransitionTest}) assert only the
 * resulting
 * {@link CompletionReason}; none inspects the session. These tests close that gap so a refactor of the
 * tool-dispatch path cannot silently produce an unresumable session while every other test still passes.
 */
@DisplayName("OrcaAgentExecutor terminal conversation well-formedness (tool_use/tool_result pairing)")
class OrcaAgentExecutorTerminalSessionStateTest {

    @TempDir
    Path tempDir;

    private final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();

    @Test
    @DisplayName("COMPLETED: a tool round-trip leaves every tool_use answered")
    void completedTurnLeavesPairedConversation() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(toolCallResponse("tu-1", EchoTool.TOOL_NAME));
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

        final SessionId sessionId = SessionId.generate();
        final OrcaAgentExecutionResult result = run(llmClient, registryOf(new EchoTool()), sessionId, null);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertToolUsesArePaired(result.getSnapshot());
        assertToolUsesArePaired(persistedSnapshot(sessionId));
    }

    @Test
    @DisplayName("MAX_ITERATIONS: the iteration ceiling never truncates a turn between tool_use and tool_result")
    void maxIterationsLeavesPairedConversation() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        // Every response asks for a tool, so the loop can only end by hitting the ceiling. Ids are unique per call,
        // as a real provider would emit them — reusing one id would mask an unpaired result.
        llmClient.setFallback(call -> toolCallResponse("tu-" + call, EchoTool.TOOL_NAME));

        final SessionId sessionId = SessionId.generate();
        final OrcaAgentExecutionResult result = run(llmClient, registryOf(new EchoTool()), sessionId,
                ExecutionBudget.builder().maxIterations(3).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.MAX_ITERATIONS);
        assertToolUsesArePaired(result.getSnapshot());
        assertToolUsesArePaired(persistedSnapshot(sessionId));
    }

    @Test
    @DisplayName("TOKEN_BUDGET_EXCEEDED: a budget stop between iterations leaves every tool_use answered")
    void budgetStopLeavesPairedConversation() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        // One tool round-trip blows the 50-token ceiling; the stop is taken at the head of the next iteration,
        // after iteration 1 committed both its assistant message and its tool results.
        llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-1", EchoTool.TOOL_NAME, Map.of())),
                TokenUsage.of(40, 40, 80)));
        llmClient.setFallback(LlmResponse.of("unreachable", List.of(), TokenUsage.of(5, 5, 10)));

        final SessionId sessionId = SessionId.generate();
        final OrcaAgentExecutionResult result = run(llmClient, registryOf(new EchoTool()), sessionId,
                ExecutionBudget.builder().maxTokens(50).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TOKEN_BUDGET_EXCEEDED);
        assertToolUsesArePaired(result.getSnapshot());
        assertToolUsesArePaired(persistedSnapshot(sessionId));
    }

    @Test
    @DisplayName("INTERRUPTED: a trip fired from inside a tool still leaves that tool's result committed")
    void interruptAtIterationTailLeavesPairedConversation() {
        final AtomicReference<InterruptCoordinator> coordinatorRef = new AtomicReference<>();
        final TrippingTool tool = new TrippingTool(
                () -> coordinatorRef.get().requestInterrupt(InterruptReason.USER_SIGINT));

        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(toolCallResponse("tu-1", TrippingTool.TOOL_NAME));
        llmClient.setFallback(LlmResponse.of("unreachable", List.of(), TokenUsage.of(5, 5, 10)));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        executor.interruptCoordinatorFactory = () -> {
            final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            coordinatorRef.set(coordinator);
            return coordinator;
        };

        final SessionId sessionId = SessionId.generate();
        final OrcaAgentExecutionResult result = executor.execute(createContext(registryOf(tool), 10),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(sessionId).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        // The interrupt is taken at the iteration tail — after the tool ran — so its result must be on record.
        assertThat(toolUseIds(result.getSnapshot())).containsExactly("tu-1");
        assertToolUsesArePaired(result.getSnapshot());
        assertToolUsesArePaired(persistedSnapshot(sessionId));
    }

    @Test
    @DisplayName("ERROR (stalled iteration): the death-spiral abort leaves every failed tool_use answered by an error"
            + " result")
    void stalledIterationAbortLeavesPairedConversation() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(toolCallResponse("tu-1", FailingTool.TOOL_NAME));
        llmClient.enqueue(toolCallResponse("tu-2", FailingTool.TOOL_NAME));
        llmClient.enqueue(toolCallResponse("tu-3", FailingTool.TOOL_NAME));
        llmClient.setFallback(LlmResponse.of("unreachable", List.of(), TokenUsage.of(5, 5, 10)));

        final SessionId sessionId = SessionId.generate();
        final OrcaAgentExecutionResult result = run(llmClient, registryOf(new FailingTool()), sessionId, null);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(toolUseIds(result.getSnapshot())).containsExactly("tu-1", "tu-2", "tu-3");
        assertToolUsesArePaired(result.getSnapshot());
        assertToolUsesArePaired(persistedSnapshot(sessionId));
    }

    @Test
    @DisplayName("ERROR (LLM failure): an exception on the follow-up call cannot orphan the previous tool_use")
    void llmFailureAfterToolRoundTripLeavesPairedConversation() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(toolCallResponse("tu-1", EchoTool.TOOL_NAME));
        llmClient.setThrowFromCall(2, new LlmClientException("provider exploded"));

        final SessionId sessionId = SessionId.generate();
        final OrcaAgentExecutionResult result = run(llmClient, registryOf(new EchoTool()), sessionId, null);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
        assertThat(toolUseIds(result.getSnapshot())).containsExactly("tu-1");
        assertToolUsesArePaired(result.getSnapshot());
        assertToolUsesArePaired(persistedSnapshot(sessionId));
    }

    @Test
    // Dispatch here is sequential (the file-local EchoTool leaves getConcurrencyBehavior() at SEQUENTIAL and the
    // fixture
    // keeps the executor's default sequential dispatcher); what is pinned is the result-for-result pairing and order
    // invariant, which must hold for every dispatch shape. Parallel dispatch itself is covered by
    // DefaultParallelToolDispatcherTest and OrcaAgentExecutorParallelToolTest.
    @DisplayName("a multi-tool batch is answered result-for-result, in the model's original order")
    void multiToolBatchIsFullyAnswered() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-a", EchoTool.TOOL_NAME, Map.of()),
                ToolUse.of("tu-b", EchoTool.TOOL_NAME, Map.of()), ToolUse.of("tu-c", EchoTool.TOOL_NAME, Map.of())),
                TokenUsage.of(5, 5, 10)));
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

        final SessionId sessionId = SessionId.generate();
        final OrcaAgentExecutionResult result = run(llmClient, registryOf(new EchoTool()), sessionId, null);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(toolResultIds(result.getSnapshot())).containsExactly("tu-a", "tu-b", "tu-c");
        assertToolUsesArePaired(result.getSnapshot());
    }

    // =================================================================================================================
    // Invariant
    // =================================================================================================================

    /**
     * Asserts the provider-level invariant: the set of {@code tool_use} ids in the session and the set of
     * {@code tool_result} ids answering them must be identical, with no id used twice on either side. A missing result
     * makes the session unresumable; an orphan result is rejected just as hard.
     */
    private static void assertToolUsesArePaired(SessionSnapshot snapshot) {
        final List<String> useIds = toolUseIds(snapshot);
        final List<String> resultIds = toolResultIds(snapshot);

        assertThat(useIds).as("tool_use ids must be unique").doesNotHaveDuplicates();
        assertThat(resultIds).as("tool_result ids must be unique").doesNotHaveDuplicates();
        assertThat(resultIds).as("every assistant tool_use must be answered by exactly one tool_result, and no "
                + "tool_result may be orphaned — an unpaired conversation is rejected by the provider on the next turn")
                .containsExactlyInAnyOrderElementsOf(useIds);
    }

    private static List<String> toolUseIds(SessionSnapshot snapshot) {
        return snapshot.getConversationHistory().stream().flatMap(m -> m.getToolUses().stream()).map(ToolUse::getId)
                .toList();
    }

    private static List<String> toolResultIds(SessionSnapshot snapshot) {
        return snapshot.getConversationHistory().stream().flatMap(m -> m.getToolUseResults().stream())
                .map(ToolUseResult::getToolUseId).toList();
    }

    /** The state a later turn actually resumes from — written by the loop's {@code saveSilently} finally block. */
    private SessionSnapshot persistedSnapshot(SessionId sessionId) {
        return SessionSnapshot.from(repository.load(sessionId)
                .orElseThrow(() -> new AssertionError("conversation was never persisted: " + sessionId)));
    }

    // =================================================================================================================
    // Fixtures
    // =================================================================================================================

    private OrcaAgentExecutionResult run(LlmClient llmClient, DefaultToolRegistry toolRegistry, SessionId sessionId,
            ExecutionBudget budget) {
        final OrcaAgentExecutionRequest.Builder request = OrcaAgentExecutionRequest.builder().userInput("hi")
                .sessionId(sessionId);
        if (budget != null) {
            request.budget(budget);
        }
        return createExecutor(llmClient).execute(createContext(toolRegistry, 10), request.build());
    }

    private static LlmResponse toolCallResponse(String toolUseId, String toolName) {
        return LlmResponse.of("act", List.of(ToolUse.of(toolUseId, toolName, Map.of())), TokenUsage.of(5, 5, 10));
    }

    private static DefaultToolRegistry registryOf(AbstractTool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (AbstractTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry, int maxIterations) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(maxIterations)
                        .systemPrompt("You are a test agent").build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(repository), toolManager, hookManager,
                commandManager, subagentManager);
    }

    /** Scripted client: queued responses first, then an optional fallback; can be made to throw from the Nth call. */
    private static final class SequencedLlmClient implements LlmClient {

        private final List<LlmResponse> responses = new ArrayList<>();
        private IntFunction<LlmResponse> fallback;
        private int throwFromCall = Integer.MAX_VALUE;
        private RuntimeException failure;
        private int callCount;

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        void setFallback(LlmResponse response) {
            this.fallback = call -> response;
        }

        /** Fallback whose response depends on the 1-based call number — lets a looping script emit unique ids. */
        void setFallback(IntFunction<LlmResponse> responseForCall) {
            this.fallback = responseForCall;
        }

        void setThrowFromCall(int oneBasedCall, RuntimeException exception) {
            this.throwFromCall = oneBasedCall;
            this.failure = exception;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            callCount++;
            if (callCount >= throwFromCall) {
                throw failure;
            }
            if (!responses.isEmpty()) {
                return responses.remove(0);
            }
            if (fallback != null) {
                return fallback.apply(callCount);
            }
            return LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10));
        }

        @Override
        public String getProviderName() {
            return "Sequenced";
        }

    }

    /** Trivially succeeding tool — drives a well-formed tool round-trip. */
    private static final class EchoTool extends AbstractTool {

        static final String TOOL_NAME = "Echo";

        EchoTool() {
            super(TOOL_NAME, "Echoes back", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("echo");
        }
    }

    /** Always-failing tool — three consecutive turns of these trip the death-spiral guard. */
    private static final class FailingTool extends AbstractTool {

        static final String TOOL_NAME = "FailingTool";

        FailingTool() {
            super(TOOL_NAME, "Always fails", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.error("always fails");
        }
    }

    /** Succeeds, but trips the interrupt coordinator from inside {@code execute()} so the tail check fires. */
    private static final class TrippingTool extends AbstractTool {

        static final String TOOL_NAME = "TrippingTool";

        private final Runnable sideEffect;

        TrippingTool(Runnable sideEffect) {
            super(TOOL_NAME, "Trips the interrupt coordinator", Map.of("type", "object", "properties", Map.of()));
            this.sideEffect = sideEffect;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            sideEffect.run();
            return ToolResult.success("tripped");
        }
    }
}
