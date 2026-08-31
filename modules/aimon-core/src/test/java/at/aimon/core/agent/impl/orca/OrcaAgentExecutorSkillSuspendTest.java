package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.SkillTurnSuspendedEvent;
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
import at.aimon.core.llm.Role;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.llm.retry.LlmFallbackPolicy;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationRequest;
import at.aimon.core.skill.policy.SkillPreflightScanner;
import at.aimon.core.skill.policy.pending.InMemoryPendingTurnRegistry;
import at.aimon.core.skill.policy.pending.PendingTurn;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/** Integration tests for SK-11.4: pre-flight scan + atomic suspend in {@link OrcaAgentExecutor}. */
@DisplayName("OrcaAgentExecutor SK-11.4 skill pre-flight scan & suspend")
class OrcaAgentExecutorSkillSuspendTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("scanner unconfigured => legacy path runs (no suspension wiring)")
    void nullScannerPreservesLegacyBehavior() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

        final OrcaAgentExecutor executor = createExecutor(llmClient, null, null);
        final OrcaAgentExecutionResult result = executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    @Test
    @DisplayName("scanner returns proceed => normal tool flow continues to completion")
    void proceedScanRunsToolFlowNormally() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        // Iter 1: Skill tool_use that the policy ALLOWS — flow proceeds, SkillTool will execute (and emit error
        // because the SkillTool isn't registered, but the suspend path is what we care about — it must not trigger).
        llmClient.enqueue(LlmResponse.of("calling skill",
                List.of(ToolUse.of("tu-1", "Skill", Map.of("skill", "commit", "args", ""))),
                TokenUsage.of(10, 10, 20)));
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

        final InMemoryPendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        final SkillPreflightScanner scanner = new SkillPreflightScanner(
                new MapBackedPolicy(Map.of("commit", SkillInvocationDecision.ALLOW)), registryWith(List.of("commit")));

        final OrcaAgentExecutor executor = createExecutor(llmClient, scanner, registry);
        final OrcaAgentExecutionResult result = executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("scanner returns suspend => result reason=SUSPENDED, errorMessage references pendingTurnId")
    void suspendScanReturnsSuspendedResult() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("calling skill",
                List.of(ToolUse.of("tu-1", "Skill", Map.of("skill", "deploy", "args", "--prod"))),
                TokenUsage.of(10, 10, 20)));
        // Safety net: if suspension fails, this would advance the loop.
        llmClient.enqueue(LlmResponse.of("unexpected", List.of(), TokenUsage.of(5, 5, 10)));

        final InMemoryPendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        final SkillPreflightScanner scanner = new SkillPreflightScanner(
                new MapBackedPolicy(Map.of("deploy", SkillInvocationDecision.ASK)), registryWith(List.of("deploy")));

        final OrcaAgentExecutor executor = createExecutor(llmClient, scanner, registry);
        final OrcaAgentExecutionResult result = executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.SUSPENDED);
        assertThat(result.getErrorMessage()).contains("Execution suspended", "pendingTurnId=");
        assertThat(result.getMetadata().getIterationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("suspend registers a PendingTurn carrying the pending skills under the executing context id")
    void suspendScanRegistersPendingTurn() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("calling skill",
                List.of(ToolUse.of("tu-1", "Skill", Map.of("skill", "deploy", "args", "--prod"))),
                TokenUsage.of(10, 10, 20)));

        final InMemoryPendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        final SkillPreflightScanner scanner = new SkillPreflightScanner(
                new MapBackedPolicy(Map.of("deploy", SkillInvocationDecision.ASK)), registryWith(List.of("deploy")));

        final OrcaAgentExecutor executor = createExecutor(llmClient, scanner, registry);
        final OrcaAgentRuntime context = createContext();
        executor.execute(context,
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        final List<PendingTurn> turns = registry.listByAgentRuntime(context.getId());
        assertThat(turns).hasSize(1);
        final PendingTurn turn = turns.get(0);
        assertThat(turn.getAgentRuntimeId()).isEqualTo(context.getId());
        assertThat(turn.getPendingSkills()).hasSize(1);
        assertThat(turn.getPendingSkills().get(0).getSkillName()).isEqualTo("deploy");
        assertThat(turn.getPendingSkills().get(0).getToolUseId()).isEqualTo("tu-1");
        assertThat(turn.getPendingSkills().get(0).getArgs()).isEqualTo("--prod");
    }

    @Test
    @DisplayName("suspend emits SkillTurnSuspendedEvent with the registered pending turn id")
    void suspendScanEmitsSkillTurnSuspendedEvent() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("calling skill",
                List.of(ToolUse.of("tu-1", "Skill", Map.of("skill", "deploy", "args", ""))),
                TokenUsage.of(10, 10, 20)));

        final InMemoryPendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        final SkillPreflightScanner scanner = new SkillPreflightScanner(
                new MapBackedPolicy(Map.of("deploy", SkillInvocationDecision.ASK)), registryWith(List.of("deploy")));

        final OrcaAgentExecutor executor = createExecutor(llmClient, scanner, registry);
        final List<AgentExecutionEvent> events = new CopyOnWriteArrayList<>();
        final Consumer<AgentExecutionEvent> listener = events::add;
        executor.addEventListener(listener);
        try {
            executor.execute(createContext(),
                    OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());
        } finally {
            executor.removeEventListener(listener);
        }

        final List<SkillTurnSuspendedEvent> suspended = events.stream()
                .filter(SkillTurnSuspendedEvent.class::isInstance).map(SkillTurnSuspendedEvent.class::cast).toList();
        assertThat(suspended).hasSize(1);
        final SkillTurnSuspendedEvent event = suspended.get(0);
        assertThat(event.getPendingSkills()).hasSize(1);
        assertThat(event.getPendingSkills().get(0).getSkillName()).isEqualTo("deploy");
        assertThat(registry.get(event.getPendingTurnId())).isPresent();
    }

    @Test
    @DisplayName("suspend does NOT commit the assistant message for the suspended turn (atomic suspension)")
    void suspendScanDoesNotCommitAssistantMessage() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("calling skill",
                List.of(ToolUse.of("tu-1", "Skill", Map.of("skill", "deploy", "args", ""))),
                TokenUsage.of(10, 10, 20)));

        final InMemoryPendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        final SkillPreflightScanner scanner = new SkillPreflightScanner(
                new MapBackedPolicy(Map.of("deploy", SkillInvocationDecision.ASK)), registryWith(List.of("deploy")));

        final OrcaAgentExecutor executor = createExecutor(llmClient, scanner, registry);
        final OrcaAgentExecutionResult result = executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        final SessionSnapshot snapshot = result.getSnapshot();
        // The user message is committed before the LLM call; the assistant message produced by the suspended turn
        // is intentionally not committed so a future resume can re-issue from the same memory state.
        final long assistantMessages = snapshot.getConversationHistory().stream()
                .filter(m -> m.getRole() == Role.ASSISTANT).count();
        assertThat(assistantMessages).isZero();
    }

    @Test
    @DisplayName("pre-flight scan carries the caller identity into the policy")
    void scanCarriesPrincipalIntoPolicy() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("calling skill",
                List.of(ToolUse.of("tu-1", "Skill", Map.of("skill", "deploy", "args", ""))),
                TokenUsage.of(10, 10, 20)));
        llmClient.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

        final CapturingPolicy policy = new CapturingPolicy(SkillInvocationDecision.ALLOW);
        final SkillPreflightScanner scanner = new SkillPreflightScanner(policy, registryWith(List.of("deploy")));

        final OrcaAgentRuntime runtime = createContext();
        final SessionId sessionId = SessionId.generate();
        final Principal principal = Principal.user("user-123", "John Doe");

        createExecutor(llmClient, scanner, new InMemoryPendingTurnRegistry()).execute(runtime,
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(sessionId).principal(principal).build());

        assertThat(policy.requests).hasSize(1);
        final SkillInvocationRequest seen = policy.requests.get(0);
        // Identity-gated policies (per-user allowlists, group membership) are why the field exists at all; dropping it
        // here would evaluate every skill as an anonymous caller without any policy being able to tell.
        assertThat(seen.getPrincipal()).contains(principal);
        assertThat(seen.getSessionId()).contains(sessionId);
        assertThat(seen.getAgentRuntimeId()).contains(runtime.getId());
    }

    @Test
    @DisplayName("an unconfigured TTL expires the registered turn after the framework default")
    void unconfiguredTtlUsesTheDefault() {
        final List<PendingTurn> turns = suspendOneTurn(null).listAll();

        assertThat(turns).hasSize(1);
        assertThat(Duration.between(turns.get(0).getCreatedAt(), turns.get(0).getExpiresAt()))
                .isEqualTo(OrcaAgentExecutor.DEFAULT_PENDING_TURN_TTL);
    }

    @Test
    @DisplayName("a configured TTL is the one the registered turn expires after")
    void configuredTtlReachesTheRegisteredTurn() {
        // The value has to survive the whole way to PendingTurn.ttl, not merely be accepted by the builder: a TTL
        // that is stored and never applied leaves every deployment on the 30-minute default while reporting its own.
        final List<PendingTurn> turns = suspendOneTurn(Duration.ofMinutes(5)).listAll();

        assertThat(turns).hasSize(1);
        assertThat(Duration.between(turns.get(0).getCreatedAt(), turns.get(0).getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("a non-positive TTL is refused at construction")
    void nonPositiveTtlIsRefused() {
        // Zero does not mean "expire immediately" in any useful sense — the turn would be reaped before the
        // approval it is waiting for could arrive, so every ASK would suspend and then vanish.
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        assertThatThrownBy(() -> createExecutor(llmClient, null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pendingTurnTtl");
        assertThatThrownBy(() -> createExecutor(llmClient, null, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pendingTurnTtl");
    }

    /** Runs one turn that suspends on an ASK, and returns the registry it was registered in. */
    private InMemoryPendingTurnRegistry suspendOneTurn(Duration pendingTurnTtl) {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        llmClient.enqueue(LlmResponse.of("calling skill",
                List.of(ToolUse.of("tu-1", "Skill", Map.of("skill", "deploy", "args", ""))),
                TokenUsage.of(10, 10, 20)));

        final InMemoryPendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        final SkillPreflightScanner scanner = new SkillPreflightScanner(
                new MapBackedPolicy(Map.of("deploy", SkillInvocationDecision.ASK)), registryWith(List.of("deploy")));

        createExecutor(llmClient, scanner, registry, pendingTurnTtl).execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());
        return registry;
    }

    @Test
    @DisplayName("constructor rejects scanner without a pending-turn registry")
    void constructorRejectsScannerWithoutRegistry() {
        final SequencedLlmClient llmClient = new SequencedLlmClient();
        final SkillPreflightScanner scanner = new SkillPreflightScanner(new MapBackedPolicy(Map.of()),
                registryWith(List.of()));

        assertThatThrownBy(() -> createExecutor(llmClient, scanner, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pendingTurnRegistry");
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
                .skillRegistry(new TestSkillRegistry()).fileSystem(fileSystem).environment(Environment.createDefault())
                .build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient llmClient, SkillPreflightScanner scanner,
            PendingTurnRegistry registry) {
        return createExecutor(llmClient, scanner, registry, null);
    }

    private OrcaAgentExecutor createExecutor(LlmClient llmClient, SkillPreflightScanner scanner,
            PendingTurnRegistry registry, Duration pendingTurnTtl) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(llmClient);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(llmClient,
                toolManager, hookManager);
        final LlmCallGateway<TranscriptBuffer> gateway = LlmCallGateway.<TranscriptBuffer>builder().client(llmClient)
                .retryPolicy(LlmRetryPolicy.defaultPolicy()).fallbackPolicy(LlmFallbackPolicy.none()).build();
        return OrcaAgentExecutor.builder().gateway(gateway)
                .transcriptManager(new DefaultTranscriptManager(new InMemorySessionRecordStore()))
                .toolExecutionManager(toolManager).hookExecutionManager(hookManager)
                .commandExecutionManager(commandManager).subagentExecutionManager(subagentManager)
                .skillPreflightScanner(scanner).pendingTurnRegistry(registry).pendingTurnTtl(pendingTurnTtl).build();
    }

    private static SkillRegistry registryWith(List<String> skillNames) {
        final TestSkillRegistry registry = new TestSkillRegistry();
        for (String name : skillNames) {
            registry.add(
                    Skill.builder().name(name).metadata(SkillMetadata.builder().name(name).description("desc").build())
                            .content(SkillContent.of("body")).build());
        }
        return registry;
    }

    /** Minimal in-memory {@link SkillRegistry} for tests. */
    private static final class TestSkillRegistry implements SkillRegistry {
        private final Map<String, Skill> skills = new HashMap<>();

        void add(Skill skill) {
            skills.put(skill.getName(), skill);
        }

        @Override
        public Optional<Skill> getSkill(String name) {
            return Optional.ofNullable(skills.get(name));
        }

        @Override
        public List<Skill> getAllSkills() {
            return List.copyOf(skills.values());
        }

        @Override
        public void reloadSkill(String skillName) {
            // no-op
        }

        @Override
        public void reloadAll() {
            // no-op
        }
    }

    /** Minimal {@link SkillInvocationPolicy} backed by a fixed name->decision map (default DENY). */
    private static final class MapBackedPolicy implements SkillInvocationPolicy {
        private final Map<String, SkillInvocationDecision> decisions;

        MapBackedPolicy(Map<String, SkillInvocationDecision> decisions) {
            this.decisions = decisions;
        }

        @Override
        public SkillInvocationDecision check(SkillInvocationRequest request) {
            return decisions.getOrDefault(request.getSkill().getName(), SkillInvocationDecision.DENY);
        }
    }

    /** {@link SkillInvocationPolicy} that records every request it sees and answers with one fixed decision. */
    private static final class CapturingPolicy implements SkillInvocationPolicy {
        private final List<SkillInvocationRequest> requests = new CopyOnWriteArrayList<>();
        private final SkillInvocationDecision decision;

        CapturingPolicy(SkillInvocationDecision decision) {
            this.decision = decision;
        }

        @Override
        public SkillInvocationDecision check(SkillInvocationRequest request) {
            requests.add(request);
            return decision;
        }
    }

    /** Sequenced LLM client returning pre-queued responses; mirrors the helper in OrcaAgentExecutorBudgetTest. */
    private static final class SequencedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private final Duration delay = Duration.ZERO;

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
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (responses.isEmpty()) {
                return LlmResponse.text("unexpected-extra-call");
            }
            return responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Sequenced";
        }

    }
}
