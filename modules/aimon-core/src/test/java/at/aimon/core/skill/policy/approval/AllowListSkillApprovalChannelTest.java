package at.aimon.core.skill.policy.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

/** Unit tests for {@link AllowListSkillApprovalChannel}. */
@DisplayName("AllowListSkillApprovalChannel")
class AllowListSkillApprovalChannelTest {

    private InMemorySessionApprovalStore sessionStore;
    private InMemoryAgentApprovalStore agentStore;
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

    private AllowListSkillApprovalChannel channelAllowing(String... skillNames) {
        return new AllowListSkillApprovalChannel(sessionStore, agentStore, List.of(skillNames));
    }

    @Test
    @DisplayName("a listed skill is allowed, and the grant is persisted so SkillTool sees it")
    void allowsListedSkill() {
        channelAllowing("deploy").requestApproval(List.of(pending("deploy")), runtimeId, sessionId);

        assertThat(sessionStore.get(sessionId, "deploy")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    @DisplayName("grants land in the session store — the narrowest scope — not agent-wide")
    void writesAtTheNarrowestScope() {
        channelAllowing("deploy").requestApproval(List.of(pending("deploy")), runtimeId, sessionId);

        assertThat(agentStore.get(runtimeId, "deploy")).isEmpty();
    }

    @Test
    @DisplayName("an unlisted skill is denied")
    void deniesUnlistedSkill() {
        channelAllowing("deploy").requestApproval(List.of(pending("rollback")), runtimeId, sessionId);

        assertThat(sessionStore.get(sessionId, "rollback")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    @DisplayName("a mixed batch is decided per skill in a single call")
    void decidesPerSkillInOneBatch() {
        channelAllowing("deploy", "status").requestApproval(
                List.of(pending("deploy"), pending("rollback"), pending("status")), runtimeId, sessionId);

        assertThat(sessionStore.get(sessionId, "deploy")).contains(SkillInvocationDecision.ALLOW);
        assertThat(sessionStore.get(sessionId, "rollback")).contains(SkillInvocationDecision.DENY);
        assertThat(sessionStore.get(sessionId, "status")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    @DisplayName("matching is case-sensitive — 'Deploy' is not 'deploy'")
    void matchingIsCaseSensitive() {
        channelAllowing("deploy").requestApproval(List.of(pending("Deploy")), runtimeId, sessionId);

        assertThat(sessionStore.get(sessionId, "Deploy")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    @DisplayName("matching is exact — a listed name is not a prefix")
    void matchingIsExactNotPrefix() {
        channelAllowing("deploy").requestApproval(List.of(pending("deploy-prod")), runtimeId, sessionId);

        assertThat(sessionStore.get(sessionId, "deploy-prod")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    @DisplayName("'*' is a literal name, not a wildcard — a pattern dialect would be one typo from granting all")
    void starIsNotAWildcard() {
        channelAllowing("*").requestApproval(List.of(pending("deploy"), pending("*")), runtimeId, sessionId);

        assertThat(sessionStore.get(sessionId, "deploy")).contains(SkillInvocationDecision.DENY);
        assertThat(sessionStore.get(sessionId, "*")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    @DisplayName("a caller with no session gets an agent-scoped grant rather than none at all")
    void fallsBackToAgentScopeWhenSessionIsNull() {
        channelAllowing("deploy").requestApproval(List.of(pending("deploy")), runtimeId, null);

        assertThat(agentStore.get(runtimeId, "deploy")).contains(SkillInvocationDecision.ALLOW);
        assertThat(sessionStore.get(sessionId, "deploy")).isEmpty();
    }

    @Test
    @DisplayName("the two-arg overload behaves exactly like a null session")
    void twoArgOverloadWritesAgentScoped() {
        channelAllowing("deploy").requestApproval(List.of(pending("deploy")), runtimeId);

        assertThat(agentStore.get(runtimeId, "deploy")).contains(SkillInvocationDecision.ALLOW);
    }

    @Test
    @DisplayName("widening a grant to agent scope is logged at WARN — nobody typed 'agent-wide'")
    void warnsWhenAGrantIsWidenedToAgentScope() {
        channelAllowing("deploy").requestApproval(List.of(pending("deploy")), runtimeId, null);

        assertThat(warnings()).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("deploy").contains("agent:ops").contains("ALLOW").contains("/revoke --agent"));
    }

    @Test
    @DisplayName("every widened decision warns, allow and deny alike — one line per skill")
    void warnsOncePerWidenedSkill() {
        channelAllowing("deploy").requestApproval(List.of(pending("deploy"), pending("rollback")), runtimeId, null);

        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(0).getFormattedMessage()).contains("ALLOW").contains("deploy");
        assertThat(warnings().get(1).getFormattedMessage()).contains("DENY").contains("rollback");
    }

    @Test
    @DisplayName("the ordinary session-scoped write stays quiet — nothing was widened")
    void doesNotWarnWhenASessionIsBound() {
        channelAllowing("deploy").requestApproval(List.of(pending("deploy"), pending("rollback")), runtimeId,
                sessionId);

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("an empty allow-list constructs and denies everything")
    void emptyAllowListDeniesEverything() {
        final AllowListSkillApprovalChannel channel = channelAllowing();

        channel.requestApproval(List.of(pending("deploy")), runtimeId, sessionId);

        assertThat(channel.getAllowedSkills()).isEmpty();
        assertThat(sessionStore.get(sessionId, "deploy")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    @DisplayName("an empty batch is a no-op, not an error")
    void emptyBatchIsANoOp() {
        final AllowListSkillApprovalChannel channel = channelAllowing("deploy");

        assertThatCode(() -> channel.requestApproval(List.of(), runtimeId, sessionId)).doesNotThrowAnyException();

        assertThat(sessionStore.get(sessionId, "deploy")).isEmpty();
    }

    @Test
    @DisplayName("a store that fails does not propagate — the contract forbids throwing")
    void swallowsStoreFailures() {
        final AllowListSkillApprovalChannel channel = new AllowListSkillApprovalChannel(
                new ThrowingSessionApprovalStore(), agentStore, List.of("deploy"));

        assertThatCode(() -> channel.requestApproval(List.of(pending("deploy")), runtimeId, sessionId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the source collection is copied — mutating it afterwards cannot widen the gate")
    void allowListIsDefensivelyCopied() {
        final List<String> source = new ArrayList<>(List.of("deploy"));
        final AllowListSkillApprovalChannel channel = new AllowListSkillApprovalChannel(sessionStore, agentStore,
                source);

        source.add("rollback");
        channel.requestApproval(List.of(pending("rollback")), runtimeId, sessionId);

        assertThat(channel.getAllowedSkills()).containsExactly("deploy");
        assertThat(sessionStore.get(sessionId, "rollback")).contains(SkillInvocationDecision.DENY);
    }

    @Test
    void getAllowedSkillsIsUnmodifiable() {
        final Set<String> allowed = channelAllowing("deploy").getAllowedSkills();

        assertThatThrownBy(() -> allowed.add("rollback")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructorRejectsNullAllowList() {
        assertThatThrownBy(() -> new AllowListSkillApprovalChannel(sessionStore, agentStore, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("allowedSkills");
    }

    @Test
    @DisplayName("a null entry fails at wiring time — it could never match a real skill name")
    void constructorRejectsNullEntry() {
        final List<String> withNull = Arrays.asList("deploy", null);

        assertThatThrownBy(() -> new AllowListSkillApprovalChannel(sessionStore, agentStore, withNull))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("allowedSkills[1]");
    }

    @Test
    @DisplayName("a blank entry fails at wiring time, naming the offending position")
    void constructorRejectsBlankEntry() {
        assertThatThrownBy(() -> new AllowListSkillApprovalChannel(sessionStore, agentStore, List.of("deploy", "  ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowedSkills[1]");
    }

    @Test
    void constructorRejectsNullStores() {
        assertThatThrownBy(() -> new AllowListSkillApprovalChannel(null, agentStore, List.of("deploy")))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("sessionApprovalStore");
        assertThatThrownBy(() -> new AllowListSkillApprovalChannel(sessionStore, null, List.of("deploy")))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("agentApprovalStore");
    }

    @Test
    void rejectsNullPendingRequests() {
        final AllowListSkillApprovalChannel channel = channelAllowing("deploy");

        assertThatThrownBy(() -> channel.requestApproval(null, runtimeId, sessionId))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("pendingRequests");
    }

    @Test
    void rejectsNullAgentRuntimeId() {
        final AllowListSkillApprovalChannel channel = channelAllowing("deploy");

        assertThatThrownBy(() -> channel.requestApproval(List.of(pending("deploy")), null, sessionId))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("agentRuntimeId");
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
