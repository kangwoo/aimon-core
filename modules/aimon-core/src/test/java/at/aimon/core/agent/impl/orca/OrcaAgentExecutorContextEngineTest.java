package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.NoOpCompactionGuard;
import at.aimon.core.agent.context.ContextDecision;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.ContextView;
import at.aimon.core.agent.context.DefaultContextEngine;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.base.Principal;
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
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Pins the main ReAct loop to its {@link ContextEngine}: what the provider is sent is the engine's view, not the
 * transcript buffer, on the first attempt and on the prompt-too-long retry alike.
 */
class OrcaAgentExecutorContextEngineTest {

    private static final Message VIEW_MARKER = Message.user("[the engine's view]");
    private static final Message RECOVERED_MARKER = Message.user("[the recovered view]");

    @TempDir
    Path tempDir;

    @Test
    void theProviderIsSentTheEnginesViewRatherThanTheTranscript() {
        final RecordingClient client = new RecordingClient(0);
        final ViewSubstitutingEngine engine = new ViewSubstitutingEngine(Optional.empty());

        final OrcaAgentExecutionResult result = createExecutor(client).execute(createRuntime(engine),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate())
                        .principal(Principal.user("u-1")).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.sent).containsExactly(List.of(VIEW_MARKER));
        final ContextRequest seen = engine.lastRequest;
        assertThat(seen.getTranscriptBuffer().getMessages()).extracting(Message::getContent).contains("hi");
        assertThat(seen.isBudgetForced()).isFalse();
        assertThat(seen.getCaller().getExecutionId()).as("a session's turn identifies by its session").isEmpty();
        assertThat(seen.getCaller().getPrincipal()).hasValue(Principal.user("u-1"));
    }

    @Test
    void thePromptTooLongRetryIsSentTheRecoveredView() {
        final RecordingClient client = new RecordingClient(1);
        final ViewSubstitutingEngine engine = new ViewSubstitutingEngine(
                Optional.of(ContextView.of(List.of(RECOVERED_MARKER))));

        final OrcaAgentExecutionResult result = createExecutor(client).execute(createRuntime(engine),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.sent).containsExactly(List.of(VIEW_MARKER), List.of(RECOVERED_MARKER));
        assertThat(engine.recoverCalls.get()).isEqualTo(1);
    }

    @Test
    void anEngineThatCannotRecoverLetsThePromptTooLongErrorEndTheTurn() {
        final RecordingClient client = new RecordingClient(1);
        final ViewSubstitutingEngine engine = new ViewSubstitutingEngine(Optional.empty());

        final OrcaAgentExecutionResult result = createExecutor(client).execute(createRuntime(engine),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("context exceeded");
        assertThat(client.sent).hasSize(1);
        assertThat(engine.recoverCalls.get()).isEqualTo(1);
    }

    @Test
    void aBlockingDecisionEndsTheTurnWithoutCallingTheProvider() {
        final RecordingClient client = new RecordingClient(0);
        final ContextEngine blocking = new ViewSubstitutingEngine(Optional.empty()) {
            @Override
            public ContextDecision prepare(ContextRequest request) {
                return ContextDecision.builder().view(ContextView.of(List.of())).action(CompactionDecision.Action.BLOCK)
                        .reason("over the limit").estimatedTokens(1200).blockingLimit(1000).build();
            }
        };

        final OrcaAgentExecutionResult result = createExecutor(client).execute(createRuntime(blocking),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("over the limit");
        assertThat(client.sent).isEmpty();
    }

    @Test
    void aRollingFallbackWarningIsRecordedWithTheCompactionEvents() {
        final RecordingClient client = new RecordingClient(0);
        final Instant now = Instant.now();
        final CompactionMetadata fallback = CompactionMetadata.builder().trigger(CompactionTrigger.AUTO)
                .kind(CompactionKind.FALLBACK).preCompactTokenCount(700).startedAt(now).completedAt(now).build();
        final ContextEngine warning = new ViewSubstitutingEngine(Optional.empty()) {
            @Override
            public ContextDecision prepare(ContextRequest request) {
                return ContextDecision.builder().view(ContextView.of(List.of(VIEW_MARKER)))
                        .action(CompactionDecision.Action.WARN).reason("cannot bring the view down")
                        .compactionMetadata(fallback).build();
            }
        };

        final OrcaAgentExecutionResult result = createExecutor(client).execute(createRuntime(warning),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompactionEvents()).containsExactly(fallback);
    }

    @Test
    void anyOtherWarningRecordsNothing() {
        final RecordingClient client = new RecordingClient(0);
        final Instant now = Instant.now();
        final CompactionMetadata notAFallback = CompactionMetadata.builder().trigger(CompactionTrigger.AUTO)
                .kind(CompactionKind.ROLLING).startedAt(now).completedAt(now).build();
        final ContextEngine warning = new ViewSubstitutingEngine(Optional.empty()) {
            @Override
            public ContextDecision prepare(ContextRequest request) {
                return ContextDecision.builder().view(ContextView.of(List.of(VIEW_MARKER)))
                        .action(CompactionDecision.Action.WARN).reason("warning band").compactionMetadata(notAFallback)
                        .build();
            }
        };

        final OrcaAgentExecutionResult result = createExecutor(client).execute(createRuntime(warning),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.getCompactionEvents()).isEmpty();
    }

    @Test
    void aRuntimeConfiguredOnlyWithAGuardGetsADefaultEngineOverIt() {
        final CompactionGuard guard = NoOpCompactionGuard.instance();
        final OrcaAgentRuntime runtime = runtimeBuilder().compactionGuard(guard).build();

        assertThat(runtime.getContextEngine()).isInstanceOf(DefaultContextEngine.class);
        assertThat(((DefaultContextEngine) runtime.getContextEngine()).getCompactionGuard()).isSameAs(guard);
    }

    @Test
    void anExplicitEngineWinsOverTheDerivedOne() {
        final ContextEngine engine = ContextEngine.passthrough();

        assertThat(runtimeBuilder().compactionGuard(NoOpCompactionGuard.instance()).contextEngine(engine).build()
                .getContextEngine()).isSameAs(engine);
    }

    private OrcaAgentRuntime createRuntime(ContextEngine engine) {
        return runtimeBuilder().contextEngine(engine).build();
    }

    private OrcaAgentRuntime.Builder runtimeBuilder() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault());
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

    /** Sends a fixed view on every call and answers recovery with the configured view. */
    private static class ViewSubstitutingEngine implements ContextEngine {
        final AtomicInteger recoverCalls = new AtomicInteger();
        volatile ContextRequest lastRequest;
        private final Optional<ContextView> recovered;

        ViewSubstitutingEngine(Optional<ContextView> recovered) {
            this.recovered = recovered;
        }

        @Override
        public ContextDecision prepare(ContextRequest request) {
            lastRequest = request;
            return ContextDecision.none(ContextView.of(List.of(VIEW_MARKER)));
        }

        @Override
        public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
            recoverCalls.incrementAndGet();
            return recovered;
        }

        @Override
        public CompactionResult compactNow(ContextRequest request, String instructions) {
            throw new AssertionError("not called");
        }
    }

    /** Records every message list it is sent; rejects the first {@code failures} calls as prompt-too-long. */
    private static final class RecordingClient implements LlmClient {
        final List<List<Message>> sent = new CopyOnWriteArrayList<>();
        private final int failures;

        RecordingClient(int failures) {
            this.failures = failures;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            sent.add(List.copyOf(messages));
            if (sent.size() <= failures) {
                throw new LlmPromptTooLongException("context exceeded", 10000, 8192);
            }
            return LlmResponse.of("done", List.of(), TokenUsage.of(10, 5, 15));
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }
    }
}
