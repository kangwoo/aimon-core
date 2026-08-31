package at.aimon.core.agent.impl.orca;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Shared scaffolding for the {@code OrcaAgentExecutor} concurrency suite.
 *
 * <p>
 * The existing single-threaded tests each copy-paste a {@code createExecutor}/{@code createContext} pair plus an
 * {@code ArrayList}-backed scripted {@code LlmClient} that is <b>not</b> thread-safe ({@code responses.remove(0)} with
 * no
 * synchronization — see {@code OrcaAgentExecutorParallelToolTest.RecordingLlmClient}). Driving several
 * {@code execute()}
 * turns through <b>one shared executor</b> concurrently therefore needs a different foundation, which this class
 * provides:
 *
 * <ul>
 * <li>{@link ConcurrentScriptedLlmClient} — a thread-safe scripted client that routes each response by the
 * <b>session id</b>. The executor stamps {@code LlmCallMetadata.traceId = sessionId.value()}
 * ({@code OrcaAgentExecutor} default metadata) and forwards it to the 5-arg {@code sendMessage} overload, so distinct
 * concurrent sessions never race over a single shared response queue.</li>
 * <li>Reusable {@link ConcurrencyBehavior#CONCURRENT_SAFE} tools ({@link BarrierTool}, {@link EchoTool},
 * {@link GateTool}, {@link ConcurrencyProbeTool}) extracted from the parallel-tool test so every suite shares one
 * definition.</li>
 * <li>{@link #newExecutor}, {@link #newExecutorWithQueue}, {@link #newContext}, {@link #request} — the wiring helpers,
 * rooted at a caller-supplied temp directory so each test class owns its own {@code @TempDir}.</li>
 * </ul>
 *
 * <p>
 * An instance is cheap; construct one per test with the injected {@code @TempDir}. All members are package-private on
 * purpose — the concurrency tests live in this same package ({@code at.aimon.core.agent.impl.orca}) and rely on
 * package-private access to executor fields such as {@code parallelToolDispatcher} and
 * {@code interruptCoordinatorFactory}.
 */
final class OrcaAgentExecutorTestSupport {

    static final String DEFAULT_CONTEXT_ID = "agent:concurrency-1";

    private final LocalFileSystem fileSystem;

    OrcaAgentExecutorTestSupport(Path baseDir) {
        this.fileSystem = new LocalFileSystem(new LocalFileSystemConfig(Objects.requireNonNull(baseDir).toString()));
        this.fileSystem.initialize();
    }

    LocalFileSystem fileSystem() {
        return fileSystem;
    }

    // ---------- executor wiring ----------

    /** A plain executor with its own in-memory session manager and no mid-turn queue. */
    OrcaAgentExecutor newExecutor(LlmClient client) {
        return buildExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()), null);
    }

    /** An executor over a caller-supplied session manager (e.g. one wired with a flusher or spy repository). */
    OrcaAgentExecutor newExecutor(LlmClient client, TranscriptManager transcriptManager) {
        return buildExecutor(client, transcriptManager, null);
    }

    /** An executor wired for mid-turn injection through the given queue. */
    OrcaAgentExecutor newExecutorWithQueue(LlmClient client, MessageQueueManager queue) {
        return buildExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                Objects.requireNonNull(queue));
    }

    private OrcaAgentExecutor buildExecutor(LlmClient client, TranscriptManager transcriptManager,
            MessageQueueManager queue) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        if (queue == null) {
            return new OrcaAgentExecutor(client, transcriptManager, toolManager, hookManager, commandManager,
                    subagentManager);
        }
        return new OrcaAgentExecutorFactory().withMessageQueueManager(queue).create(client, transcriptManager,
                toolManager, hookManager, commandManager, subagentManager);
    }

    // ---------- context wiring ----------

    /** A context over the default context id, registering the given tools in a {@link DefaultToolRegistry}. */
    OrcaAgentRuntime newContext(Tool... tools) {
        return newContext(DEFAULT_CONTEXT_ID, tools);
    }

    /** A context over an explicit context id (use distinct ids to get distinct agent-scoped queues). */
    OrcaAgentRuntime newContext(String runtimeIdValue, Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return newContext(runtimeIdValue, registry, new DefaultHookRegistry());
    }

    OrcaAgentRuntime newContext(String runtimeIdValue, ToolRegistry toolRegistry, HookRegistry hookRegistry) {
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of(runtimeIdValue))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry)
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    static DefaultToolRegistry registryOf(Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    // ---------- request wiring ----------

    /** Opening delimiter of the session marker embedded in helper-built user input (see {@link #request}). */
    static final String CONVERSATION_MARKER_PREFIX = "<<conv:";

    /** Closing delimiter of the session marker; the suffix is what makes the marker collision-free. */
    static final String CONVERSATION_MARKER_SUFFIX = ">>";

    /**
     * Wraps a session id in delimiters so the {@link ConcurrentScriptedLlmClient} message-scan fallback matches a
     * <b>whole</b> id and never a bare substring — without the closing {@code >>}, {@code conv-1} would match inside
     * {@code conv-15}. The primary routing path is still the trace id (which the executor always stamps); this marker
     * only backs the fallback for calls that arrive without a routable trace id.
     */
    static String sessionMarker(String sessionId) {
        return CONVERSATION_MARKER_PREFIX + sessionId + CONVERSATION_MARKER_SUFFIX;
    }

    static OrcaAgentExecutionRequest request(SessionId sessionId) {
        return OrcaAgentExecutionRequest.builder().userInput("input for " + sessionMarker(sessionId.value()))
                .sessionId(sessionId).build();
    }

    static OrcaAgentExecutionRequest request(String sessionId) {
        return request(SessionId.of(sessionId));
    }

    // ================= thread-safe scripted client =================

    /** Invoked on every {@code sendMessage}, keyed by session id — a seam for barrier/latch rendezvous. */
    @FunctionalInterface
    interface CallListener {
        void onCall(String sessionId, List<Message> messages);
    }

    /**
     * A thread-safe {@link LlmClient} that scripts responses per session.
     *
     * <p>
     * Routing key is the {@link LlmCallMetadata#getTraceId() trace id}, which the executor defaults to
     * {@code sessionId.value()}. When metadata is absent (the legacy 4-arg overload) it falls back to scanning the
     * message list for a registered session marker — the {@link #request(SessionId) request helper} embeds
     * the
     * session id in the user input, so both routing paths resolve to the same key.
     *
     * <ul>
     * <li>{@link #script(String, LlmResponse...)} — an ordered per-session script consumed by an
     * {@link AtomicInteger} cursor.</li>
     * <li>{@link #repeat(String, LlmResponse)} — a stateless per-session response returned on every call (handy
     * when two concurrent turns share one session id and must both get a valid single-iteration turn).</li>
     * </ul>
     */
    static final class ConcurrentScriptedLlmClient implements LlmClient {

        private final ConcurrentMap<String, List<LlmResponse>> scripts = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AtomicInteger> cursors = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, LlmResponse> fallbacks = new ConcurrentHashMap<>();
        private final List<String> registeredKeys = new CopyOnWriteArrayList<>();

        private final AtomicInteger totalCalls = new AtomicInteger();
        private final ConcurrentMap<String, AtomicInteger> callsBySession = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, List<List<Message>>> messagesBySession = new ConcurrentHashMap<>();

        private volatile LlmResponse globalFallback = LlmResponse.text("done");
        private volatile CallListener onCall;

        /** Registers an ordered script for {@code sessionId}, consumed one response per call. */
        ConcurrentScriptedLlmClient script(String sessionId, LlmResponse... responses) {
            scripts.put(sessionId, List.of(responses));
            cursors.putIfAbsent(sessionId, new AtomicInteger());
            registerKey(sessionId);
            return this;
        }

        /** Registers a stateless response returned on every call for {@code sessionId}. */
        ConcurrentScriptedLlmClient repeat(String sessionId, LlmResponse response) {
            fallbacks.put(sessionId, response);
            registerKey(sessionId);
            return this;
        }

        /** Per-session fallback used once its ordered script is exhausted. */
        ConcurrentScriptedLlmClient fallback(String sessionId, LlmResponse response) {
            fallbacks.put(sessionId, response);
            registerKey(sessionId);
            return this;
        }

        ConcurrentScriptedLlmClient globalFallback(LlmResponse response) {
            this.globalFallback = Objects.requireNonNull(response);
            return this;
        }

        ConcurrentScriptedLlmClient onEachCall(CallListener listener) {
            this.onCall = listener;
            return this;
        }

        int totalCalls() {
            return totalCalls.get();
        }

        int callCount(String sessionId) {
            final AtomicInteger counter = callsBySession.get(sessionId);
            return counter == null ? 0 : counter.get();
        }

        List<List<Message>> messagesFor(String sessionId) {
            return messagesBySession.getOrDefault(sessionId, List.of());
        }

        List<Message> lastMessagesFor(String sessionId) {
            final List<List<Message>> all = messagesFor(sessionId);
            return all.isEmpty() ? List.of() : all.get(all.size() - 1);
        }

        private void registerKey(String sessionId) {
            if (!registeredKeys.contains(sessionId)) {
                registeredKeys.add(sessionId);
            }
        }

        private String route(List<Message> messages, LlmCallMetadata metadata) {
            final String trace = metadata.getTraceId().orElse(null);
            if (trace != null && registeredKeys.contains(trace)) {
                return trace;
            }
            // Fallback for calls that arrive without a routable trace id (e.g. the legacy 4-arg sendMessage overload).
            // Match on the delimiter-wrapped marker so a whole id is matched and never a bare substring — without the
            // wrapping, key "conv-1" would spuriously match inside "conv-15". Only helper-built requests embed it.
            for (String key : registeredKeys) {
                final String marker = sessionMarker(key);
                for (Message message : messages) {
                    final String content = message.getContent();
                    if (content != null && content.contains(marker)) {
                        return key;
                    }
                }
            }
            return trace != null ? trace : "<unrouted>";
        }

        private LlmResponse handle(List<Message> messages, LlmCallMetadata metadata) {
            final String sessionId = route(messages, metadata);
            totalCalls.incrementAndGet();
            callsBySession.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
            messagesBySession.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(List.copyOf(messages));

            final CallListener listener = onCall;
            if (listener != null) {
                listener.onCall(sessionId, messages);
            }

            final List<LlmResponse> script = scripts.get(sessionId);
            if (script != null) {
                final int index = cursors.get(sessionId).getAndIncrement();
                if (index < script.size()) {
                    return script.get(index);
                }
            }
            final LlmResponse fallback = fallbacks.get(sessionId);
            return fallback != null ? fallback : globalFallback;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return handle(messages, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return handle(messages, metadata);
        }

        @Override
        public String getProviderName() {
            return "ConcurrentScripted";
        }

    }

    // ================= reusable CONCURRENT_SAFE tools =================

    /**
     * A {@link ConcurrencyBehavior#CONCURRENT_SAFE} tool whose {@code execute} blocks on a shared
     * {@link CyclicBarrier};
     * it records whether it rendezvoused, which only happens if every sibling in the batch runs concurrently.
     */
    static final class BarrierTool extends AbstractTool {
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

    /** A {@link ConcurrencyBehavior#CONCURRENT_SAFE} no-op tool that echoes a stable per-name base output. */
    static final class EchoTool extends AbstractTool {
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

    /**
     * A {@link ConcurrencyBehavior#CONCURRENT_SAFE} tool that counts down an "entered" latch on entry then blocks on a
     * "release" latch — useful for pinning workers so a test can observe simultaneous in-flight execution.
     */
    static final class GateTool extends AbstractTool {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        GateTool(String name, CountDownLatch entered, CountDownLatch release) {
            super(name, "gate tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
            this.entered = entered;
            this.release = release;
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
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    return ToolResult.error("gate-not-released");
                }
                return ToolResult.success("ok");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            }
        }
    }

    /**
     * A {@link ConcurrencyBehavior#CONCURRENT_SAFE} tool that records the maximum number of instances executing
     * simultaneously via caller-supplied counters, sleeping briefly so overlap is reliably observed. Used to assert a
     * shared dispatcher pool's concurrency ceiling.
     */
    static final class ConcurrencyProbeTool extends AbstractTool {
        private final AtomicInteger active;
        private final AtomicInteger maxObserved;
        private final long holdMillis;

        ConcurrencyProbeTool(String name, AtomicInteger active, AtomicInteger maxObserved, long holdMillis) {
            super(name, "probe tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
            this.active = active;
            this.maxObserved = maxObserved;
            this.holdMillis = holdMillis;
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
            final int current = active.incrementAndGet();
            maxObserved.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(holdMillis);
                return ToolResult.success("ok");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            } finally {
                active.decrementAndGet();
            }
        }
    }

    // ---------- response helpers ----------

    /** Builds an assistant turn that calls a single tool. */
    static LlmResponse toolCall(String toolUseId, String toolName) {
        return LlmResponse.tools(List.of(ToolUse.of(toolUseId, toolName, Map.of())));
    }

    /** Builds a batch of {@code n} tool uses named {@code prefix0..prefix(n-1)} with ids {@code prefix-0..}. */
    static LlmResponse toolBatch(String prefix, int n) {
        final List<ToolUse> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(ToolUse.of(prefix + "-" + i, prefix + i, Map.of()));
        }
        return LlmResponse.tools(batch);
    }
}
