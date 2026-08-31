package at.aimon.core.skill.policy.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.InMemoryAgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.session.InMemorySessionApprovalStore;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Unit tests for {@link DenyAllSkillApprovalChannel}. */
@DisplayName("DenyAllSkillApprovalChannel")
class DenyAllSkillApprovalChannelTest {

    private InMemorySessionApprovalStore sessionStore;
    private InMemoryAgentApprovalStore agentStore;
    private DenyAllSkillApprovalChannel channel;
    private AgentRuntimeId runtimeId;
    private SessionId sessionId;
    private Logger writerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    private static PendingSkillRequest pending(String skillName) {
        return PendingSkillRequest.builder().toolUseId("tu-" + skillName).skillName(skillName).args("").build();
    }

    @BeforeEach
    void setUp() {
        sessionStore = new InMemorySessionApprovalStore();
        agentStore = new InMemoryAgentApprovalStore();
        channel = new DenyAllSkillApprovalChannel(sessionStore, agentStore);
        runtimeId = AgentRuntimeId.of("agent:ops");
        sessionId = SessionId.of("conv-1");

        writerLogger = (Logger) LoggerFactory.getLogger(ApprovalGrantWriter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        writerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        writerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    private List<ILoggingEvent> warnings() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("persists a DENY for every requested skill — the scanner never re-runs the policy")
    void deniesEveryRequestedSkill() {
        channel.requestApproval(List.of(pending("deploy"), pending("rollback")), runtimeId, sessionId);

        assertThat(sessionStore.get(sessionId, "deploy")).contains(SkillInvocationDecision.DENY);
        assertThat(sessionStore.get(sessionId, "rollback")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    @DisplayName("denials land in the session store — the narrowest scope — not agent-wide")
    void writesAtTheNarrowestScope() {
        channel.requestApproval(List.of(pending("deploy")), runtimeId, sessionId);

        assertThat(sessionStore.get(sessionId, "deploy")).contains(SkillInvocationDecision.DENY);
        assertThat(agentStore.get(runtimeId, "deploy")).isEmpty();
    }

    @Test
    @DisplayName("an answer given in one session does not pre-answer another")
    void sessionScopedDenialDoesNotLeak() {
        channel.requestApproval(List.of(pending("deploy")), runtimeId, sessionId);

        assertThat(sessionStore.get(SessionId.of("conv-2"), "deploy")).isEmpty();
    }

    @Test
    @DisplayName("a caller with no session gets an agent-scoped denial rather than none at all")
    void fallsBackToAgentScopeWhenSessionIsNull() {
        channel.requestApproval(List.of(pending("deploy")), runtimeId, null);

        assertThat(agentStore.get(runtimeId, "deploy")).contains(SkillInvocationDecision.DENY);
        assertThat(sessionStore.get(sessionId, "deploy")).isEmpty();
    }

    @Test
    @DisplayName("the two-arg overload behaves exactly like a null session")
    void twoArgOverloadWritesAgentScoped() {
        channel.requestApproval(List.of(pending("deploy")), runtimeId);

        assertThat(agentStore.get(runtimeId, "deploy")).contains(SkillInvocationDecision.DENY);
        assertThat(sessionStore.get(sessionId, "deploy")).isEmpty();
    }

    @Test
    @DisplayName("the widened denial is logged at WARN — the agent-wide reach is visible, not merely documented")
    void warnsWhenADenialIsWidenedToAgentScope() {
        channel.requestApproval(List.of(pending("deploy")), runtimeId, null);

        assertThat(warnings()).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("deploy").contains("agent:ops").contains("DENY").contains("/revoke --agent"));
    }

    @Test
    @DisplayName("the ordinary session-scoped write stays quiet — nothing was widened")
    void doesNotWarnWhenASessionIsBound() {
        channel.requestApproval(List.of(pending("deploy"), pending("rollback")), runtimeId, sessionId);

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("two ASK invocations of the same skill in one batch share a single write")
    void deDuplicatesBySkillName() {
        final CountingSessionApprovalStore counting = new CountingSessionApprovalStore();
        final DenyAllSkillApprovalChannel countingChannel = new DenyAllSkillApprovalChannel(counting, agentStore);

        countingChannel.requestApproval(List.of(pending("deploy"), pending("deploy"), pending("rollback")), runtimeId,
                sessionId);

        assertThat(counting.putCount).isEqualTo(2);
        assertThat(counting.decisions).containsOnlyKeys("deploy", "rollback");
    }

    @Test
    @DisplayName("an empty batch is a no-op, not an error — throwing would strand a headless run on the suspend path")
    void emptyBatchIsANoOp() {
        assertThatCode(() -> channel.requestApproval(List.of(), runtimeId, sessionId)).doesNotThrowAnyException();

        assertThat(sessionStore.get(sessionId, "deploy")).isEmpty();
        assertThat(agentStore.get(runtimeId, "deploy")).isEmpty();
    }

    @Test
    @DisplayName("a store that fails does not propagate — the contract forbids throwing")
    void swallowsStoreFailures() {
        final DenyAllSkillApprovalChannel failingChannel = new DenyAllSkillApprovalChannel(
                new ThrowingSessionApprovalStore(), agentStore);

        assertThatCode(() -> failingChannel.requestApproval(List.of(pending("deploy")), runtimeId, sessionId))
                .doesNotThrowAnyException();
    }

    @Test
    void constructorRejectsNullSessionStore() {
        assertThatThrownBy(() -> new DenyAllSkillApprovalChannel(null, agentStore))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("sessionApprovalStore");
    }

    @Test
    void constructorRejectsNullAgentStore() {
        assertThatThrownBy(() -> new DenyAllSkillApprovalChannel(sessionStore, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("agentApprovalStore");
    }

    @Test
    void rejectsNullPendingRequests() {
        assertThatThrownBy(() -> channel.requestApproval(null, runtimeId, sessionId))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("pendingRequests");
    }

    @Test
    void rejectsNullAgentRuntimeId() {
        assertThatThrownBy(() -> channel.requestApproval(List.of(pending("deploy")), null, sessionId))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("agentRuntimeId");
    }

    /** Counts writes so a test can prove duplicate pending requests collapse into one. */
    private static final class CountingSessionApprovalStore implements SessionApprovalStore {

        private final Map<String, SkillInvocationDecision> decisions = new LinkedHashMap<>();
        private int putCount;

        @Override
        public Optional<SkillInvocationDecision> get(SessionId sessionId, String skillName) {
            return Optional.ofNullable(decisions.get(skillName));
        }

        @Override
        public void put(SessionId sessionId, String skillName, SkillInvocationDecision decision) {
            putCount++;
            decisions.put(skillName, decision);
        }

        @Override
        public void invalidate(SessionId sessionId) {
            decisions.clear();
        }
    }

    /** Stands in for a shared store that is down. */
    private static final class ThrowingSessionApprovalStore implements SessionApprovalStore {

        @Override
        public Optional<SkillInvocationDecision> get(SessionId sessionId, String skillName) {
            return Optional.empty();
        }

        @Override
        public void put(SessionId sessionId, String skillName, SkillInvocationDecision decision) {
            throw new IllegalStateException("approval store is unreachable");
        }

        @Override
        public void invalidate(SessionId sessionId) {
            throw new IllegalStateException("approval store is unreachable");
        }
    }
}
