package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
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
 * PAR-03 end-to-end wiring test: verifies that an {@link OrcaAgentExecutor} configured with an
 * {@link ToolConcurrencyConfig#isEnabled() enabled} {@link DefaultParallelToolDispatcher} actually runs a batch of
 * {@link ConcurrencyBehavior#CONCURRENT_SAFE} tools concurrently, preserves {@code ToolUseStarted} event order, and
 * completes the turn successfully. Complements the dispatcher's isolated unit tests by exercising the real executor →
 * {@code executeToolUses} → dispatcher → {@code executeSingleTool} path.
 *
 * <p>
 * Hook coverage: {@code executeSingleTool} owns Permission/Pre/PostTool hook invocation, so under parallel dispatch the
 * same hook instance is invoked concurrently from worker threads within a single turn. The hook tests here lock in that
 * contract — that Pre/PostTool hooks fire exactly once per tool, that they are genuinely invoked concurrently (proven
 * via a {@link CyclicBarrier} rendezvous inside the hook), and that a PostTool output rewrite is threaded back to the
 * correct tool's result with no cross-contamination when results complete out of order.
 */
@DisplayName("OrcaAgentExecutor parallel tool execution wiring (PAR-03)")
class OrcaAgentExecutorParallelToolTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("enabled config runs a CONCURRENT_SAFE batch in parallel, in input order, and completes")
    void enabledConfigParallelizesBatch() {
        final int n = 3;
        // Shared rendezvous: each tool blocks until all n are running. If the executor ran them sequentially the
        // first await() would time out (BrokenBarrier for the rest), so all-rendezvoused proves genuine concurrency.
        final CyclicBarrier barrier = new CyclicBarrier(n);
        final List<BarrierTool> tools = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tools.add(new BarrierTool("P" + i, barrier));
        }

        final RecordingLlmClient llmClient = new RecordingLlmClient();
        final List<ToolUse> batch = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            batch.add(ToolUse.of("tu-" + i, "P" + i, Map.of()));
        }
        llmClient.enqueue(LlmResponse.of("calling tools", batch, TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        executor.parallelToolDispatcher = new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(n));

        final List<String> startedIds = new CopyOnWriteArrayList<>();
        executor.addEventListener(event -> {
            if (event instanceof ToolUseStarted started) {
                startedIds.add(started.getToolUseId());
            }
        });

        try {
            final OrcaAgentExecutionResult result = executor.execute(createContext(registryOf(tools)), request());

            assertThat(result.isSuccess()).as("turn should complete successfully").isTrue();
            assertThat(tools).as("every tool must have reached the rendezvous => they ran concurrently")
                    .allMatch(t -> t.rendezvoused);
            assertThat(startedIds).as("ToolUseStarted events must preserve input order").containsExactly("tu-0", "tu-1",
                    "tu-2");
        } finally {
            executor.parallelToolDispatcher = DefaultParallelToolDispatcher.sequential();
        }
    }

    @Test
    @DisplayName("Pre/PostTool hooks fire concurrently, exactly once per tool, with thread-safe accounting")
    void hooksFireConcurrentlyOncePerTool() {
        final int n = 3;
        // A CyclicBarrier(n) can only be cleared if all n preTool hooks are blocked on it at the same instant — i.e.
        // they were invoked concurrently. If executeSingleTool ran sequentially the first await() would time out and
        // the rest would see a BrokenBarrier, leaving preRendezvoused < n and failing the assertion below.
        final CyclicBarrier hookBarrier = new CyclicBarrier(n);
        final AtomicInteger preInvocations = new AtomicInteger();
        final AtomicInteger postInvocations = new AtomicInteger();
        final AtomicInteger preRendezvoused = new AtomicInteger();
        final Set<String> preToolNames = ConcurrentHashMap.newKeySet();
        final Set<String> postToolNames = ConcurrentHashMap.newKeySet();

        final PreToolHook preHook = ctx -> {
            preInvocations.incrementAndGet();
            preToolNames.add(ctx.getCurrentToolUse().getName());
            try {
                hookBarrier.await(5, TimeUnit.SECONDS);
                preRendezvoused.incrementAndGet();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (TimeoutException | BrokenBarrierException ignored) {
                // Not concurrent: leave preRendezvoused short so the assertion surfaces the regression.
            }
            return HookResult.success();
        };
        final PostToolHook postHook = ctx -> {
            postInvocations.incrementAndGet();
            postToolNames.add(ctx.getToolUse().getName());
            return HookResult.success();
        };

        final DefaultHookRegistry hookRegistry = new DefaultHookRegistry();
        hookRegistry.register(HookEventType.PRE_TOOL, preHook);
        hookRegistry.register(HookEventType.POST_TOOL, postHook);

        final List<Tool> tools = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tools.add(new EchoTool("P" + i));
        }

        final RecordingLlmClient llmClient = new RecordingLlmClient();
        llmClient.enqueue(LlmResponse.of("calling tools", batchOf(n), TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        executor.parallelToolDispatcher = new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(n));

        try {
            final OrcaAgentExecutionResult result = executor.execute(createContext(registryOf(tools), hookRegistry),
                    request());

            assertThat(result.isSuccess()).as("turn should complete successfully").isTrue();
            assertThat(preInvocations).as("preTool hook fires exactly once per tool").hasValue(n);
            assertThat(postInvocations).as("postTool hook fires exactly once per tool").hasValue(n);
            assertThat(preRendezvoused).as("every preTool hook must clear the barrier => invoked concurrently")
                    .hasValue(n);
            assertThat(preToolNames).as("each tool is seen by preTool exactly once").containsExactlyInAnyOrder("P0",
                    "P1", "P2");
            assertThat(postToolNames).as("each tool is seen by postTool exactly once").containsExactlyInAnyOrder("P0",
                    "P1", "P2");
        } finally {
            executor.parallelToolDispatcher = DefaultParallelToolDispatcher.sequential();
        }
    }

    @Test
    @DisplayName("PostTool output rewrite is threaded back to the correct tool under parallel dispatch")
    void postToolOutputRewriteMapsToCorrectTool() {
        final int n = 3;
        // Each invocation rewrites the output with a marker derived from THAT tool's name. Under parallel dispatch the
        // tools complete out of order, so this guards against cross-contamination: tool Pi's result must carry Pi's
        // marker appended to Pi's own base output.
        final PostToolHook markerHook = ctx -> {
            final String toolName = ctx.getToolUse().getName();
            final String base = ctx.getCurrentToolUseResult().getContent();
            return HookResult.withUpdatedOutput(ToolResult.success(base + "::hooked-for-" + toolName));
        };

        final DefaultHookRegistry hookRegistry = new DefaultHookRegistry();
        hookRegistry.register(HookEventType.POST_TOOL, markerHook);

        final List<Tool> tools = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tools.add(new EchoTool("P" + i));
        }

        final RecordingLlmClient llmClient = new RecordingLlmClient();
        llmClient.enqueue(LlmResponse.of("calling tools", batchOf(n), TokenUsage.empty()));
        llmClient.enqueue(LlmResponse.text("done"));

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        executor.parallelToolDispatcher = new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(n));

        try {
            final OrcaAgentExecutionResult result = executor.execute(createContext(registryOf(tools), hookRegistry),
                    request());

            assertThat(result.isSuccess()).as("turn should complete successfully").isTrue();
            final Map<String, String> resultsByToolUseId = llmClient.lastToolResultsById();
            assertThat(resultsByToolUseId).as("every tool's result must reach the next LLM iteration").hasSize(n);
            assertThat(resultsByToolUseId.get("tu-0")).isEqualTo("base-P0::hooked-for-P0");
            assertThat(resultsByToolUseId.get("tu-1")).isEqualTo("base-P1::hooked-for-P1");
            assertThat(resultsByToolUseId.get("tu-2")).isEqualTo("base-P2::hooked-for-P2");
        } finally {
            executor.parallelToolDispatcher = DefaultParallelToolDispatcher.sequential();
        }
    }

    // ---------- helpers ----------

    /** Builds a batch of {@code n} tool uses named {@code P0..P(n-1)} with stable ids {@code tu-0..tu-(n-1)}. */
    private List<ToolUse> batchOf(int n) {
        final List<ToolUse> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(ToolUse.of("tu-" + i, "P" + i, Map.of()));
        }
        return batch;
    }

    private DefaultToolRegistry registryOf(List<? extends Tool> tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        tools.forEach(registry::register);
        return registry;
    }

    private OrcaAgentExecutionRequest request() {
        return OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build();
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry) {
        return createContext(toolRegistry, new DefaultHookRegistry());
    }

    private OrcaAgentRuntime createContext(DefaultToolRegistry toolRegistry, DefaultHookRegistry hookRegistry) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:par-1"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry)
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
     * CONCURRENT_SAFE tool whose {@code execute} blocks on a shared barrier; it records whether it rendezvoused (which
     * only happens if all sibling tools in the batch run concurrently).
     */
    private static final class BarrierTool extends AbstractTool {
        private final CyclicBarrier barrier;
        volatile boolean rendezvoused;

        BarrierTool(String name, CyclicBarrier barrier) {
            super(name, "barrier tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
            this.barrier = barrier;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.COOPERATIVE;
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                rendezvoused = true;
                return ToolResult.success("ok");
            } catch (TimeoutException | BrokenBarrierException e) {
                return ToolResult.error("no-rendezvous: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            }
        }
    }

    /** CONCURRENT_SAFE no-op tool that echoes a stable, per-name base output ({@code base-<name>}). */
    private static final class EchoTool extends AbstractTool {
        private final String name;

        EchoTool(String name) {
            super(name, "echo tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
            this.name = name;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.COOPERATIVE;
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("base-" + name);
        }
    }

    /** Minimal scripted LLM client: returns enqueued responses in order and captures the last tool-result batch. */
    private static final class RecordingLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private volatile Map<String, String> lastToolResultsById = Map.of();

        void enqueue(LlmResponse response) {
            responses.add(response);
        }

        /** @return tool-result content keyed by tool_use id, captured from the most recent call that carried any. */
        Map<String, String> lastToolResultsById() {
            return lastToolResultsById;
        }

        private LlmResponse record(List<Message> messages) {
            captureToolResults(messages);
            if (responses.isEmpty()) {
                return LlmResponse.text("unexpected-extra-call");
            }
            return responses.remove(0);
        }

        private void captureToolResults(List<Message> messages) {
            final Map<String, String> byId = new LinkedHashMap<>();
            for (Message message : messages) {
                if (message.hasToolResults()) {
                    message.getToolUseResults().forEach(result -> byId.put(result.getToolUseId(), result.getContent()));
                }
            }
            if (!byId.isEmpty()) {
                lastToolResultsById = byId;
            }
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
}
