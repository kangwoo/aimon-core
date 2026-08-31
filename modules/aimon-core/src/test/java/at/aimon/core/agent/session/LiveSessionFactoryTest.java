package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.DefaultAgentRegistry;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
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
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Verifies SESSION-02 {@link LiveSessionFactory} behaviour: agent resolution via {@link DefaultAgentRegistry},
 * {@code contextBuilder} delegation, and argument / constructor validation.
 */
@DisplayName("LiveSessionFactory (SESSION-02)")
class LiveSessionFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("open() resolves the agent, invokes ContextBuilder, and returns a usable LiveSession")
    void openResolvesAgentAndReturnsSession() {
        final DefaultAgentRegistry registry = new DefaultAgentRegistry();
        final Agent expectedAgent = DefaultAgent.builder().name("my-agent").maxIterations(5)
                .systemPrompt("You are my-agent").build();
        registry.register(expectedAgent);

        final StubLlmClient llmClient = new StubLlmClient();
        llmClient.enqueue(LlmResponse.of("hello-response", List.of(), TokenUsage.of(1, 1, 2)));
        final OrcaAgentExecutor executor = createExecutor(llmClient);

        // Capture the Agent passed to the builder to verify registry resolution.
        final List<Agent> capturedAgents = new ArrayList<>();
        final LiveSessionFactory.ContextBuilder builder = agent -> {
            capturedAgents.add(agent);
            return createContext(agent);
        };

        final LiveSessionFactory factory = new LiveSessionFactory(registry, builder, executor);

        final SessionId id = SessionId.of("factory-open");
        try (LiveSession session = factory.open(id, "my-agent", LiveSessionOptions.defaults())) {
            assertThat(session).isInstanceOf(DefaultLiveSession.class);
            assertThat(session.getSessionId()).isEqualTo(id);
            assertThat(capturedAgents).singleElement().isEqualTo(expectedAgent);

            // Verify the session is usable end-to-end.
            final var result = session.submit("hi");
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Test
    @DisplayName("open() with an unknown agentRef throws IllegalArgumentException")
    void unknownAgentRefThrows() {
        final DefaultAgentRegistry registry = new DefaultAgentRegistry();
        final LiveSessionFactory factory = new LiveSessionFactory(registry, agent -> createContext(agent),
                createExecutor(new StubLlmClient()));

        assertThatThrownBy(() -> factory.open(SessionId.generate(), "does-not-exist", LiveSessionOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does-not-exist");
    }

    @Test
    @DisplayName("open() with null arguments throws NullPointerException")
    void nullArgumentsRejected() {
        final DefaultAgentRegistry registry = new DefaultAgentRegistry();
        registry.register(DefaultAgent.builder().name("any").maxIterations(1).systemPrompt("x").build());
        final LiveSessionFactory factory = new LiveSessionFactory(registry, agent -> createContext(agent),
                createExecutor(new StubLlmClient()));

        assertThatThrownBy(() -> factory.open(null, "any", LiveSessionOptions.defaults()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> factory.open(SessionId.generate(), null, LiveSessionOptions.defaults()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> factory.open(SessionId.generate(), "any", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor rejects null collaborators")
    void constructorNullsRejected() {
        final DefaultAgentRegistry registry = new DefaultAgentRegistry();
        final LiveSessionFactory.ContextBuilder builder = agent -> createContext(agent);
        final OrcaAgentExecutor executor = createExecutor(new StubLlmClient());

        assertThatThrownBy(() -> new LiveSessionFactory(null, builder, executor))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LiveSessionFactory(registry, null, executor))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LiveSessionFactory(registry, builder, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Two sessions for the same agent share the same agent-scoped AgentRuntime")
    void twoSessionsShareSameContextForSameAgent() {
        final DefaultAgentRegistry registry = new DefaultAgentRegistry();
        final Agent agent = DefaultAgent.builder().name("shared-agent").maxIterations(1).systemPrompt("x").build();
        registry.register(agent);

        // Memoize one context per agent — same instance returned on every ContextBuilder call. This emulates
        // OrcaAgentRuntimeManager.getOrCreateRuntime semantics (one AEC per (Agent, discriminator)).
        final java.util.Map<Agent, OrcaAgentRuntime> cache = new java.util.HashMap<>();
        final java.util.List<OrcaAgentRuntime> resolved = new ArrayList<>();
        final LiveSessionFactory.ContextBuilder builder = a -> {
            OrcaAgentRuntime ctx = cache.computeIfAbsent(a, this::createContext);
            resolved.add(ctx);
            return ctx;
        };

        final LiveSessionFactory factory = new LiveSessionFactory(registry, builder,
                createExecutor(new StubLlmClient()));

        final LiveSession s1 = factory.open(SessionId.of("c1"), "shared-agent", LiveSessionOptions.defaults());
        final LiveSession s2 = factory.open(SessionId.of("c2"), "shared-agent", LiveSessionOptions.defaults());

        try {
            // The factory invoked the ContextBuilder twice but received the same memoized AEC both times,
            // confirming that two concurrent sessions targeting the same agent share one agent-scoped AEC.
            assertThat(resolved).hasSize(2);
            assertThat(resolved.get(0)).isSameAs(resolved.get(1));
        } finally {
            s1.close();
            s2.close();
        }
    }

    @Test
    @DisplayName("After s1.close() the s2 session and its agent-scoped AEC remain usable")
    void afterCloseS1_S2AndContextRemainUsable() {
        final DefaultAgentRegistry registry = new DefaultAgentRegistry();
        final Agent agent = DefaultAgent.builder().name("persistent-agent").maxIterations(1).systemPrompt("x").build();
        registry.register(agent);

        final java.util.Map<Agent, OrcaAgentRuntime> cache = new java.util.HashMap<>();
        final LiveSessionFactory.ContextBuilder builder = a -> cache.computeIfAbsent(a, this::createContext);

        final StubLlmClient llm = new StubLlmClient();
        llm.enqueue(LlmResponse.of("s1-response", List.of(), TokenUsage.of(1, 1, 2)));
        llm.enqueue(LlmResponse.of("s2-response", List.of(), TokenUsage.of(1, 1, 2)));

        final LiveSessionFactory factory = new LiveSessionFactory(registry, builder, createExecutor(llm));

        final LiveSession s1 = factory.open(SessionId.of("c-a"), "persistent-agent", LiveSessionOptions.defaults());
        final LiveSession s2 = factory.open(SessionId.of("c-b"), "persistent-agent", LiveSessionOptions.defaults());

        // Close s1 first — this must NOT invalidate the agent-scoped context shared with s2.
        s1.close();

        // s2 must still be able to submit a turn, demonstrating the AEC was not torn down by s1.close().
        try {
            final var result = s2.submit("hello");
            assertThat(result.isSuccess()).isTrue();
            // Context registries / filesystem are still wired and accessible from the cached instance.
            final OrcaAgentRuntime ctx = cache.get(agent);
            assertThat(ctx).isNotNull();
            assertThat(ctx.getFileSystem()).isNotNull();
            assertThat(ctx.getToolRegistry()).isNotNull();
        } finally {
            s2.close();
        }
    }

    @Test
    @DisplayName("Two sessions for the same agent share the same context instance (Phase 1 verification #1)")
    void twoSessionsForSameAgentShareSameContextInstance() {
        final DefaultAgentRegistry registry = new DefaultAgentRegistry();
        final Agent agent = DefaultAgent.builder().name("phase1-share").maxIterations(1).systemPrompt("x").build();
        registry.register(agent);

        // Memoize one context per agent — emulating OrcaAgentRuntimeManager.getOrCreateRuntime semantics.
        final java.util.Map<Agent, OrcaAgentRuntime> cache = new java.util.HashMap<>();
        final List<OrcaAgentRuntime> resolved = new ArrayList<>();
        final LiveSessionFactory.ContextBuilder builder = a -> {
            final OrcaAgentRuntime ctx = cache.computeIfAbsent(a, this::createContext);
            resolved.add(ctx);
            return ctx;
        };

        final LiveSessionFactory factory = new LiveSessionFactory(registry, builder,
                createExecutor(new StubLlmClient()));

        try (LiveSession s1 = factory.open(SessionId.of("c-1"), "phase1-share", LiveSessionOptions.defaults());
                LiveSession s2 = factory.open(SessionId.of("c-2"), "phase1-share", LiveSessionOptions.defaults())) {
            // The factory invoked ContextBuilder twice but received the same memoized AEC for both sessions —
            // i.e., s1 and s2 share the exact same agent-scoped context instance.
            assertThat(resolved).hasSize(2);
            assertThat(resolved.get(0)).isSameAs(resolved.get(1));
        }
    }

    @Test
    @DisplayName("Closing the first session leaves the second session's context usable (Phase 1 verification #2)")
    void closingFirstSessionLeavesSecondAgentEnvironmentSnapshotUsable() {
        final DefaultAgentRegistry registry = new DefaultAgentRegistry();
        final Agent agent = DefaultAgent.builder().name("phase1-survives").maxIterations(1).systemPrompt("x").build();
        registry.register(agent);

        final java.util.Map<Agent, OrcaAgentRuntime> cache = new java.util.HashMap<>();
        final LiveSessionFactory.ContextBuilder builder = a -> cache.computeIfAbsent(a, this::createContext);

        final StubLlmClient llm = new StubLlmClient();
        llm.enqueue(LlmResponse.of("turn-after-s1-close", List.of(), TokenUsage.of(1, 1, 2)));

        final LiveSessionFactory factory = new LiveSessionFactory(registry, builder, createExecutor(llm));

        final LiveSession s1 = factory.open(SessionId.of("c-survive-1"), "phase1-survives",
                LiveSessionOptions.defaults());
        final LiveSession s2 = factory.open(SessionId.of("c-survive-2"), "phase1-survives",
                LiveSessionOptions.defaults());

        // Close s1 — agent-scoped AEC must remain alive for s2.
        s1.close();

        try {
            // Tool registry on the shared AEC is still wired and reachable.
            final OrcaAgentRuntime shared = cache.get(agent);
            assertThat(shared).isNotNull();
            assertThat(shared.getToolRegistry()).isNotNull();
            // And s2 can still successfully run a turn.
            final var result = s2.submit("hello");
            assertThat(result.isSuccess()).isTrue();
        } finally {
            s2.close();
        }
    }

    @Test
    @DisplayName("ContextBuilder returning null is rejected")
    void nullContextRejected() {
        final DefaultAgentRegistry registry = new DefaultAgentRegistry();
        registry.register(DefaultAgent.builder().name("any").maxIterations(1).systemPrompt("x").build());
        final LiveSessionFactory factory = new LiveSessionFactory(registry, agent -> null,
                createExecutor(new StubLlmClient()));

        assertThatThrownBy(() -> factory.open(SessionId.generate(), "any", LiveSessionOptions.defaults()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("null context");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private OrcaAgentRuntime createContext(Agent agent) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-1")).agent(agent)
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

    private static final class StubLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();

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
            if (responses.isEmpty()) {
                return LlmResponse.text("stub-answer");
            }
            return responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }

    }
}
