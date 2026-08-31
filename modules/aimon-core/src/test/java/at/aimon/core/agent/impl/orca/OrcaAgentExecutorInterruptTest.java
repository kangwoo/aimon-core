package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.TerminatorRegistrar;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.InterruptToolKeys;
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
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Behavioural interrupt test for {@link OrcaAgentExecutor}: verifies that each ReAct turn owns a fresh
 * {@link InterruptCoordinator}, that cooperative tools see a live {@link CancellationSignal} through
 * {@link ToolContext}, that THREAD_INTERRUPT/EXTERNALLY_TERMINATED tools receive a {@link TerminatorRegistrar},
 * and that a tripped signal short-circuits the loop with
 * {@link CompletionReason#INTERRUPTED} instead of issuing another LLM call.
 */
@DisplayName("OrcaAgentExecutor interrupt wiring")
class OrcaAgentExecutorInterruptTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("cooperative tool observes a non-Noop CancellationSignal via ToolContext")
    void cooperativeToolSeesLiveSignal() {
        final CapturingCooperativeTool tool = new CapturingCooperativeTool();
        final RecordingLlmClient llmClient = new RecordingLlmClient();
        llmClient.enqueue(LlmResponse.of("calling tool",
                List.of(ToolUse.of("tu-1", CapturingCooperativeTool.TOOL_NAME, Map.of())), TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(llmClient, tool);
        executor.execute(createContext(registryOf(tool)), request());

        assertThat(tool.observed).as("cooperative tool must see a live signal (not Noop)").isNotNull();
        assertThat(tool.observed.isCancelled()).as("signal must not be tripped at invocation time").isFalse();
    }

    @Test
    @DisplayName("trip fired during tool execution => next iteration exits with CompletionReason.INTERRUPTED")
    void trippedSignalShortCircuitsLoop() {
        final AtomicReference<InterruptCoordinator> coordinatorRef = new AtomicReference<>();

        final CapturingCooperativeTool tool = new CapturingCooperativeTool();
        final RecordingLlmClient llmClient = new RecordingLlmClient();
        // Two turns worth of responses — only the first should ever be consumed.
        llmClient.enqueue(LlmResponse.of("calling tool",
                List.of(ToolUse.of("tu-1", CapturingCooperativeTool.TOOL_NAME, Map.of())), TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("unreachable"));

        // Trip during the tool's execute() call so the iteration-tail check fires.
        tool.sideEffect = () -> coordinatorRef.get().requestInterrupt(InterruptReason.USER_SIGINT);

        final OrcaAgentExecutor executor = createExecutor(llmClient, tool);
        executor.interruptCoordinatorFactory = () -> {
            final InterruptCoordinator c = new DefaultInterruptCoordinator();
            coordinatorRef.set(c);
            return c;
        };

        final OrcaAgentExecutionResult result = executor.execute(createContext(registryOf(tool)), request());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(result.getErrorMessage()).isEqualTo("Execution interrupted");
        // The terminal LLM call was NOT issued — only the first request is on the wire.
        assertThat(llmClient.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("NON_INTERRUPTIBLE tool sees NO TerminatorRegistrar in its ToolContext")
    void nonInterruptibleToolReceivesNoRegistrar() {
        final CapturingCooperativeTool tool = new CapturingCooperativeTool();
        final RecordingLlmClient llmClient = new RecordingLlmClient();
        llmClient.enqueue(LlmResponse.of("calling tool",
                List.of(ToolUse.of("tu-1", CapturingCooperativeTool.TOOL_NAME, Map.of())), TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(llmClient, tool);
        executor.execute(createContext(registryOf(tool)), request());

        assertThat(tool.observedRegistrar).as("cooperative (non-interruptible tier) must NOT receive a registrar")
                .isNull();
    }

    @Test
    @DisplayName("THREAD_INTERRUPT tool receives a registrar that is closed on return")
    void threadInterruptToolReceivesRegistrarAndItIsClosed() {
        final ThreadInterruptTool tool = new ThreadInterruptTool();
        final RecordingLlmClient llmClient = new RecordingLlmClient();
        llmClient.enqueue(LlmResponse.of("calling tool",
                List.of(ToolUse.of("tu-1", ThreadInterruptTool.TOOL_NAME, Map.of())), TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(llmClient, tool);
        executor.execute(createContext(registryOf(tool)), request());

        assertThat(tool.observedRegistrar).as("THREAD_INTERRUPT tool must receive a registrar").isNotNull();
        // The registrar was closed in the finally block: attempting to register() after return throws
        // IllegalStateException per the TerminatorRegistrar contract.
        assertThat(tool.observedRegistrar).isNotNull();
        final TerminatorRegistrar captured = tool.observedRegistrar;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> captured.register(() -> {
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("pre-tripped signal before first iteration => INTERRUPTED without any LLM call")
    void preTrippedSignalShortCircuitsBeforeFirstCall() {
        final CapturingCooperativeTool tool = new CapturingCooperativeTool();
        final RecordingLlmClient llmClient = new RecordingLlmClient();
        // Enqueue one response just in case; it must remain unused.
        llmClient.enqueue(LlmResponse.text("unreachable"));

        final OrcaAgentExecutor executor = createExecutor(llmClient, tool);
        executor.interruptCoordinatorFactory = () -> {
            final InterruptCoordinator c = new DefaultInterruptCoordinator();
            c.requestInterrupt(InterruptReason.USER_SIGINT);
            return c;
        };

        final OrcaAgentExecutionResult result = executor.execute(createContext(registryOf(tool)), request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(llmClient.callCount).as("no LLM call must be issued when signal is pre-tripped").isZero();
    }

    // ---------- helpers ----------

    private DefaultToolRegistry registryOf(AbstractTool tool) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        return registry;
    }

    private OrcaAgentExecutionRequest request() {
        return OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build();
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-1"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient client, AbstractTool... ignored) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    /**
     * Cooperative tool that captures the {@link CancellationSignal} (always injected) and the
     * {@link TerminatorRegistrar} (only injected for THREAD_INTERRUPT/EXTERNALLY_TERMINATED). It optionally runs a
     * side-effect that tests use to trip the signal from inside the tool call.
     */
    private static final class CapturingCooperativeTool extends AbstractTool {
        static final String TOOL_NAME = "Coop";
        CancellationSignal observed;
        TerminatorRegistrar observedRegistrar;
        Runnable sideEffect;

        CapturingCooperativeTool() {
            super(TOOL_NAME, "cooperative tool",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.COOPERATIVE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            observed = context.get(InterruptToolKeys.CANCELLATION_SIGNAL).orElse(null);
            observedRegistrar = context.get(InterruptToolKeys.TERMINATOR_REGISTRAR).orElse(null);
            if (sideEffect != null) {
                sideEffect.run();
            }
            return ToolResult.success("ok");
        }
    }

    /**
     * THREAD_INTERRUPT tool that captures the registrar reference so tests can verify the executor closes it after
     * the call returns.
     */
    private static final class ThreadInterruptTool extends AbstractTool {
        static final String TOOL_NAME = "Thr";
        TerminatorRegistrar observedRegistrar;
        final AtomicBoolean interrupted = new AtomicBoolean();

        ThreadInterruptTool() {
            super(TOOL_NAME, "thread-interrupt tool",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.THREAD_INTERRUPT;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            observedRegistrar = context.get(InterruptToolKeys.TERMINATOR_REGISTRAR).orElse(null);
            interrupted.set(Thread.interrupted());
            return ToolResult.success("ok");
        }
    }

    /** Records every {@code sendMessage} call so tests can count and inspect LLM traffic. */
    private static final class RecordingLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        int callCount;

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        private LlmResponse record() {
            callCount++;
            if (responses.isEmpty()) {
                return LlmResponse.text("unexpected-extra-call");
            }
            return responses.remove(0);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return record();
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return record();
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }

    }
}
