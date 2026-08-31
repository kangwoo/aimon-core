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

class DenyTurnCommandTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final SessionId SESSION = SessionId.of("conv-1");

    private PendingTurnRegistry registry;
    private SessionApprovalStore sessionApprovals;
    private AgentApprovalStore agentApprovals;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPendingTurnRegistry();
        sessionApprovals = new InMemorySessionApprovalStore();
        agentApprovals = new InMemoryAgentApprovalStore();
    }

    @Test
    void constructorRejectsNullRegistry() {
        assertThatThrownBy(() -> new DenyTurnCommand(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullRegistryEvenWithStores() {
        assertThatThrownBy(() -> new DenyTurnCommand(null, sessionApprovals, agentApprovals))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hasCorrectNameDescriptionAndType() {
        DenyTurnCommand command = new DenyTurnCommand(registry);

        assertThat(command.getName()).isEqualTo("deny");
        assertThat(command.getMetadata().getDescription())
                .hasValue("Deny a suspended turn by id (--agent to deny for the whole agent)");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeRejectsNullContext() {
        DenyTurnCommand command = new DenyTurnCommand(registry);

        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("turn-1")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeRejectsNullRequest() {
        DenyTurnCommand command = new DenyTurnCommand(registry);

        assertThatThrownBy(() -> command.execute(createContext(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyArgsShowsUsage() {
        DenyTurnCommand command = new DenyTurnCommand(registry);

        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Usage: /deny <turn-id> [--agent]").contains("/pending");
    }

    @Test
    void whitespaceOnlyArgsShowsUsage() {
        DenyTurnCommand command = new DenyTurnCommand(registry);

        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of("   "));

        assertThat(result.getResponse()).contains("Usage: /deny <turn-id> [--agent]");
    }

    @Test
    void flagWithoutAnIdShowsUsage() {
        CommandExecutionResult result = scopedCommand().execute(createContext(),
                DirectCommandExecutionRequest.of("--agent"));

        assertThat(result.getResponse()).contains("Usage: /deny <turn-id> [--agent]");
    }

    @Test
    void unknownIdReturnsNotFoundMessage() {
        DenyTurnCommand command = new DenyTurnCommand(registry);

        CommandExecutionResult result = command.execute(createContext(),
                DirectCommandExecutionRequest.of("unknown-id"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("No pending turn found with id: unknown-id");
    }

    // ---------------------------------------------------------------------
    // Default (session) scope
    // ---------------------------------------------------------------------

    @Test
    void denialLandsInTheTurnsConversationByDefault() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, SESSION, "commit", "deploy"));

        CommandExecutionResult result = scopedCommand().execute(createContext(),
                DirectCommandExecutionRequest.of("turn-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Denied turn turn-1").contains("skills: [commit, deploy]");
        assertThat(sessionApprovals.get(SESSION, "commit")).contains(SkillInvocationDecision.DENY);
        assertThat(sessionApprovals.get(SESSION, "deploy")).contains(SkillInvocationDecision.DENY);
        // The narrow default must not block the skill in the agent's other sessions.
        assertThat(agentApprovals.get(ctx, "commit")).isEmpty();
        assertThat(sessionApprovals.get(SessionId.of("conv-2"), "commit")).isEmpty();
    }

    @Test
    void turnWithoutAConversationFallsBackToAgentScope() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, null, "commit"));

        scopedCommand().execute(createContext(), DirectCommandExecutionRequest.of("turn-1"));

        assertThat(agentApprovals.get(ctx, "commit")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    void withNoStoresWiredTheTurnIsStillDroppedAndNothingIsCached() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, SESSION, "commit"));

        // The legacy single-store-less wiring degrades to single-shot: re-issuing the prompt re-triggers the ASK.
        CommandExecutionResult result = new DenyTurnCommand(registry).execute(createContext(),
                DirectCommandExecutionRequest.of("turn-1"));

        assertThat(result.getResponse()).contains("Denied turn turn-1");
        assertThat(registry.get(PendingTurnId.of("turn-1"))).isEmpty();
        assertThat(sessionApprovals.get(SESSION, "commit")).isEmpty();
        assertThat(agentApprovals.get(ctx, "commit")).isEmpty();
    }

    // ---------------------------------------------------------------------
    // --agent scope
    // ---------------------------------------------------------------------

    @Test
    void agentFlagWidensTheDenialToTheWholeRuntime() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, SESSION, "commit"));

        CommandExecutionResult result = scopedCommand().execute(createContext(),
                DirectCommandExecutionRequest.of("turn-1 --agent"));

        assertThat(result.getResponse()).contains("Denied turn turn-1");
        assertThat(agentApprovals.get(ctx, "commit")).contains(SkillInvocationDecision.DENY);
        assertThat(sessionApprovals.get(SESSION, "commit")).isEmpty();
    }

    @Test
    void flagMayPrecedeTheTurnId() {
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-a");
        registry.register(turn("turn-1", ctx, SESSION, "commit"));

        CommandExecutionResult result = scopedCommand().execute(createContext(),
                DirectCommandExecutionRequest.of("--agent turn-1"));

        assertThat(result.getResponse()).contains("Denied turn turn-1");
        assertThat(agentApprovals.get(ctx, "commit")).contains(SkillInvocationDecision.DENY);
    }

    // ---------------------------------------------------------------------
    // Shared behaviour
    // ---------------------------------------------------------------------

    @Test
    void existingIdRemovesTurnAndReportsSkills() {
        registry.register(turn("turn-1", AgentRuntimeIds.testCtx("ctx-a"), SESSION, "commit", "deploy"));

        DenyTurnCommand command = new DenyTurnCommand(registry);
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of("turn-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Denied turn turn-1").contains("skills: [commit, deploy]")
                .contains("Send a new prompt");
        assertThat(registry.get(PendingTurnId.of("turn-1"))).isEmpty();
    }

    @Test
    void duplicateSkillNamesAreReportedOnce() {
        registry.register(turn("turn-3", AgentRuntimeIds.testCtx("ctx-a"), SESSION, "commit", "commit", "deploy"));

        CommandExecutionResult result = scopedCommand().execute(createContext(),
                DirectCommandExecutionRequest.of("turn-3"));

        assertThat(result.getResponse()).contains("skills: [commit, deploy]");
        assertThat(sessionApprovals.get(SESSION, "commit")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    void trailingTokensAreIgnoredAndOnlyFirstIdIsUsed() {
        registry.register(turn("turn-7", AgentRuntimeIds.testCtx("ctx-a"), SESSION, "commit"));

        DenyTurnCommand command = new DenyTurnCommand(registry);
        // Simulate user pasting a copied line: "turn-7 context=ctx-a skills=[commit]" — the command should still
        // resolve
        // turn-7 cleanly.
        CommandExecutionResult result = command.execute(createContext(),
                DirectCommandExecutionRequest.of("turn-7 context=ctx-a skills=[commit]"));

        assertThat(result.getResponse()).contains("Denied turn turn-7");
        assertThat(registry.get(PendingTurnId.of("turn-7"))).isEmpty();
    }

    @Test
    void denyingTwiceReturnsNotFoundOnSecondAttempt() {
        registry.register(turn("turn-1", AgentRuntimeIds.testCtx("ctx-a"), SESSION, "commit"));

        DenyTurnCommand command = new DenyTurnCommand(registry);
        CommandExecutionResult first = command.execute(createContext(), DirectCommandExecutionRequest.of("turn-1"));
        CommandExecutionResult second = command.execute(createContext(), DirectCommandExecutionRequest.of("turn-1"));

        assertThat(first.getResponse()).contains("Denied turn turn-1");
        assertThat(second.getResponse()).contains("No pending turn found with id: turn-1");
    }

    private DenyTurnCommand scopedCommand() {
        return new DenyTurnCommand(registry, sessionApprovals, agentApprovals);
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
        DenyTurnCommand dummy = new DenyTurnCommand(new InMemoryPendingTurnRegistry());
        return CommandExecutionContext.builder().command(dummy).defaultModel(LlmModel.builder().name("test").build())
                .toolRegistry(new DefaultToolRegistry()).build();
    }
}
