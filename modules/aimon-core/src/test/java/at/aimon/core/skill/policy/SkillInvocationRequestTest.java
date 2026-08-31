package at.aimon.core.skill.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.Principal;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

/** Unit tests for {@link SkillInvocationRequest}. */
class SkillInvocationRequestTest {

    @Test
    void build_RequiresSkill() {
        assertThatThrownBy(() -> SkillInvocationRequest.builder().build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill");
    }

    @Test
    void build_NormalisesNullArgsToEmptyString() {
        final SkillInvocationRequest req = SkillInvocationRequest.builder().skill(skill("commit")).build();
        assertThat(req.getArgs()).isEmpty();
    }

    @Test
    void build_PreservesArgsWhenProvided() {
        final SkillInvocationRequest req = SkillInvocationRequest.builder().skill(skill("commit")).args("--scope=feat")
                .build();
        assertThat(req.getArgs()).isEqualTo("--scope=feat");
    }

    @Test
    void contextIdAndPrincipal_AreOptionalAndAbsentByDefault() {
        final SkillInvocationRequest req = SkillInvocationRequest.builder().skill(skill("commit")).build();
        assertThat(req.getAgentRuntimeId()).isEmpty();
        assertThat(req.getPrincipal()).isEmpty();
    }

    @Test
    void contextIdAndPrincipal_PresentWhenProvided() {
        final AgentRuntimeId ctxId = AgentRuntimeId.of("agent:test-1");
        final Principal user = Principal.user("alice");
        final SkillInvocationRequest req = SkillInvocationRequest.builder().skill(skill("commit")).agentRuntimeId(ctxId)
                .principal(user).build();
        assertThat(req.getAgentRuntimeId()).contains(ctxId);
        assertThat(req.getPrincipal()).contains(user);
    }

    @Test
    void conversationIds_AreIndependentAndAbsentByDefault() {
        final SkillInvocationRequest req = SkillInvocationRequest.builder().skill(skill("commit")).build();
        assertThat(req.getSessionId()).isEmpty();
        assertThat(req.getInvokingSessionId()).isEmpty();
    }

    @Test
    void conversationIds_AreDistinguishedFromEachOther() {
        // A fork carries both; they never match, and conflating them is the whole failure mode this field exists for.
        final SessionId own = SessionId.generate();
        final SessionId invoker = SessionId.generate();
        final SkillInvocationRequest req = SkillInvocationRequest.builder().skill(skill("commit")).sessionId(own)
                .invokingSessionId(invoker).build();

        assertThat(req.getSessionId()).contains(own);
        assertThat(req.getInvokingSessionId()).contains(invoker);
    }

    @Test
    void equality_DistinguishesInvokingConversationId() {
        final Skill s = skill("commit");
        final SessionId a = SessionId.generate();
        final SessionId b = SessionId.generate();

        final SkillInvocationRequest first = SkillInvocationRequest.builder().skill(s).invokingSessionId(a).build();
        final SkillInvocationRequest same = SkillInvocationRequest.builder().skill(s).invokingSessionId(a).build();
        final SkillInvocationRequest other = SkillInvocationRequest.builder().skill(s).invokingSessionId(b).build();

        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
    }

    @Test
    void equality_BasedOnAllFields() {
        final Skill s = skill("commit");
        final SkillInvocationRequest a = SkillInvocationRequest.builder().skill(s).args("--x").build();
        final SkillInvocationRequest b = SkillInvocationRequest.builder().skill(s).args("--x").build();
        final SkillInvocationRequest different = SkillInvocationRequest.builder().skill(s).args("--y").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }

    @Test
    void toString_DoesNotLeakArgsContent() {
        final SkillInvocationRequest req = SkillInvocationRequest.builder().skill(skill("commit"))
                .args("super-secret-token").build();
        // Length should leak (cheap audit info), raw content should not.
        assertThat(req.toString()).contains("argsLen=18").doesNotContain("super-secret-token");
    }

    private static Skill skill(String name) {
        return Skill.builder().name(name).metadata(SkillMetadata.builder().name(name).description("desc").build())
                .content(SkillContent.of("body")).build();
    }
}
