package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
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
 * Death-spiral guard: verifies both the pure {@link OrcaAgentExecutor#isStalledIteration(List)} predicate and
 * the end-to-end abort behaviour once {@link OrcaAgentExecutor#MAX_CONSECUTIVE_STALLED_ITERATIONS} consecutive
 * all-error tool iterations land back-to-back.
 */
@DisplayName("OrcaAgentExecutor stalled-iteration (death-spiral) guard")
class OrcaAgentExecutorStalledIterationTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("isStalledIteration predicate")
    class IsStalledIterationPredicate {

        @Test
        @DisplayName("empty tool-use result list is never stalled")
        void emptyResultsAreNotStalled() {
            assertThat(OrcaAgentExecutor.isStalledIteration(List.of())).isFalse();
        }

        @Test
        @DisplayName("all-error results are stalled")
        void allErrorResultsAreStalled() {
            final List<ToolUseResult> results = List.of(ToolUseResult.error("t1", "boom"),
                    ToolUseResult.error("t2", "boom"));

            assertThat(OrcaAgentExecutor.isStalledIteration(results)).isTrue();
        }

        @Test
        @DisplayName("a mix of success and error results is not stalled")
        void mixedResultsAreNotStalled() {
            final List<ToolUseResult> results = List.of(ToolUseResult.success("t1", "ok"),
                    ToolUseResult.error("t2", "boom"));

            assertThat(OrcaAgentExecutor.isStalledIteration(results)).isFalse();
        }

        @Test
        @DisplayName("all-success results are not stalled")
        void allSuccessResultsAreNotStalled() {
            final List<ToolUseResult> results = List.of(ToolUseResult.success("t1", "ok"),
                    ToolUseResult.success("t2", "ok"));

            assertThat(OrcaAgentExecutor.isStalledIteration(results)).isFalse();
        }
    }

    @Nested
    @DisplayName("end-to-end guard behaviour")
    class EndToEndGuard {

        @Test
        @DisplayName("3 consecutive all-error tool iterations abort with CompletionReason.ERROR")
        void threeConsecutiveFailingIterationsTripTheGuard() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-1", "FailingTool", Map.of())),
                    TokenUsage.of(5, 5, 10)));
            llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-2", "FailingTool", Map.of())),
                    TokenUsage.of(5, 5, 10)));
            llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-3", "FailingTool", Map.of())),
                    TokenUsage.of(5, 5, 10)));
            // Safety net - the loop must abort at the 3rd iteration, before this is ever consumed.
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new FailingTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.ERROR);
            assertThat(result.getErrorMessage()).contains("consecutive");
            assertThat(result.getErrorMessage()).contains("no progress");
            assertThat(result.getMetadata().getIterationCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a succeeding iteration resets the consecutive-stall counter, so 2 failures alone do not trip"
                + " the guard")
        void aSucceedingIterationResetsTheCounter() {
            final SequencedLlmClient llmClient = new SequencedLlmClient();
            llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-1", "FailingTool", Map.of())),
                    TokenUsage.of(5, 5, 10)));
            llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-2", "FailingTool", Map.of())),
                    TokenUsage.of(5, 5, 10)));
            llmClient.enqueue(
                    LlmResponse.of("act", List.of(ToolUse.of("tu-3", "NoopTool", Map.of())), TokenUsage.of(5, 5, 10)));
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new FailingTool());
            toolRegistry.register(new NoopTool());

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getFinalAnswer()).isEqualTo("done");
        }

        @Test
        @DisplayName("A cancellation landing on the would-be third stalled iteration finalises as INTERRUPTED")
        void aCancelledIterationIsNotCountedAsStalled() {
            final AtomicReference<InterruptCoordinator> coordinatorRef = new AtomicReference<>();

            final SequencedLlmClient llmClient = new SequencedLlmClient();
            // Two genuine stalled iterations first, so the counter sits at 2 and the next all-error iteration is the
            // one that would trip MAX_CONSECUTIVE_STALLED_ITERATIONS.
            llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-1", FailingTool.TOOL_NAME, Map.of())),
                    TokenUsage.of(5, 5, 10)));
            llmClient.enqueue(LlmResponse.of("act", List.of(ToolUse.of("tu-2", FailingTool.TOOL_NAME, Map.of())),
                    TokenUsage.of(5, 5, 10)));
            llmClient.enqueue(LlmResponse.of("act",
                    List.of(ToolUse.of("tu-3", TrippingFailingTool.TOOL_NAME, Map.of())), TokenUsage.of(5, 5, 10)));
            // Safety net — the loop must exit at the 3rd iteration, before this is ever consumed.
            llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

            final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
            toolRegistry.register(new FailingTool());
            toolRegistry.register(new TrippingFailingTool(coordinatorRef));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            executor.interruptCoordinatorFactory = () -> {
                final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
                coordinatorRef.set(coordinator);
                return coordinator;
            };

            final OrcaAgentExecutionResult result = executor.execute(createContext(toolRegistry),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            // Once the signal is tripped, every not-yet-started tool_use is short-circuited to an error result, so an
            // interrupted iteration is indistinguishable from a stalled one to isStalledIteration. Counting it here
            // would make the loop report a cancellation the user asked for as a death spiral the agent fell into — the
            // wrong
            // reason, the wrong error message, and a misleading transcript. The guard must read the signal first.
            assertThat(result.getCompletionReason())
                    .as("a cancelled iteration is a cancellation, not the third strike of the death-spiral guard")
                    .isEqualTo(CompletionReason.INTERRUPTED);
            assertThat(result.getErrorMessage()).isEqualTo("Execution interrupted");
            assertThat(result.getErrorMessage()).doesNotContain("no progress");
            assertThat(llmClient.callCount).as("no further LLM call is issued once the signal is tripped").isEqualTo(3);
        }
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
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
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    /**
     * Minimal LLM client that returns pre-queued responses in sequence. Supports an optional delay applied once per
     * response, allowing wall-clock budget scenarios to exercise the elapsed-time check without relying on real LLM
     * latency.
     */
    private static final class SequencedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private Duration delayBeforeResponse = Duration.ZERO;
        private int callCount;

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        void setDelayBeforeResponse(Duration delay) {
            this.delayBeforeResponse = delay;
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
            if (!delayBeforeResponse.isZero()) {
                try {
                    Thread.sleep(delayBeforeResponse.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (responses.isEmpty()) {
                return LlmResponse.text("unexpected-extra-call");
            }
            return responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Sequenced";
        }

    }

    /** Tool that always fails, used to drive consecutive stalled iterations. */
    private static final class FailingTool extends AbstractTool {
        static final String TOOL_NAME = "FailingTool";

        FailingTool() {
            super(TOOL_NAME, "always-failing tool for death-spiral guard tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.error("boom");
        }
    }

    /**
     * Fails like {@link FailingTool} but trips the turn's {@link CancellationSignal} on the way out — the shape of a
     * cooperative tool that observed a SIGINT, or of any tool whose failure was itself caused by the cancellation. The
     * iteration it belongs to therefore looks all-error <em>and</em> cancelled at the same moment, which is exactly the
     * ambiguity the guard has to resolve in favour of the cancellation.
     */
    private static final class TrippingFailingTool extends AbstractTool {
        static final String TOOL_NAME = "TrippingFailingTool";

        private final AtomicReference<InterruptCoordinator> coordinatorRef;

        TrippingFailingTool(AtomicReference<InterruptCoordinator> coordinatorRef) {
            super(TOOL_NAME, "failing tool that trips the cancellation signal",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
            this.coordinatorRef = coordinatorRef;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.COOPERATIVE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            coordinatorRef.get().requestInterrupt(InterruptReason.USER_SIGINT);
            return ToolResult.error("boom");
        }
    }

    /** Minimal tool used to demonstrate that a successful iteration resets the consecutive-stall counter. */
    private static final class NoopTool extends AbstractTool {
        static final String TOOL_NAME = "NoopTool";

        NoopTool() {
            super(TOOL_NAME, "no-op tool for death-spiral guard tests",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("noop");
        }
    }
}
