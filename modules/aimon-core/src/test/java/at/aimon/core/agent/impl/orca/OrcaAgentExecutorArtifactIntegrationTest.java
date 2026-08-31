package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.artifact.FileArtifact;
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
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("OrcaAgentExecutor Artifact Integration Tests")
class OrcaAgentExecutorArtifactIntegrationTest {

    @TempDir
    Path tempDir;

    private OrcaAgentExecutor executor;
    private DefaultToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        MockLlmClient llmClient = new MockLlmClient();

        DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(llmClient);
        DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(llmClient, toolManager,
                hookManager);

        executor = new OrcaAgentExecutor(llmClient, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);

        toolRegistry = new DefaultToolRegistry();
    }

    private OrcaAgentRuntime createContext(MockLlmClient llmClient) {
        LocalFileSystemConfig fsConfig = new LocalFileSystemConfig(tempDir.toString());
        LocalFileSystem fileSystem = new LocalFileSystem(fsConfig);
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

    @Nested
    @DisplayName("ArtifactCollector Injection")
    class ArtifactCollectorInjection {

        @Test
        @DisplayName("Tool should receive ArtifactCollector via ToolContext")
        void toolShouldReceiveArtifactCollectorViaToolContext() {
            AtomicReference<ToolContext> capturedContext = new AtomicReference<>();

            ContextCaptureTool captureTool = new ContextCaptureTool(capturedContext);
            toolRegistry.register(captureTool);

            // LLM: first call returns tool use, second call returns final answer
            List<LlmResponse> responses = new ArrayList<>();
            responses.add(LlmResponse.of("Let me check.", List.of(ToolUse.of("toolu_001", "capture_context", Map.of())),
                    TokenUsage.empty()));
            responses.add(LlmResponse.text("Done."));

            MockLlmClient llmClient = new MockLlmClient(responses);
            executor = createExecutor(llmClient);

            OrcaAgentRuntime context = createContext(llmClient);
            OrcaAgentExecutionRequest request = createRequest("test");

            executor.execute(context, request);

            assertThat(capturedContext.get()).isNotNull();
            assertThat(capturedContext.get().get(ToolContextKeys.ARTIFACT_COLLECTOR)).isPresent();
        }
    }

    @Nested
    @DisplayName("toolUseId Injection")
    class ToolUseIdInjection {

        @Test
        @DisplayName("Tool should receive current toolUseId via ToolContext")
        void toolShouldReceiveToolUseIdViaToolContext() {
            AtomicReference<ToolContext> capturedContext = new AtomicReference<>();

            ContextCaptureTool captureTool = new ContextCaptureTool(capturedContext);
            toolRegistry.register(captureTool);

            List<LlmResponse> responses = new ArrayList<>();
            responses.add(LlmResponse.of("Checking.", List.of(ToolUse.of("toolu_abc123", "capture_context", Map.of())),
                    TokenUsage.empty()));
            responses.add(LlmResponse.text("Done."));

            MockLlmClient llmClient = new MockLlmClient(responses);
            executor = createExecutor(llmClient);

            OrcaAgentRuntime context = createContext(llmClient);
            OrcaAgentExecutionRequest request = createRequest("test");

            executor.execute(context, request);

            assertThat(capturedContext.get()).isNotNull();
            assertThat(capturedContext.get().get(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY)).hasValue("toolu_abc123");
        }

        @Test
        @DisplayName("Each tool call should receive its own toolUseId")
        void eachToolCallShouldReceiveItsOwnToolUseId() {
            List<String> capturedToolUseIds = new ArrayList<>();

            ToolUseIdCaptureTool captureTool = new ToolUseIdCaptureTool(capturedToolUseIds);
            toolRegistry.register(captureTool);

            // LLM returns two tool uses in a single response, then final answer
            List<LlmResponse> responses = new ArrayList<>();
            responses.add(
                    LlmResponse.of("Running tools.", List.of(ToolUse.of("toolu_first", "capture_tool_use_id", Map.of()),
                            ToolUse.of("toolu_second", "capture_tool_use_id", Map.of())), TokenUsage.empty()));
            responses.add(LlmResponse.text("Done."));

            MockLlmClient llmClient = new MockLlmClient(responses);
            executor = createExecutor(llmClient);

            OrcaAgentRuntime context = createContext(llmClient);
            OrcaAgentExecutionRequest request = createRequest("test");

            executor.execute(context, request);

            assertThat(capturedToolUseIds).containsExactly("toolu_first", "toolu_second");
        }
    }

    @Nested
    @DisplayName("Artifacts in Execution Result")
    class ArtifactsInResult {

        @Test
        @DisplayName("Success result should include artifacts collected during execution")
        void successResultShouldIncludeArtifacts() {
            ArtifactRegisteringTool artifactTool = new ArtifactRegisteringTool("/reports/sales.csv", "sales.csv", 1024);
            toolRegistry.register(artifactTool);

            List<LlmResponse> responses = new ArrayList<>();
            responses.add(LlmResponse.of("Generating report.",
                    List.of(ToolUse.of("toolu_write1", "register_artifact", Map.of())), TokenUsage.empty()));
            responses.add(LlmResponse.text("Report generated."));

            MockLlmClient llmClient = new MockLlmClient(responses);
            executor = createExecutor(llmClient);

            OrcaAgentRuntime context = createContext(llmClient);
            OrcaAgentExecutionRequest request = createRequest("generate report");

            OrcaAgentExecutionResult result = executor.execute(context, request);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getArtifacts()).hasSize(1);
            assertThat(result.getArtifacts().get(0).getPath()).isEqualTo("/reports/sales.csv");
            assertThat(result.getArtifacts().get(0).getFileName()).isEqualTo("sales.csv");
        }

        @Test
        @DisplayName("Success result with no artifacts should return empty list")
        void successResultWithNoArtifactsShouldReturnEmptyList() {
            List<LlmResponse> responses = new ArrayList<>();
            responses.add(LlmResponse.text("Hello!"));

            MockLlmClient llmClient = new MockLlmClient(responses);
            executor = createExecutor(llmClient);

            OrcaAgentRuntime context = createContext(llmClient);
            OrcaAgentExecutionRequest request = createRequest("hello");

            OrcaAgentExecutionResult result = executor.execute(context, request);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Failure result should include artifacts generated before failure")
        void failureResultShouldIncludeArtifactsGeneratedBeforeFailure() {
            // First tool registers an artifact, then LLM throws error
            ArtifactRegisteringTool artifactTool = new ArtifactRegisteringTool("/partial/output.csv", "output.csv",
                    512);
            toolRegistry.register(artifactTool);

            List<LlmResponse> responses = new ArrayList<>();
            // First: tool call that registers artifact
            responses.add(LlmResponse.of("Writing file.",
                    List.of(ToolUse.of("toolu_partial", "register_artifact", Map.of())), TokenUsage.empty()));
            // Second: LLM throws exception (simulating failure)
            // We use a special response list that throws on second access

            MockLlmClient llmClient = new FailingMockLlmClient(responses, 1);
            executor = createExecutor(llmClient);

            OrcaAgentRuntime context = createContext(llmClient);
            OrcaAgentExecutionRequest request = createRequest("generate and fail");

            OrcaAgentExecutionResult result = executor.execute(context, request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getArtifacts()).hasSize(1);
            assertThat(result.getArtifacts().get(0).getPath()).isEqualTo("/partial/output.csv");
        }

        @Test
        @DisplayName("Multiple artifacts from multiple tool calls should all be collected")
        void multipleArtifactsShouldBeCollected() {
            ArtifactRegisteringTool artifactTool = new ArtifactRegisteringTool("/reports/sales.csv", "sales.csv", 1024);
            ArtifactRegisteringTool2 artifactTool2 = new ArtifactRegisteringTool2("/reports/summary.pdf", "summary.pdf",
                    2048);
            toolRegistry.register(artifactTool);
            toolRegistry.register(artifactTool2);

            List<LlmResponse> responses = new ArrayList<>();
            responses.add(
                    LlmResponse.of("Generating files.", List.of(ToolUse.of("toolu_w1", "register_artifact", Map.of()),
                            ToolUse.of("toolu_w2", "register_artifact_2", Map.of())), TokenUsage.empty()));
            responses.add(LlmResponse.text("Files generated."));

            MockLlmClient llmClient = new MockLlmClient(responses);
            executor = createExecutor(llmClient);

            OrcaAgentRuntime context = createContext(llmClient);
            OrcaAgentExecutionRequest request = createRequest("generate files");

            OrcaAgentExecutionResult result = executor.execute(context, request);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getArtifacts()).hasSize(2);
            assertThat(result.getArtifacts()).extracting(FileArtifact::getFileName).containsExactly("sales.csv",
                    "summary.pdf");
        }
    }

    // --- Helper methods ---

    private static OrcaAgentExecutionRequest createRequest(String userInput) {
        return OrcaAgentExecutionRequest.builder().userInput(userInput).sessionId(SessionId.generate()).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient llmClient) {
        DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(llmClient);
        DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(llmClient, toolManager,
                hookManager);

        return new OrcaAgentExecutor(llmClient, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    // --- Mock LLM Client ---

    private static class MockLlmClient implements LlmClient {
        private final List<LlmResponse> responses;
        private int callIndex = 0;

        MockLlmClient() {
            this.responses = List.of(LlmResponse.text("Mock response"));
        }

        MockLlmClient(List<LlmResponse> responses) {
            this.responses = responses;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel model) {
            if (callIndex < responses.size()) {
                return responses.get(callIndex++);
            }
            return LlmResponse.text("Default mock response");
        }

        @Override
        public String getProviderName() {
            return "Mock";
        }

    }

    /**
     * Mock LLM client that throws an exception on a specific call index.
     */
    private static class FailingMockLlmClient extends MockLlmClient {
        private final int failAtIndex;
        private int callCount = 0;

        FailingMockLlmClient(List<LlmResponse> responses, int failAtIndex) {
            super(responses);
            this.failAtIndex = failAtIndex;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel model) {
            if (callCount == failAtIndex) {
                callCount++;
                throw new RuntimeException("Simulated LLM failure");
            }
            callCount++;
            return super.sendMessage(systemPrompt, messages, tools, model);
        }
    }

    // --- Test Tools ---

    /**
     * Tool that captures the ToolContext for verification.
     */
    private static class ContextCaptureTool extends AbstractTool {
        private final AtomicReference<ToolContext> capturedContext;

        ContextCaptureTool(AtomicReference<ToolContext> capturedContext) {
            super("capture_context", "Captures tool context for testing",
                    Map.of("type", "object", "properties", Map.of()));
            this.capturedContext = capturedContext;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            capturedContext.set(context);
            return ToolResult.success("Context captured");
        }
    }

    /**
     * Tool that captures the toolUseId from each invocation.
     */
    private static class ToolUseIdCaptureTool extends AbstractTool {
        private final List<String> capturedIds;

        ToolUseIdCaptureTool(List<String> capturedIds) {
            super("capture_tool_use_id", "Captures toolUseId for testing",
                    Map.of("type", "object", "properties", Map.of()));
            this.capturedIds = capturedIds;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            context.get(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY).ifPresent(capturedIds::add);
            return ToolResult.success("ID captured");
        }
    }

    /**
     * Tool that registers a FileArtifact via ArtifactCollector.
     */
    private static class ArtifactRegisteringTool extends AbstractTool {
        private final String path;
        private final String fileName;
        private final long size;

        ArtifactRegisteringTool(String path, String fileName, long size) {
            super("register_artifact", "Registers a file artifact for testing",
                    Map.of("type", "object", "properties", Map.of()));
            this.path = path;
            this.fileName = fileName;
            this.size = size;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            context.get(ToolContextKeys.ARTIFACT_COLLECTOR).ifPresent(collector -> {
                collector.add(FileArtifact.builder().path(path).fileName(fileName).size(size)
                        .toolUseId(context.get(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY).orElse(null)).build());
            });
            return ToolResult.success("Artifact registered: " + fileName);
        }
    }

    /**
     * Second artifact-registering tool (different name) for multi-artifact tests.
     */
    private static class ArtifactRegisteringTool2 extends AbstractTool {
        private final String path;
        private final String fileName;
        private final long size;

        ArtifactRegisteringTool2(String path, String fileName, long size) {
            super("register_artifact_2", "Registers a second file artifact for testing",
                    Map.of("type", "object", "properties", Map.of()));
            this.path = path;
            this.fileName = fileName;
            this.size = size;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            context.get(ToolContextKeys.ARTIFACT_COLLECTOR).ifPresent(collector -> {
                collector.add(FileArtifact.builder().path(path).fileName(fileName).size(size)
                        .toolUseId(context.get(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY).orElse(null)).build());
            });
            return ToolResult.success("Artifact registered: " + fileName);
        }
    }
}
