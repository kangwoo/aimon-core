package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
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
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Pins what an interrupt does to a <b>multi-tool batch</b>.
 *
 * <p>
 * When the model returns several {@code tool_use} blocks in one response the whole {@code List<ToolUse>} goes to the
 * dispatcher at once. The loop's iteration <i>head</i> and <i>tail</i> checks cannot see inside that batch, so a trip
 * landing while tool 1 runs used to leave tools 2..N executing to completion — a user who pressed Ctrl+C at the first
 * of five tool calls still watched the other four run. The interrupt design
 * ({@code docs/design/agent-execution/interrupt.md} §7) mandates a check immediately before each
 * {@code toolUse}, with the remainder short-circuited to an {@code Interrupted — skipped} error result; the gate now
 * lives in {@code OrcaAgentExecutor#toolRunner}, the single callback every dispatch shape invokes per tool.
 *
 * <p>
 * The existing {@code OrcaAgentExecutorInterruptTest} could not have caught the divergence: all five of its cases use
 * single-tool batches, where the head/tail checks are sufficient by construction.
 *
 * <p>
 * Two invariants must hold alongside the skip and are asserted throughout: the turn still reports
 * {@link CompletionReason#INTERRUPTED}, and every {@code tool_use} still receives exactly one {@code tool_result} —
 * an unpaired transcript is written to the store and then rejected by the provider on the NEXT turn.
 */
@DisplayName("OrcaAgentExecutor multi-tool batch interrupt")
class OrcaAgentExecutorBatchInterruptTest {

    @TempDir
    Path tempDir;

    private final InMemorySessionRecordStore repository = new InMemorySessionRecordStore();

    @Test
    @DisplayName("interrupt during tool 1 of 3 stops tools 2 and 3 — they are skipped, not executed")
    void interruptDuringFirstToolStopsTheRestOfTheBatch() {
        final AtomicReference<InterruptCoordinator> coordinatorRef = new AtomicReference<>();
        final TrippingTool tripping = new TrippingTool(
                () -> coordinatorRef.get().requestInterrupt(InterruptReason.USER_SIGINT));
        final RecordingTool recording = new RecordingTool();

        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(batch(ToolUse.of("tu-1", TrippingTool.TOOL_NAME, Map.of()),
                ToolUse.of("tu-2", RecordingTool.TOOL_NAME, Map.of()),
                ToolUse.of("tu-3", RecordingTool.TOOL_NAME, Map.of())));
        llmClient.enqueue(LlmResponse.text("unreachable"));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        executor.interruptCoordinatorFactory = () -> {
            final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            coordinatorRef.set(coordinator);
            return coordinator;
        };

        final OrcaAgentExecutionResult result = executor.execute(createContext(registryOf(tripping, recording)),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        // Tool 1 tripped the signal; neither of the tools behind it in the batch was allowed to start.
        assertThat(recording.invocations.get()).as("every not-yet-started tool_use in the batch is skipped").isZero();
        // Skipped is not the same as absent: each one is answered with an explicit interrupted-result so the model can
        // tell "cancelled before it ran" from "ran and failed" if the session is resumed.
        assertThat(resultContentFor(result.getSnapshot(), "tu-2"))
                .isEqualTo(OrcaAgentExecutor.INTERRUPTED_TOOL_SKIP_MESSAGE);
        assertThat(resultContentFor(result.getSnapshot(), "tu-3"))
                .isEqualTo(OrcaAgentExecutor.INTERRUPTED_TOOL_SKIP_MESSAGE);
        // The error flag, not just the message, is what the provider converters forward (isError=true on the
        // Anthropic/OpenAI tool_result blocks). A skip reported as a SUCCESS would tell the model the cancelled call
        // completed, and would persist that lie in the transcript.
        assertThat(resultFor(result.getSnapshot(), "tu-2").isError())
                .as("a skipped tool_use is reported to the model as an error, not a success").isTrue();
        assertThat(resultFor(result.getSnapshot(), "tu-3").isError()).isTrue();

        assertThat(result.getCompletionReason()).as("the turn is classified as interrupted")
                .isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(llmClient.callCount).as("no further LLM call is issued after the trip").isEqualTo(1);
        assertToolUsesArePaired(result.getSnapshot());
    }

    @Test
    @DisplayName("every tool_use in an interrupted batch still gets a tool_result, so the transcript stays resumable")
    void interruptedBatchLeavesAProviderValidTranscript() {
        final AtomicReference<InterruptCoordinator> coordinatorRef = new AtomicReference<>();
        final TrippingTool tripping = new TrippingTool(
                () -> coordinatorRef.get().requestInterrupt(InterruptReason.USER_SIGINT));
        final RecordingTool recording = new RecordingTool();

        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(batch(ToolUse.of("tu-a", RecordingTool.TOOL_NAME, Map.of()),
                ToolUse.of("tu-b", TrippingTool.TOOL_NAME, Map.of()),
                ToolUse.of("tu-c", RecordingTool.TOOL_NAME, Map.of())));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        executor.interruptCoordinatorFactory = () -> {
            final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            coordinatorRef.set(coordinator);
            return coordinator;
        };

        final SessionId sessionId = SessionId.generate();
        final OrcaAgentExecutionResult result = executor.execute(createContext(registryOf(tripping, recording)),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(sessionId).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        // Both the in-memory result and what was persisted by the loop's finally must be well-formed — an unpaired
        // tool_use is written to the store and rejected by the provider on the NEXT turn, not this one.
        assertToolUsesArePaired(result.getSnapshot());
        assertToolUsesArePaired(SessionSnapshot.from(
                repository.load(sessionId).orElseThrow(() -> new AssertionError("conversation was never persisted"))));

        // The trip lands mid-batch, so the split is positional: what ran before it keeps its real result, only what
        // had not started is skipped.
        assertThat(recording.invocations.get()).as("tu-a had already started and is not rolled back").isEqualTo(1);
        assertThat(resultContentFor(result.getSnapshot(), "tu-a"))
                .isNotEqualTo(OrcaAgentExecutor.INTERRUPTED_TOOL_SKIP_MESSAGE);
        assertThat(resultContentFor(result.getSnapshot(), "tu-c"))
                .isEqualTo(OrcaAgentExecutor.INTERRUPTED_TOOL_SKIP_MESSAGE);
        // Pin the boundary by error-ness too, so the split cannot regress into "everything succeeded" or
        // "everything failed": the tool that ran keeps its real success, only the skipped one is an error.
        assertThat(resultFor(result.getSnapshot(), "tu-a").isError())
                .as("a tool that already ran keeps its real, non-error result").isFalse();
        assertThat(resultFor(result.getSnapshot(), "tu-c").isError())
                .as("the tool that never started is reported as an error").isTrue();
    }

    @Test
    @DisplayName("a trip landing between two iterations DOES stop the next batch — the tail check is the only guard")
    void interruptBetweenIterationsStopsTheFollowingBatch() {
        final AtomicReference<InterruptCoordinator> coordinatorRef = new AtomicReference<>();
        final TrippingTool tripping = new TrippingTool(
                () -> coordinatorRef.get().requestInterrupt(InterruptReason.USER_SIGINT));
        final RecordingTool recording = new RecordingTool();

        final SequencedLlmClient llmClient = new SequencedLlmClient();
        // Iteration 1: a single tool that trips. Iteration 2 (never reached) would have run a 3-tool batch.
        llmClient.enqueue(batch(ToolUse.of("tu-1", TrippingTool.TOOL_NAME, Map.of())));
        llmClient.enqueue(batch(ToolUse.of("tu-2", RecordingTool.TOOL_NAME, Map.of()),
                ToolUse.of("tu-3", RecordingTool.TOOL_NAME, Map.of())));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        executor.interruptCoordinatorFactory = () -> {
            final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
            coordinatorRef.set(coordinator);
            return coordinator;
        };

        final OrcaAgentExecutionResult result = executor.execute(createContext(registryOf(tripping, recording)),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        // The iteration-boundary guard, unchanged by the mid-batch gate: a batch that never started produces no
        // tool_use at all, so there is nothing to skip or to pair.
        assertThat(recording.invocations.get()).as("the next iteration's batch never starts").isZero();
        assertToolUsesArePaired(result.getSnapshot());
    }

    // ============================== helpers ==============================

    /** Returns the {@code tool_result} content recorded for the given {@code tool_use} id. */
    private static String resultContentFor(SessionSnapshot snapshot, String toolUseId) {
        return resultFor(snapshot, toolUseId).getContent();
    }

    /**
     * Returns the whole {@code tool_result} recorded for the given {@code tool_use} id. Tests assert on
     * {@link ToolUseResult#isError()} as well as the content: the error flag is a separate wire-level field that the
     * provider converters map onto the tool_result block, so content-only assertions would not catch a skip that
     * regressed into a success.
     */
    private static ToolUseResult resultFor(SessionSnapshot snapshot, String toolUseId) {
        return snapshot.getConversationHistory().stream().flatMap(m -> m.getToolUseResults().stream())
                .filter(r -> toolUseId.equals(r.getToolUseId())).findFirst()
                .orElseThrow(() -> new AssertionError("no tool_result for " + toolUseId));
    }

    /**
     * Asserts that the snapshot pairs every assistant {@code tool_use} with exactly one {@code tool_result}. Kept in
     * sync with the identical helper in {@code OrcaAgentExecutorTerminalSessionStateTest}.
     */
    private static void assertToolUsesArePaired(SessionSnapshot snapshot) {
        final List<String> useIds = snapshot.getConversationHistory().stream().flatMap(m -> m.getToolUses().stream())
                .map(ToolUse::getId).toList();
        final List<String> resultIds = snapshot.getConversationHistory().stream()
                .flatMap(m -> m.getToolUseResults().stream()).map(ToolUseResult::getToolUseId).toList();

        assertThat(useIds).as("tool_use ids must be unique").doesNotHaveDuplicates();
        assertThat(resultIds).as("tool_result ids must be unique").doesNotHaveDuplicates();
        assertThat(resultIds)
                .as("every assistant tool_use must be answered by exactly one tool_result — an unpaired "
                        + "conversation is rejected by the provider on the next turn")
                .containsExactlyInAnyOrderElementsOf(useIds);
    }

    private static LlmResponse batch(ToolUse... toolUses) {
        return LlmResponse.of("act", List.of(toolUses), TokenUsage.of(5, 5, 10));
    }

    private static DefaultToolRegistry registryOf(AbstractTool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (AbstractTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
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

    /** Counts how many times it was invoked — the probe for "did the batch keep going after the trip?". */
    private static final class RecordingTool extends AbstractTool {

        static final String TOOL_NAME = "Recording";

        private final AtomicInteger invocations = new AtomicInteger();

        RecordingTool() {
            super(TOOL_NAME, "Records that it ran", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            // NON_INTERRUPTIBLE is the unmitigated tier: unlike THREAD_INTERRUPT tools (which the registrar aborts
            // because the coordinator is already tripped), nothing can stop these once dispatch reaches them. Write
            // and Edit sit in exactly this tier.
            return InterruptBehavior.NON_INTERRUPTIBLE;
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            invocations.incrementAndGet();
            return ToolResult.success("recorded");
        }
    }

    /** Succeeds, but trips the turn's interrupt coordinator from inside {@code execute()}. */
    private static final class TrippingTool extends AbstractTool {

        static final String TOOL_NAME = "Tripping";

        private final Runnable sideEffect;

        TrippingTool(Runnable sideEffect) {
            super(TOOL_NAME, "Trips the interrupt coordinator", Map.of("type", "object", "properties", Map.of()));
            this.sideEffect = sideEffect;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.COOPERATIVE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            sideEffect.run();
            return ToolResult.success("tripped");
        }
    }

    /** Scripted client that also counts calls, so tests can prove the loop stopped issuing them. */
    private static final class SequencedLlmClient implements LlmClient {

        private final List<LlmResponse> responses = new ArrayList<>();
        private int callCount;

        void enqueue(LlmResponse response) {
            responses.add(response);
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
            if (!responses.isEmpty()) {
                return responses.remove(0);
            }
            return LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10));
        }

        @Override
        public String getProviderName() {
            return "Sequenced";
        }

    }
}
