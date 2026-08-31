package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("OrcaAgentExecutor LlmCallMetadata propagation")
class OrcaAgentExecutorMetadataTest {

    @TempDir
    Path tempDir;

    private CapturingLlmClient llmClient;
    private OrcaAgentExecutor executor;
    private DefaultToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        llmClient = new CapturingLlmClient(LlmResponse.text("done"));
        toolRegistry = new DefaultToolRegistry();
        executor = createExecutor(llmClient);
    }

    @Test
    @DisplayName("auto-derive: caller 가 metadata 를 안 주면 agent name + react-loop + sessionId 가 채워진다")
    void autoDerivesDefaultsWhenCallerProvidesNone() {
        final SessionId sessionId = SessionId.generate();
        executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(sessionId).build());

        assertThat(llmClient.captured).hasSize(1);
        final LlmCallMetadata md = llmClient.captured.get(0);
        assertThat(md.getComponent()).contains("TestAgent");
        assertThat(md.getFeature()).contains("react-loop");
        assertThat(md.getTraceId()).contains(sessionId.value());
    }

    @Test
    @DisplayName("override: caller 가 명시한 필드는 보존되고 미설정 필드만 자동 보강된다")
    void preservesCallerSuppliedFieldsAndFillsTheRest() {
        final LlmCallMetadata caller = LlmCallMetadata.builder().feature("custom-flow").traceId("external-trace-99")
                .tag("tenant", "acme").build();

        executor.execute(createContext(), OrcaAgentExecutionRequest.builder().userInput("hi")
                .sessionId(SessionId.generate()).llmCallMetadata(caller).build());

        assertThat(llmClient.captured).hasSize(1);
        final LlmCallMetadata md = llmClient.captured.get(0);
        // caller fields preserved
        assertThat(md.getFeature()).contains("custom-flow");
        assertThat(md.getTraceId()).contains("external-trace-99");
        assertThat(md.getTags()).containsEntry("tenant", "acme");
        // unset field auto-filled
        assertThat(md.getComponent()).contains("TestAgent");
    }

    @Test
    @DisplayName("ToolContext 에 effective metadata 가 노출되어 sub-execution 진입점이 읽을 수 있다")
    void exposesEffectiveMetadataInToolContext() {
        final AtomicReference<LlmCallMetadata> seenByTool = new AtomicReference<>();
        toolRegistry.register(new MetadataCaptureTool(seenByTool));

        // First response triggers the capture tool, second ends the loop.
        llmClient.responses.clear();
        llmClient.responses.add(LlmResponse.tools(
                List.of(at.aimon.core.llm.ToolUse.of("tu-1", MetadataCaptureTool.TOOL_NAME, java.util.Map.of()))));
        llmClient.responses.add(LlmResponse.text("done"));

        final LlmCallMetadata caller = LlmCallMetadata.builder().traceId("external-trace-42").build();
        executor.execute(createContext(), OrcaAgentExecutionRequest.builder().userInput("hi")
                .sessionId(SessionId.generate()).llmCallMetadata(caller).build());

        final LlmCallMetadata md = seenByTool.get();
        assertThat(md).isNotNull();
        assertThat(md.getTraceId()).contains("external-trace-42");
        assertThat(md.getComponent()).contains("TestAgent");
        assertThat(md.getFeature()).contains("react-loop");
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

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    private static final class CapturingLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private final List<LlmCallMetadata> captured = new ArrayList<>();
        private int idx;

        CapturingLlmClient(LlmResponse defaultResponse) {
            responses.add(defaultResponse);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            captured.add(metadata);
            final LlmResponse response = idx < responses.size()
                    ? responses.get(idx++)
                    : responses.get(responses.size() - 1);
            return response;
        }

        @Override
        public String getProviderName() {
            return "Capturing";
        }

    }

    private static final class MetadataCaptureTool extends AbstractTool {
        static final String TOOL_NAME = "MetadataCapture";

        private final AtomicReference<LlmCallMetadata> sink;

        MetadataCaptureTool(AtomicReference<LlmCallMetadata> sink) {
            super(TOOL_NAME, "captures llm call metadata from tool context", java.util.Map.of("type", "object",
                    "properties", java.util.Map.of(), "required", java.util.List.of()));
            this.sink = sink;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            context.get(ToolContextKeys.LLM_CALL_METADATA_KEY).ifPresent(sink::set);
            return ToolResult.success("captured");
        }
    }
}
