package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentEnvironmentSnapshot;
import at.aimon.core.agent.AgentEnvironmentSnapshotProvider;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecord;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
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
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Verifies CTX-06 synthetic {@code messages[0]} user-context injection behavior in {@link OrcaAgentExecutor}.
 */
@DisplayName("OrcaAgentExecutor user-context injection (CTX-06)")
class OrcaAgentExecutorUserContextInjectionTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-23T12:34:56Z");

    @TempDir
    Path tempDir;

    private CapturingLlmClient llmClient;
    private InMemorySessionRecordStore repository;
    private DefaultToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        llmClient = new CapturingLlmClient();
        repository = new InMemorySessionRecordStore();
        toolRegistry = new DefaultToolRegistry();
    }

    @Test
    @DisplayName("fresh conversation with provider: synthetic user-context message precedes the real user input")
    void freshConversationInjectsSyntheticBlock() {
        final OrcaAgentExecutor executor = createExecutor(llmClient, repository, fixedProvider("/workspace/proj"));

        executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("Hello").sessionId(SessionId.generate()).build());

        assertThat(llmClient.capturedMessages).hasSize(1);
        final List<Message> firstCall = llmClient.capturedMessages.get(0);

        // First call should contain two USER messages in order: synthetic context, then the real user input.
        assertThat(firstCall).hasSize(2);
        assertThat(firstCall.get(0).getRole()).isEqualTo(Role.USER);
        assertThat(firstCall.get(0).getContent()).contains("<system-reminder key=\"working-directory\">")
                .contains("/workspace/proj").contains("<system-reminder key=\"current-date\">")
                .contains("2026-04-23T12:34:56Z");

        assertThat(firstCall.get(1).getRole()).isEqualTo(Role.USER);
        assertThat(firstCall.get(1).getContent()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("userContextInjection(false) opts out: only the real user message is sent")
    void optOutSkipsInjection() {
        final OrcaAgentExecutor executor = createExecutor(llmClient, repository, fixedProvider("/workspace/proj"));

        executor.execute(createContext(), OrcaAgentExecutionRequest.builder().userInput("Hello")
                .sessionId(SessionId.generate()).userContextInjection(false).build());

        assertThat(llmClient.capturedMessages).hasSize(1);
        final List<Message> firstCall = llmClient.capturedMessages.get(0);
        assertThat(firstCall).hasSize(1);
        assertThat(firstCall.get(0).getRole()).isEqualTo(Role.USER);
        assertThat(firstCall.get(0).getContent()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("resumed conversation (existing user messages): injection is skipped")
    void resumedConversationSkipsInjection() {
        final SessionId existingId = SessionId.generate();
        // Pre-populate the repository with a session that already carries a user message — mirrors a "resumed"
        // turn where memory is non-empty at initialize-time.
        final SessionRecord persisted = new SessionRecord(existingId, "prior system prompt",
                List.of(Message.user("Earlier question"), Message.assistant("Earlier answer")));
        repository.save(persisted);

        final OrcaAgentExecutor executor = createExecutor(llmClient, repository, fixedProvider("/workspace/proj"));

        executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("Follow-up").sessionId(existingId).build());

        assertThat(llmClient.capturedMessages).hasSize(1);
        final List<Message> firstCall = llmClient.capturedMessages.get(0);
        // Previous user + assistant + new user: no synthetic block injected.
        assertThat(firstCall).hasSize(3);
        assertThat(firstCall.get(0).getContent()).isEqualTo("Earlier question");
        assertThat(firstCall.get(1).getContent()).isEqualTo("Earlier answer");
        assertThat(firstCall.get(2).getContent()).isEqualTo("Follow-up");
        assertThat(firstCall).allSatisfy(m -> assertThat(m.getContent()).doesNotContain("<system-reminder"));
    }

    @Test
    @DisplayName("no provider configured: injection is skipped even on fresh conversations")
    void noProviderSkipsInjection() {
        // Note: deliberately passing null as the provider here.
        final OrcaAgentExecutor executor = createExecutor(llmClient, repository, null);

        executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("Hello").sessionId(SessionId.generate()).build());

        assertThat(llmClient.capturedMessages).hasSize(1);
        final List<Message> firstCall = llmClient.capturedMessages.get(0);
        assertThat(firstCall).hasSize(1);
        assertThat(firstCall.get(0).getContent()).isEqualTo("Hello");
    }

    private OrcaAgentRuntime createContext() {
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

    private OrcaAgentExecutor createExecutor(LlmClient client, InMemorySessionRecordStore repo,
            AgentEnvironmentSnapshotProvider agentEnvironmentSnapshotProvider) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutorFactory().withAgentEnvironmentSnapshotProvider(agentEnvironmentSnapshotProvider)
                .create(client, new DefaultTranscriptManager(repo), toolManager, hookManager, commandManager,
                        subagentManager);
    }

    private static AgentEnvironmentSnapshotProvider fixedProvider(String workingDirectory) {
        final AgentEnvironmentSnapshot snapshot = AgentEnvironmentSnapshot.builder().workingDirectory(workingDirectory)
                .currentDate(FIXED_INSTANT).environment(Environment.createDefault()).build();
        return new AgentEnvironmentSnapshotProvider() {
            @Override
            public AgentEnvironmentSnapshot get(AgentRuntime context) {
                return snapshot;
            }

            @Override
            public void invalidate(AgentRuntimeId id) {
                // no-op for tests
            }
        };
    }

    /**
     * Captures the {@code messages} argument on each LLM call so tests can assert what the executor actually sent.
     * Always returns a single text response to terminate the ReAct loop after one iteration.
     */
    private static final class CapturingLlmClient implements LlmClient {
        private final List<List<Message>> capturedMessages = new ArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            capturedMessages.add(List.copyOf(messages));
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "Capturing";
        }

    }

}
