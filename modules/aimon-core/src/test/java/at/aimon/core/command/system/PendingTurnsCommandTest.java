package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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
import at.aimon.core.skill.policy.pending.InMemoryPendingTurnRegistry;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurn;
import at.aimon.core.skill.policy.pending.PendingTurnId;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;

class PendingTurnsCommandTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");

    @Test
    void constructorRejectsNullRegistry() {
        assertThatThrownBy(() -> new PendingTurnsCommand(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullClock() {
        assertThatThrownBy(() -> new PendingTurnsCommand(new InMemoryPendingTurnRegistry(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hasCorrectNameDescriptionAndType() {
        PendingTurnsCommand command = new PendingTurnsCommand(new InMemoryPendingTurnRegistry());

        assertThat(command.getName()).isEqualTo("pending");
        assertThat(command.getMetadata().getDescription()).hasValue("List turns suspended awaiting user approval");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeRejectsNullContext() {
        PendingTurnsCommand command = new PendingTurnsCommand(new InMemoryPendingTurnRegistry());

        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeRejectsNullRequest() {
        PendingTurnsCommand command = new PendingTurnsCommand(new InMemoryPendingTurnRegistry());

        assertThatThrownBy(() -> command.execute(createContext(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rendersEmptyMessageWhenNoTurnsRegistered() {
        PendingTurnsCommand command = new PendingTurnsCommand(new InMemoryPendingTurnRegistry(), fixedClock(T0));

        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Pending turns:").contains("No turns are currently suspended.")
                .doesNotContain("Total:");
    }

    @Test
    void rendersTurnWithIdContextSkillsAgeAndRemainingTtl() {
        PendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        AgentRuntimeId ctx = AgentRuntimeIds.testCtx("ctx-abc");
        PendingTurn turn = PendingTurn.builder().id(PendingTurnId.of("turn-1")).agentRuntimeId(ctx)
                .pendingSkills(List.of(skill("commit"), skill("deploy"))).createdAt(T0).ttl(Duration.ofMinutes(30))
                .build();
        registry.register(turn);

        // Now is 90s after creation → age=1m30s, remaining=28m30s
        PendingTurnsCommand command = new PendingTurnsCommand(registry, fixedClock(T0.plusSeconds(90)));
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("turn-1").contains("context=agent:ctx-abc")
                .contains("skills=[commit, deploy]").contains("age=1m30s").contains("28m30s remaining")
                .contains("Total: 1 turn(s)");
    }

    @Test
    void showsTheConversationAnApproveWouldReach() {
        PendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        registry.register(PendingTurn.builder().id(PendingTurnId.of("turn-1"))
                .agentRuntimeId(AgentRuntimeIds.testCtx("ctx-abc")).sessionId(SessionId.of("conv-1"))
                .pendingSkills(List.of(skill("commit"))).createdAt(T0).ttl(Duration.ofMinutes(30)).build());

        PendingTurnsCommand command = new PendingTurnsCommand(registry, fixedClock(T0));
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("session=conv-1");
    }

    @Test
    void rendersConversationNoneForTurnsThatCarryNoConversation() {
        PendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        // No suspension the framework performs omits the session (see PendingTurn#getSessionId); this pins the
        // rendering for an entry an embedder registered itself, where /approve can only reach agent-wide.
        registry.register(
                PendingTurn.builder().id(PendingTurnId.of("turn-1")).agentRuntimeId(AgentRuntimeIds.testCtx("ctx-abc"))
                        .pendingSkills(List.of(skill("commit"))).createdAt(T0).ttl(Duration.ofMinutes(30)).build());

        PendingTurnsCommand command = new PendingTurnsCommand(registry, fixedClock(T0));
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("session=none");
    }

    @Test
    void rendersExpiredTurnWithExpiredMarker() {
        PendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        registry.register(
                PendingTurn.builder().id(PendingTurnId.of("turn-2")).agentRuntimeId(AgentRuntimeIds.testCtx("c"))
                        .pendingSkills(List.of(skill("rollback"))).createdAt(T0).ttl(Duration.ofMinutes(1)).build());

        // Now is 5m past creation → expiresAt was at +1m, so expired by 4m
        PendingTurnsCommand command = new PendingTurnsCommand(registry, fixedClock(T0.plus(Duration.ofMinutes(5))));
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("turn-2").contains("expired").doesNotContain("remaining");
    }

    @Test
    void rendersMultipleTurnsSortedByCreatedAt() {
        PendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        // Register out of order; the registry sorts by createdAt.
        registry.register(PendingTurn.builder().id(PendingTurnId.of("turn-late"))
                .agentRuntimeId(AgentRuntimeIds.testCtx("c1")).pendingSkills(List.of(skill("s1")))
                .createdAt(T0.plusSeconds(60)).ttl(Duration.ofHours(1)).build());
        registry.register(
                PendingTurn.builder().id(PendingTurnId.of("turn-early")).agentRuntimeId(AgentRuntimeIds.testCtx("c2"))
                        .pendingSkills(List.of(skill("s2"))).createdAt(T0).ttl(Duration.ofHours(1)).build());

        PendingTurnsCommand command = new PendingTurnsCommand(registry, fixedClock(T0.plusSeconds(120)));
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("Total: 2 turn(s)");
        int earlyIdx = result.getResponse().indexOf("turn-early");
        int lateIdx = result.getResponse().indexOf("turn-late");
        assertThat(earlyIdx).isPositive();
        assertThat(lateIdx).isGreaterThan(earlyIdx);
    }

    @Test
    void agePastOneHourFormatsAsHoursAndMinutes() {
        PendingTurnRegistry registry = new InMemoryPendingTurnRegistry();
        registry.register(
                PendingTurn.builder().id(PendingTurnId.of("turn-old")).agentRuntimeId(AgentRuntimeIds.testCtx("c"))
                        .pendingSkills(List.of(skill("commit"))).createdAt(T0).ttl(Duration.ofHours(5)).build());

        // Now is 2h15m after creation
        PendingTurnsCommand command = new PendingTurnsCommand(registry,
                fixedClock(T0.plus(Duration.ofHours(2).plusMinutes(15))));
        CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.getResponse()).contains("age=2h15m");
    }

    private static PendingSkillRequest skill(String name) {
        return PendingSkillRequest.builder().toolUseId("tu_" + name).skillName(name).build();
    }

    private static Clock fixedClock(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private CommandExecutionContext createContext() {
        PendingTurnsCommand dummy = new PendingTurnsCommand(new InMemoryPendingTurnRegistry());
        return CommandExecutionContext.builder().command(dummy).defaultModel(LlmModel.builder().name("test").build())
                .toolRegistry(new DefaultToolRegistry()).build();
    }
}
