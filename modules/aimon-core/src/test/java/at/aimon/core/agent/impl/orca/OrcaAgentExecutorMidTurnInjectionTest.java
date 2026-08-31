package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
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
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * CQ-03 behavioral test for {@link OrcaAgentExecutor}: verifies that queued user inputs are injected at each
 * iteration's tail, scoped to the current agent runtime, wrapped in a {@code <system-reminder>} block,
 * and placed after tool-result messages in session memory.
 */
@DisplayName("OrcaAgentExecutor mid-turn queue injection (CQ-03)")
class OrcaAgentExecutorMidTurnInjectionTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("queued NEXT input scoped to this context is injected between iterations, after tool-result message")
    void drainsQueuedMessageIntoSecondTurn() {
        final RecordingLlmClient llmClient = new RecordingLlmClient();
        // Turn 1: request a tool. Turn 2: no tools => final answer.
        llmClient.enqueue(LlmResponse.of("calling tool", List.of(ToolUse.of("tu-1", EchoTool.TOOL_NAME, Map.of())),
                TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("final answer"));

        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-1");
        final OrcaAgentRuntime context = createContext(agentRuntimeId, toolRegistryWithEcho());
        final OrcaAgentExecutor executor = createExecutor(llmClient, queueManager);

        // Enqueue a user message for THIS agent runtime BEFORE executing so it is drained at the first
        // iteration's tail and injected into the conversation history sent to the LLM on turn 2.
        queueManager.enqueue(QueuedInput.builder().agentRuntimeId(agentRuntimeId)
                .inputText("please also check the logs").priority(QueuedInputPriority.NEXT).build());

        final OrcaAgentExecutionResult result = executor.execute(context,
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("final answer");

        // The LLM was called twice; inspect the message list that was on the wire for turn 2.
        assertThat(llmClient.messagesPerCall).hasSize(2);
        final List<Message> turn2Messages = llmClient.messagesPerCall.get(1);

        // Locate the tool-result message and the injected user message.
        final int toolResultIndex = indexOfFirstRole(turn2Messages, Role.TOOL);
        assertThat(toolResultIndex).isGreaterThanOrEqualTo(0);

        final int injectedIndex = indexOfFirstUserMessageContaining(turn2Messages, toolResultIndex + 1,
                "<system-reminder key=\"user-mid-turn-message\">");
        assertThat(injectedIndex).as("injected message must appear AFTER the tool-result message")
                .isGreaterThan(toolResultIndex);

        // The injected message is last on the wire for turn 2 — nothing else follows it.
        assertThat(injectedIndex).isEqualTo(turn2Messages.size() - 1);

        final Message injected = turn2Messages.get(injectedIndex);
        assertThat(injected.getRole()).isEqualTo(Role.USER);
        assertThat(injected.getContent()).contains("<system-reminder key=\"user-mid-turn-message\">");
        assertThat(injected.getContent()).contains("please also check the logs");
        assertThat(injected.getContent()).contains("</system-reminder>");
    }

    @Test
    @DisplayName("empty queue => second turn identical to baseline (no injected reminder)")
    void emptyQueueMatchesBaseline() {
        final RecordingLlmClient llmClient = new RecordingLlmClient();
        llmClient.enqueue(LlmResponse.of("calling tool", List.of(ToolUse.of("tu-1", EchoTool.TOOL_NAME, Map.of())),
                TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("final answer"));

        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-2");
        final OrcaAgentRuntime context = createContext(agentRuntimeId, toolRegistryWithEcho());
        final OrcaAgentExecutor executor = createExecutor(llmClient, queueManager);

        executor.execute(context,
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(llmClient.messagesPerCall).hasSize(2);
        final List<Message> turn2Messages = llmClient.messagesPerCall.get(1);
        for (Message msg : turn2Messages) {
            assertThat(msg.getContent()).doesNotContain("<system-reminder key=\"user-mid-turn-message\">");
        }
    }

    @Test
    @DisplayName("message queued for a DIFFERENT agent runtime is NOT drained by this agent")
    void foreignContextEntryIsNotDrained() {
        final RecordingLlmClient llmClient = new RecordingLlmClient();
        llmClient.enqueue(LlmResponse.of("calling tool", List.of(ToolUse.of("tu-1", EchoTool.TOOL_NAME, Map.of())),
                TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("final answer"));

        final MessageQueueManager queueManager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());

        final AgentRuntimeId myRuntimeId = AgentRuntimeId.of("agent:test-3");
        final AgentRuntimeId otherRuntimeId = AgentRuntimeId.of("agent:test-4");

        // Queue a message scoped to a DIFFERENT context (e.g., a sub-agent or parallel session).
        queueManager.enqueue(QueuedInput.builder().agentRuntimeId(otherRuntimeId).inputText("for somebody else")
                .priority(QueuedInputPriority.NEXT).build());

        final OrcaAgentRuntime context = createContext(myRuntimeId, toolRegistryWithEcho());
        final OrcaAgentExecutor executor = createExecutor(llmClient, queueManager);

        executor.execute(context,
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        // Foreign entry is still on the queue.
        assertThat(queueManager.snapshot()).hasSize(1);
        assertThat(queueManager.snapshot().get(0).getAgentRuntimeId()).isEqualTo(otherRuntimeId);

        // And no injection landed in turn 2.
        final List<Message> turn2Messages = llmClient.messagesPerCall.get(1);
        for (Message msg : turn2Messages) {
            assertThat(msg.getContent()).doesNotContain("<system-reminder key=\"user-mid-turn-message\">");
        }
    }

    @Test
    @DisplayName("null MessageQueueManager => legacy behavior (no injection attempt)")
    void nullQueueManagerPreservesLegacyBehavior() {
        final RecordingLlmClient llmClient = new RecordingLlmClient();
        llmClient.enqueue(LlmResponse.of("calling tool", List.of(ToolUse.of("tu-1", EchoTool.TOOL_NAME, Map.of())),
                TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("final answer"));

        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of("agent:test-5");
        final OrcaAgentRuntime context = createContext(agentRuntimeId, toolRegistryWithEcho());
        final OrcaAgentExecutor executor = createExecutor(llmClient, null);

        final OrcaAgentExecutionResult result = executor.execute(context,
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isTrue();
        final List<Message> turn2Messages = llmClient.messagesPerCall.get(1);
        for (Message msg : turn2Messages) {
            assertThat(msg.getContent()).doesNotContain("<system-reminder key=\"user-mid-turn-message\">");
        }
    }

    private static int indexOfFirstRole(List<Message> messages, Role role) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() == role) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfFirstUserMessageContaining(List<Message> messages, int startInclusive, String needle) {
        for (int i = startInclusive; i < messages.size(); i++) {
            final Message msg = messages.get(i);
            if (msg.getRole() == Role.USER && msg.getContent().contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private DefaultToolRegistry toolRegistryWithEcho() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new EchoTool());
        return registry;
    }

    private OrcaAgentRuntime createContext(AgentRuntimeId agentRuntimeId, DefaultToolRegistry toolRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(agentRuntimeId)
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
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

    /**
     * Records every {@code sendMessage} call so tests can inspect the exact message list the executor built for each
     * turn. Uses a separate {@link ArrayList} per call because {@link OrcaAgentExecutor} may hand us a live view of
     * {@code TranscriptBuffer}.
     */
    private static final class RecordingLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        final List<List<Message>> messagesPerCall = new ArrayList<>();

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        private LlmResponse record(List<Message> messages) {
            messagesPerCall.add(new ArrayList<>(messages));
            if (responses.isEmpty()) {
                return LlmResponse.text("unexpected-extra-call");
            }
            return responses.remove(0);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return record(messages);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return record(messages);
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }

    }

    /** Trivial tool used to keep the ReAct loop alive for a second turn so injection can land. */
    private static final class EchoTool extends AbstractTool {
        static final String TOOL_NAME = "Echo";

        EchoTool() {
            super(TOOL_NAME, "echo tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("ok");
        }
    }
}
