package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.fork.NoOpSkillForkExecutor;
import at.aimon.core.skill.hook.NoOpSkillHookActivator;
import at.aimon.core.skill.policy.RuleBasedSkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.agent.ApprovalCachingSkillInvocationPolicy;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionScopedSkillInvocationPolicy;
import at.aimon.core.skill.render.NoOpSkillContentRenderer;
import at.aimon.core.skill.render.RenderContext;
import at.aimon.core.skill.render.SkillContentRenderer;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.skill.SkillTool;
import at.aimon.core.tools.todo.TodoWriteTool;

/**
 * End-to-end guard for skill approvals reaching subagent forks.
 *
 * <p>
 * A fork has no {@link SessionId} of its own, and nothing could ever be stored under one: a fork can never prompt for
 * an approval — no channel is reachable from one, and none may be. So a fork's skill call can only be answered by the
 * decision the user gave in the session that spawned it. These tests run a real {@link DefaultSubagentExecutor}
 * against the real policy chain rather than a mock, because the bug they guard lived in the wiring between the two,
 * not in either piece.
 *
 * <p>
 * The rule chain is deliberately configured with {@code safeByDefault(false)}: the default rule policy ALLOWs any
 * hook-less INLINE skill outright, which would let every case here pass no matter what the stores contain.
 */
@DisplayName("DefaultSubagentExecutor skill approval inheritance")
class DefaultSubagentExecutorSkillApprovalTest {

    private static final String SKILL_NAME = "deploy";

    private InMemorySessionApprovalStore sessionStore;
    private InMemoryAgentApprovalStore agentStore;
    private RecordingRenderer renderer;
    private MapSkillRegistry skillRegistry;

    @BeforeEach
    void setUp() {
        sessionStore = new InMemorySessionApprovalStore();
        agentStore = new InMemoryAgentApprovalStore();
        renderer = new RecordingRenderer();
        skillRegistry = new MapSkillRegistry();
        skillRegistry.add(skill(SKILL_NAME));
    }

    @Test
    @DisplayName("fork inherits the grant the user gave in the conversation that spawned it")
    void forkInheritsInvokingConversationGrant() {
        final SessionId userSession = SessionId.generate();
        sessionStore.put(userSession, SKILL_NAME, SkillInvocationDecision.ALLOW);

        final SubagentExecutionResult result = runFork(userSession);

        assertThat(result.isSuccess()).isTrue();
        assertThat(renderer.renders).as("the skill body must have been rendered, i.e. the policy allowed the call")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a grant in an unrelated conversation does not reach the fork")
    void unrelatedConversationGrantIsNotInherited() {
        sessionStore.put(SessionId.generate(), SKILL_NAME, SkillInvocationDecision.ALLOW);

        final SubagentExecutionResult result = runFork(SessionId.generate());

        assertThat(result.isSuccess()).isTrue(); // the run completes; only the skill call is refused
        assertThat(renderer.renders).isZero();
    }

    @Test
    @DisplayName("a deny in the invoking conversation beats an agent-wide allow")
    void invokingConversationDenyBeatsAgentWideAllow() {
        final SessionId userSession = SessionId.generate();
        sessionStore.put(userSession, SKILL_NAME, SkillInvocationDecision.DENY);
        agentStore.put(AgentRuntimeId.of("agent:test-1"), SKILL_NAME, SkillInvocationDecision.ALLOW);

        runFork(userSession);

        assertThat(renderer.renders).as("the narrower refusal must win over the broader standing grant").isZero();
    }

    @Test
    @DisplayName("a run nobody asked for inherits nothing and is unaffected")
    void systemInitiatedRunIsUnchanged() {
        sessionStore.put(SessionId.generate(), SKILL_NAME, SkillInvocationDecision.ALLOW);

        // No invoking session: a scheduled task, a background job — nobody granted anything on its behalf.
        final SubagentExecutionResult result = runFork(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(renderer.renders).isZero();
    }

    /**
     * Stage 6-4 — the fork publishes no session id at all. Before, it published the one it had minted for its
     * transcript, which a tool could not distinguish from the user's; what per-run state actually needs is a unique
     * bucket, and an execution id supplies that without claiming a user is on the other end.
     */
    @Test
    @DisplayName("the fork publishes a run id and no session id, so per-run tool state stays partitioned")
    void forkConversationIdStaysUniqueAndPartitionsTodoState() {
        final SessionId userSession = SessionId.generate();
        sessionStore.put(userSession, SKILL_NAME, SkillInvocationDecision.ALLOW);

        final ContextCapturingSkillTool probe = new ContextCapturingSkillTool(skillRegistry, renderer, policy());
        runFork(userSession, probe);

        assertThat(probe.forkSessionId).as("a fork is not a session, so it must not claim to be one").isNull();
        assertThat(probe.forkExecutionId).isNotNull();
        assertThat(probe.forkExecutionId.value()).startsWith("subagent:deployer:").isNotEqualTo(userSession.value());
        assertThat(probe.invokingSessionId).isEqualTo(userSession);
        assertThat(probe.todoContextId).as("todo state is keyed on the fork's own run id, not the invoker's session")
                .isEqualTo(probe.forkExecutionId.value());
    }

    @Test
    @DisplayName("an unresolvable approval never blocks the fork — it fails fast")
    void forkNeverBlocksOnApproval() {
        // Nothing is stored anywhere, so the chain falls through to ASK. A fork has no channel to ask on; the call must
        // return a refusal immediately rather than park waiting for an answer that can never arrive.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            final SubagentExecutionResult result = runFork(SessionId.generate());
            assertThat(result.isSuccess()).isTrue();
            assertThat(renderer.renders).isZero();
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private SubagentExecutionResult runFork(SessionId invokingSessionId) {
        return runFork(invokingSessionId, new SkillTool(skillRegistry, renderer, new NoOpSkillForkExecutor(),
                new NoOpSkillHookActivator(), policy()));
    }

    private SubagentExecutionResult runFork(SessionId invokingSessionId, SkillTool skillTool) {
        final DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(skillTool);

        final ScriptedLlmClient llm = new ScriptedLlmClient();
        llm.responses
                .add(LlmResponse.of("", List.of(ToolUse.of("t1", SkillTool.TOOL_NAME, Map.of("skill", SKILL_NAME)))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(toolRegistry)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();

        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("ship it")
                .invokingSessionId(invokingSessionId).build();

        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .execute(context, request);
    }

    /** The production chain, narrow-first: session -> agent -> rules. */
    private SkillInvocationPolicy policy() {
        final SkillInvocationPolicy rules = RuleBasedSkillInvocationPolicy.builder().safeByDefault(false)
                .defaultDecision(SkillInvocationDecision.ASK).build();
        return new SessionScopedSkillInvocationPolicy(sessionStore,
                new ApprovalCachingSkillInvocationPolicy(agentStore, rules));
    }

    private static Skill skill(String name) {
        return Skill.builder().name(name).metadata(SkillMetadata.builder().name(name).description("d").build())
                .content(SkillContent.of("body for " + name)).build();
    }

    private Subagent subagent() {
        return Subagent.of("deployer", SubagentMetadata.builder().description("d").maxIterations(5).build(),
                SubagentContent.of("you are deployer"));
    }

    /** Counts renders: the renderer runs only after the policy has ALLOWed, so it is the allow/deny witness. */
    private static final class RecordingRenderer implements SkillContentRenderer {
        private int renders;

        @Override
        public String render(Skill skill, String args, RenderContext context) {
            renders++;
            return new NoOpSkillContentRenderer().render(skill, args, context);
        }
    }

    /** A real SkillTool that also records the identity keys it was handed, so the ids can be asserted directly. */
    private static final class ContextCapturingSkillTool extends SkillTool {
        private SessionId forkSessionId;
        private ExecutionId forkExecutionId;
        private SessionId invokingSessionId;
        private String todoContextId;

        ContextCapturingSkillTool(SkillRegistry registry, SkillContentRenderer renderer, SkillInvocationPolicy policy) {
            super(registry, renderer, new NoOpSkillForkExecutor(), new NoOpSkillHookActivator(), policy);
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            forkSessionId = context.get(ToolContextKeys.SESSION_ID).orElse(null);
            forkExecutionId = context.get(ToolContextKeys.EXECUTION_ID).orElse(null);
            invokingSessionId = context.get(ToolContextKeys.INVOKING_SESSION_ID).orElse(null);
            todoContextId = context.get(TodoWriteTool.CONTEXT_ID_KEY).orElse(null);
            return super.execute(input, context);
        }
    }

    private static final class MapSkillRegistry implements SkillRegistry {
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
            // No-op: skills are added directly.
        }

        @Override
        public void reloadAll() {
            // No-op: skills are added directly.
        }
    }

    /** Minimal scriptable LLM client: hands back queued responses, then a terminal text. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> calls = new ArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            calls.add(List.copyOf(messages));
            return responses.isEmpty() ? LlmResponse.text("done") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }

    }
}
