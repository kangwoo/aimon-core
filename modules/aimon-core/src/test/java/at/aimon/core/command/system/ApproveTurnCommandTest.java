package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeIds;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;
import at.aimon.core.skill.policy.pending.InMemoryPendingTurnRegistry;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurn;
import at.aimon.core.skill.policy.pending.PendingTurnId;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

class ApproveTurnCommandTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final SessionId SESSION = SessionId.of("conv-1");

    private PendingTurnRegistry registry;
    private AgentApprovalStore approvals;
    private SessionApprovalStore sessionApprovals;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPendingTurnRegistry();
        approvals = new InMemoryAgentApprovalStore();
        sessionApprovals = new InMemorySessionApprovalStore();
    }

    @Test
    void constructorRejectsNullRegistry() {
        assertThatThrownBy(() -> new ApproveTurnCommand(null, approvals)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullApprovals() {
        assertThatThrownBy(() -> new ApproveTurnCommand(registry, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullAgentApprovalsEvenWithAConversationStore() {
        assertThatThrownBy(() -> new ApproveTurnCommand(registry, sessionApprovals, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hasCorrectNameDescriptionAndType() {
        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);

        assertThat(command.getName()).isEqualTo("approve");
        assertThat(command.getMetadata().getDescription())
                .hasValue("Approve a suspended turn by id (--agent to approve for the whole agent)");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeRejectsNullContext() {
        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);

        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("turn-1")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeRejectsNullRequest() {
        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);

        assertThatThrownBy(() -> command.execute(createContext(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyArgsShowsUsage() {
        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);

        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("Usage: /approve <turn-id> [--agent]").contains("/pending");
    }

    @Test
    void flagWithoutAnIdShowsUsage() {
        ApproveTurnCommand command = scopedCommand();

        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of("--agent"));

        assertThat(result.getResponse()).contains("Usage: /approve <turn-id> [--agent]");
    }

    @Test
    void unknownIdReturnsNotFoundAndDoesNotTouchApprovals() {
        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);

        CommandExecutionResult result = command.execute(createContext(),
                DirectCommandExecutionRequest.of("missing-id"));

        assertThat(result.getResponse()).contains("No pending turn found with id: missing-id");
        // Sanity check: nothing got cached for a random context/skill pair either.
        assertThat(approvals.get(AgentRuntimeIds.testCtx("ctx-x"), "commit")).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Default (session) scope
    // ---------------------------------------------------------------------

    @Test
    void approvalLandsInTheTurnsConversationByDefault() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, SESSION, "commit", "deploy"));

        CommandExecutionResult result = scopedCommand().execute(createContext(),
                DirectCommandExecutionRequest.of("turn-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Approved turn turn-1").contains("skills: [commit, deploy]")
                .contains("in session conv-1").contains("Resume the agent");
        assertThat(sessionApprovals.get(SESSION, "commit")).contains(SkillInvocationDecision.ALLOW);
        assertThat(sessionApprovals.get(SESSION, "deploy")).contains(SkillInvocationDecision.ALLOW);
        // The narrow default must not leak into the agent-wide store.
        assertThat(approvals.get(ctx, "commit")).isEmpty();
        assertThat(registry.get(PendingTurnId.of("turn-1"))).isEmpty();
    }

    @Test
    void approvalDoesNotReachTheAgentsOtherConversations() {
        registry.register(turn("turn-1", AgentRuntimeIds.testCtx("ctx-a"), SESSION, "commit"));

        scopedCommand().execute(createContext(), DirectCommandExecutionRequest.of("turn-1"));

        assertThat(sessionApprovals.get(SessionId.of("conv-2"), "commit")).isEmpty();
    }

    @Test
    void turnWithoutAConversationFallsBackToAgentScope() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        // Not a path the framework takes — no suspension it performs omits the session (see
        // PendingTurn#getSessionId). This pins the fallback for an entry an embedder registered itself: with no
        // session there is no store narrower than the agent to write to.
        registry.register(turn("turn-1", ctx, null, "commit"));

        CommandExecutionResult result = scopedCommand().execute(createContext(),
                DirectCommandExecutionRequest.of("turn-1"));

        assertThat(result.getResponse()).contains("for agent agent:ctx-a");
        assertThat(approvals.get(ctx, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void withoutAConversationStoreApprovalGoesAgentWide() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, SESSION, "commit"));

        CommandExecutionResult result = new ApproveTurnCommand(registry, approvals).execute(createContext(),
                DirectCommandExecutionRequest.of("turn-1"));

        assertThat(result.getResponse()).contains("for agent agent:ctx-a");
        assertThat(approvals.get(ctx, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    // ---------------------------------------------------------------------
    // --agent scope
    // ---------------------------------------------------------------------

    @Test
    void agentFlagWidensTheApprovalToTheWholeRuntime() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, SESSION, "commit"));

        CommandExecutionResult result = scopedCommand().execute(createContext(),
                DirectCommandExecutionRequest.of("turn-1 --agent"));

        assertThat(result.getResponse()).contains("for agent agent:ctx-a");
        assertThat(approvals.get(ctx, "commit")).contains(SkillInvocationDecision.ALLOW);
        // Widening writes only the broad entry; /revoke --agent then clears both.
        assertThat(sessionApprovals.get(SESSION, "commit")).isEmpty();
    }

    @Test
    void flagMayPrecedeTheTurnId() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, SESSION, "commit"));

        CommandExecutionResult result = scopedCommand().execute(createContext(),
                DirectCommandExecutionRequest.of("--agent turn-1"));

        assertThat(result.getResponse()).contains("Approved turn turn-1").contains("for agent agent:ctx-a");
        assertThat(approvals.get(ctx, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    // ---------------------------------------------------------------------
    // Shared behaviour
    // ---------------------------------------------------------------------

    @Test
    void existingIdCachesAllowForEachSkillAndRemovesTurn() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, null, "commit", "deploy"));

        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of("turn-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Approved turn turn-1").contains("skills: [commit, deploy]")
                .contains("Resume the agent");
        assertThat(approvals.get(ctx, "commit")).contains(SkillInvocationDecision.ALLOW);
        assertThat(approvals.get(ctx, "deploy")).contains(SkillInvocationDecision.ALLOW);
        assertThat(registry.get(PendingTurnId.of("turn-1"))).isEmpty();
    }

    @Test
    void duplicateSkillNamesAreCachedOnceAndReportedOnce() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-2", ctx, null, "commit", "commit", "deploy"));

        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of("turn-2"));

        // The "commit" skill should appear in the message exactly once even though the turn carried two requests for
        // it.
        assertThat(result.getResponse()).contains("skills: [commit, deploy]");
        assertThat(approvals.get(ctx, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void approvalsAreScopedToTheTurnOwningContext() {
        AgentRuntimeId targetCtx = AgentRuntimeIds.testCtx("ctx-target");
        AgentRuntimeId otherCtx = AgentRuntimeIds.testCtx("ctx-other");
        registry.register(turn("turn-1", targetCtx, null, "commit"));

        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);
        command.execute(createContext(), DirectCommandExecutionRequest.of("turn-1"));

        assertThat(approvals.get(targetCtx, "commit")).contains(SkillInvocationDecision.ALLOW);
        assertThat(approvals.get(otherCtx, "commit")).isEmpty();
    }

    @Test
    void approvingTwiceReturnsNotFoundOnSecondAttempt() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, null, "commit"));

        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);
        CommandExecutionResult first = command.execute(createContext(), DirectCommandExecutionRequest.of("turn-1"));
        CommandExecutionResult second = command.execute(createContext(), DirectCommandExecutionRequest.of("turn-1"));

        assertThat(first.getResponse()).contains("Approved turn turn-1");
        assertThat(second.getResponse()).contains("No pending turn found with id: turn-1");
        // First call's cached approval still stands.
        assertThat(approvals.get(ctx, "commit")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    void trailingTokensAreIgnoredAndOnlyFirstIdIsUsed() {
        registry.register(turn("turn-7", AgentRuntimeIds.testCtx("ctx-a"), null, "commit"));

        ApproveTurnCommand command = new ApproveTurnCommand(registry, approvals);
        CommandExecutionResult result = command.execute(createContext(),
                DirectCommandExecutionRequest.of("turn-7 context=ctx-a skills=[commit]"));

        assertThat(result.getResponse()).contains("Approved turn turn-7");
        assertThat(registry.get(PendingTurnId.of("turn-7"))).isEmpty();
    }

    private ApproveTurnCommand scopedCommand() {
        return new ApproveTurnCommand(registry, sessionApprovals, approvals);
    }

    private static PendingTurn turn(String id, AgentRuntimeId runtimeId, SessionId sessionId, String... skills) {
        final List<PendingSkillRequest> requests = new ArrayList<>();
        for (String name : skills) {
            requests.add(skill(name));
        }
        return PendingTurn.builder().id(PendingTurnId.of(id)).agentRuntimeId(runtimeId).sessionId(sessionId)
                .pendingSkills(requests).createdAt(T0).ttl(Duration.ofMinutes(30)).build();
    }

    private static PendingSkillRequest skill(String name) {
        return PendingSkillRequest.builder().toolUseId("tu_" + name + "_" + System.nanoTime()).skillName(name).build();
    }

    private CommandExecutionContext createContext() {
        ApproveTurnCommand dummy = new ApproveTurnCommand(new InMemoryPendingTurnRegistry(),
                new InMemoryAgentApprovalStore());
        return CommandExecutionContext.builder().command(dummy).defaultModel(LlmModel.builder().name("test").build())
                .toolRegistry(new DefaultToolRegistry()).build();
    }
}
