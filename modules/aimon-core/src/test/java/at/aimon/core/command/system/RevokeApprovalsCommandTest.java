package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.core.tools.ToolContextKeys;

/** Unit tests for {@link RevokeApprovalsCommand}. */
class RevokeApprovalsCommandTest {

    private static final SessionId SESSION = SessionId.of("conv-1");

    private AgentApprovalStore approvals;
    private SessionApprovalStore sessionApprovals;

    @BeforeEach
    void setUp() {
        approvals = new InMemoryAgentApprovalStore();
        sessionApprovals = new InMemorySessionApprovalStore();
    }

    @Test
    void constructorRejectsNullApprovals() {
        assertThatThrownBy(() -> new RevokeApprovalsCommand(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullAgentApprovalsEvenWithAConversationStore() {
        assertThatThrownBy(() -> new RevokeApprovalsCommand(sessionApprovals, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hasCorrectNameDescriptionAndType() {
        RevokeApprovalsCommand command = new RevokeApprovalsCommand(approvals);

        assertThat(command.getName()).isEqualTo("revoke");
        assertThat(command.getMetadata().getDescription())
                .hasValue("Forget the skill approvals granted in this session (--agent for agent-wide)");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeRejectsNullContext() {
        RevokeApprovalsCommand command = new RevokeApprovalsCommand(approvals);

        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeRejectsNullRequest() {
        RevokeApprovalsCommand command = new RevokeApprovalsCommand(approvals);

        assertThatThrownBy(() -> command.execute(createContext(AgentRuntimeIds.testCtx("ops-bot")), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------------
    // Default (session) scope
    // ---------------------------------------------------------------------

    @Test
    void plainRevokeDropsOnlyThisConversationsApprovals() {
        AgentRuntimeId runtime = AgentRuntimeIds.testCtx("ops-bot");
        sessionApprovals.put(SESSION, "commit", SkillInvocationDecision.ALLOW);
        approvals.put(runtime, "deploy", SkillInvocationDecision.ALLOW);

        CommandExecutionResult result = scopedCommand().execute(createContext(runtime, SESSION),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Revoked the skill approvals granted in session conv-1")
                .contains("/revoke --agent");
        assertThat(sessionApprovals.get(SESSION, "commit")).isEmpty();
        // The agent-wide grant is a separate, deliberately broader decision — a plain /revoke must not touch it.
        assertThat(approvals.get(runtime, "deploy")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void plainRevokeLeavesOtherConversationsAlone() {
        SessionId bystander = SessionId.of("conv-2");
        sessionApprovals.put(SESSION, "commit", SkillInvocationDecision.ALLOW);
        sessionApprovals.put(bystander, "commit", SkillInvocationDecision.ALLOW);

        scopedCommand().execute(createContext(AgentRuntimeIds.testCtx("ops-bot"), SESSION),
                DirectCommandExecutionRequest.of(""));

        assertThat(sessionApprovals.get(SESSION, "commit")).isEmpty();
        assertThat(sessionApprovals.get(bystander, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void plainRevokeFallsBackToAgentScopeWhenNoConversationIsBound() {
        AgentRuntimeId runtime = AgentRuntimeIds.testCtx("ops-bot");
        approvals.put(runtime, "commit", SkillInvocationDecision.ALLOW);

        // No TranscriptBuffer on the context: there is no narrower partition to clear.
        CommandExecutionResult result = scopedCommand().execute(createContext(runtime),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("Revoked all skill approvals for agent:ops-bot");
        assertThat(approvals.get(runtime, "commit")).isEmpty();
    }

    // ---------------------------------------------------------------------
    // --agent scope
    // ---------------------------------------------------------------------

    @Test
    void agentFlagAlsoDropsTheCurrentConversationsApprovals() {
        AgentRuntimeId runtime = AgentRuntimeIds.testCtx("ops-bot");
        approvals.put(runtime, "deploy", SkillInvocationDecision.ALLOW);
        sessionApprovals.put(SESSION, "commit", SkillInvocationDecision.ALLOW);

        CommandExecutionResult result = scopedCommand().execute(createContext(runtime, SESSION),
                DirectCommandExecutionRequest.of("--agent"));

        assertThat(result.getResponse()).contains("Revoked all skill approvals for agent:ops-bot");
        assertThat(approvals.get(runtime, "deploy")).isEmpty();
        // "Forget everything for this agent" that left a narrower ALLOW running would astonish the user.
        assertThat(sessionApprovals.get(SESSION, "commit")).isEmpty();
    }

    @Test
    void dropsEveryCachedDecisionForTheBoundRuntime() {
        AgentRuntimeId runtime = AgentRuntimeIds.testCtx("ops-bot");
        approvals.put(runtime, "commit", SkillInvocationDecision.ALLOW);
        approvals.put(runtime, "deploy", SkillInvocationDecision.DENY);

        RevokeApprovalsCommand command = new RevokeApprovalsCommand(approvals);
        CommandExecutionResult result = command.execute(createContext(runtime),
                DirectCommandExecutionRequest.of("--agent"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Revoked all skill approvals for agent:ops-bot")
                .contains("will ask again");
        assertThat(approvals.get(runtime, "commit")).isEmpty();
        assertThat(approvals.get(runtime, "deploy")).isEmpty();
    }

    @Test
    void leavesOtherRuntimesUntouched() {
        AgentRuntimeId target = AgentRuntimeIds.testCtx("ops-bot");
        AgentRuntimeId bystander = AgentRuntimeIds.testCtx("deploy-bot");
        approvals.put(target, "commit", SkillInvocationDecision.ALLOW);
        approvals.put(bystander, "commit", SkillInvocationDecision.ALLOW);

        new RevokeApprovalsCommand(approvals).execute(createContext(target),
                DirectCommandExecutionRequest.of("--agent"));

        assertThat(approvals.get(target, "commit")).isEmpty();
        assertThat(approvals.get(bystander, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void revokingWithNothingCachedStillSucceeds() {
        AgentRuntimeId runtime = AgentRuntimeIds.testCtx("ops-bot");

        CommandExecutionResult result = new RevokeApprovalsCommand(approvals).execute(createContext(runtime),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Revoked all skill approvals");
    }

    @Test
    void revokingTwiceIsIdempotent() {
        AgentRuntimeId runtime = AgentRuntimeIds.testCtx("ops-bot");
        approvals.put(runtime, "commit", SkillInvocationDecision.ALLOW);

        RevokeApprovalsCommand command = new RevokeApprovalsCommand(approvals);
        command.execute(createContext(runtime), DirectCommandExecutionRequest.of(""));
        CommandExecutionResult second = command.execute(createContext(runtime), DirectCommandExecutionRequest.of(""));

        assertThat(second.isSuccess()).isTrue();
        assertThat(approvals.get(runtime, "commit")).isEmpty();
    }

    @Test
    void unrecognizedArgumentsDoNotWidenTheScope() {
        AgentRuntimeId runtime = AgentRuntimeIds.testCtx("ops-bot");
        approvals.put(runtime, "commit", SkillInvocationDecision.ALLOW);
        sessionApprovals.put(SESSION, "commit", SkillInvocationDecision.ALLOW);

        CommandExecutionResult result = scopedCommand().execute(createContext(runtime, SESSION),
                DirectCommandExecutionRequest.of("commit --force"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(sessionApprovals.get(SESSION, "commit")).isEmpty();
        assertThat(approvals.get(runtime, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void missingAgentRuntimeIdReportsInsteadOfClaimingSuccess() {
        AgentRuntimeId runtime = AgentRuntimeIds.testCtx("ops-bot");
        approvals.put(runtime, "commit", SkillInvocationDecision.ALLOW);

        // A CommandExecutionContext built without a tool context defaults to ToolContext.empty(), so the key is absent.
        CommandExecutionContext contextWithoutRuntime = CommandExecutionContext.builder()
                .command(new RevokeApprovalsCommand(approvals)).defaultModel(LlmModel.builder().name("test").build())
                .toolRegistry(new DefaultToolRegistry()).build();

        CommandExecutionResult result = new RevokeApprovalsCommand(approvals).execute(contextWithoutRuntime,
                DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("Cannot revoke").contains("no agent runtime is bound");
        // The store must be left alone rather than blindly cleared.
        assertThat(approvals.get(runtime, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    private RevokeApprovalsCommand scopedCommand() {
        return new RevokeApprovalsCommand(sessionApprovals, approvals);
    }

    private CommandExecutionContext createContext(AgentRuntimeId runtimeId) {
        return createContext(runtimeId, null);
    }

    private CommandExecutionContext createContext(AgentRuntimeId runtimeId, SessionId sessionId) {
        CommandExecutionContext.Builder builder = CommandExecutionContext.builder()
                .command(new RevokeApprovalsCommand(approvals)).defaultModel(LlmModel.builder().name("test").build())
                .toolRegistry(new DefaultToolRegistry())
                .toolContext(ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, runtimeId).build());
        if (sessionId != null) {
            builder.transcriptBuffer(new TranscriptBuffer(sessionId));
        }
        return builder.build();
    }
}
