package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.context.ContextBlock;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
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
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

@DisplayName("OrcaAgentExecutor context assembly")
class OrcaAgentExecutorContextTest {

    private static final String SENTINEL_SYSTEM = "SENTINEL-SYSTEM-BODY-9a1f";
    private static final String SENTINEL_PREPEND = "SENTINEL-PREPEND-BODY-3c7e";
    private static final String SENTINEL_ATTACHMENT = "SENTINEL-ATTACHMENT-BODY-5b2d";

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Default (no assembler wired)")
    class DefaultNoop {

        @Test
        @DisplayName("no assembled context leaks into the system prompt or messages")
        void noContextByDefault() {
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            llmClient.enqueue(LlmResponse.of("final", List.of(), TokenUsage.of(10, 5, 15)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(llmClient.lastSystemPrompt).doesNotContain(SENTINEL_SYSTEM);
            assertThat(userMessageContents(llmClient.lastMessages)).noneMatch(c -> c.contains("system-reminder"));
        }
    }

    @Nested
    @DisplayName("With an assembler wired")
    class WithAssembler {

        @Test
        @DisplayName("SYSTEM block is folded into the system prompt")
        void systemBlockInSystemPrompt() {
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            llmClient.enqueue(LlmResponse.of("final", List.of(), TokenUsage.of(10, 5, 15)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            executor.contextAssembler = req -> List.of(ContextBlock.system("test-sys", SENTINEL_SYSTEM));

            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(llmClient.lastSystemPrompt).contains(SENTINEL_SYSTEM);
            // A SYSTEM block must not also appear as a synthetic user reminder.
            assertThat(userMessageContents(llmClient.lastMessages)).noneMatch(c -> c.contains(SENTINEL_SYSTEM));
        }

        @Test
        @DisplayName("USER_PREPEND block is injected as a synthetic system-reminder user message")
        void userPrependAsReminder() {
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            llmClient.enqueue(LlmResponse.of("final", List.of(), TokenUsage.of(10, 5, 15)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            executor.contextAssembler = req -> List.of(ContextBlock.userPrepend("test-up", SENTINEL_PREPEND));

            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(llmClient.lastSystemPrompt).doesNotContain(SENTINEL_PREPEND);
            assertThat(userMessageContents(llmClient.lastMessages))
                    .anyMatch(c -> c.contains(SENTINEL_PREPEND) && c.contains("system-reminder"));
        }

        @Test
        @DisplayName("ATTACHMENT block is injected as a synthetic system-reminder user message")
        void attachmentAsReminder() {
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            llmClient.enqueue(LlmResponse.of("final", List.of(), TokenUsage.of(10, 5, 15)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            executor.contextAssembler = req -> List.of(ContextBlock.attachment("test-att", SENTINEL_ATTACHMENT));

            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(userMessageContents(llmClient.lastMessages))
                    .anyMatch(c -> c.contains(SENTINEL_ATTACHMENT) && c.contains("system-reminder"));
        }

        @Test
        @DisplayName("a throwing assembler does not break the turn")
        void throwingAssemblerIsIsolated() {
            final CapturingLlmClient llmClient = new CapturingLlmClient();
            llmClient.enqueue(LlmResponse.of("final", List.of(), TokenUsage.of(10, 5, 15)));

            final OrcaAgentExecutor executor = createExecutor(llmClient);
            executor.contextAssembler = req -> {
                throw new IllegalStateException("assembler boom");
            };

            final OrcaAgentExecutionResult result = executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(userMessageContents(llmClient.lastMessages)).noneMatch(c -> c.contains("system-reminder"));
        }
    }

    private static List<String> userMessageContents(List<Message> messages) {
        final List<String> contents = new ArrayList<>();
        for (Message message : messages) {
            if (message.getRole() == Role.USER && message.getContent() != null) {
                contents.add(message.getContent());
            }
        }
        return contents;
    }

    private OrcaAgentRuntime createContext() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(10).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
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

    /** LLM client that records the system prompt and messages of the most recent call. */
    private static final class CapturingLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private String lastSystemPrompt = "";
        private List<Message> lastMessages = List.of();

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
            this.lastSystemPrompt = systemPrompt == null ? "" : systemPrompt;
            this.lastMessages = List.copyOf(messages);
            if (responses.isEmpty()) {
                return LlmResponse.text("unexpected-extra-call");
            }
            return responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Capturing";
        }

    }
}
