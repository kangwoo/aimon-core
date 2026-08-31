package at.aimon.core.skill.policy.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.skill.policy.SkillInvocationDecision;

/** Unit tests for {@link ApprovalGrant}. */
@DisplayName("ApprovalGrant")
class ApprovalGrantTest {

    @Test
    @DisplayName("a plain yes is session-scoped — never silently widened to the agent")
    void allowForConversationIsConversationScoped() {
        final ApprovalGrant grant = ApprovalGrant.allowForSession();

        assertThat(grant.getDecision()).isEqualTo(SkillInvocationDecision.ALLOW);
        assertThat(grant.getScope()).isEqualTo(ApprovalScope.SESSION);
    }

    @Test
    void allowForAgentIsAgentScoped() {
        final ApprovalGrant grant = ApprovalGrant.allowForAgent();

        assertThat(grant.getDecision()).isEqualTo(SkillInvocationDecision.ALLOW);
        assertThat(grant.getScope()).isEqualTo(ApprovalScope.AGENT);
    }

    @Test
    @DisplayName("a denial is session-scoped too — a refusal should not harden into a standing block")
    void denyForConversationIsConversationScoped() {
        final ApprovalGrant grant = ApprovalGrant.denyForSession();

        assertThat(grant.getDecision()).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(grant.getScope()).isEqualTo(ApprovalScope.SESSION);
    }

    @Test
    void ofBuildsAnyValidCombination() {
        final ApprovalGrant grant = ApprovalGrant.of(SkillInvocationDecision.DENY, ApprovalScope.AGENT);

        assertThat(grant.getDecision()).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(grant.getScope()).isEqualTo(ApprovalScope.AGENT);
    }

    @Test
    @DisplayName("ASK is an unanswered question, so it cannot be a grant")
    void ofRejectsAsk() {
        assertThatThrownBy(() -> ApprovalGrant.of(SkillInvocationDecision.ASK, ApprovalScope.SESSION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ASK");
    }

    @Test
    void ofRejectsNullArguments() {
        assertThatThrownBy(() -> ApprovalGrant.of(null, ApprovalScope.SESSION))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ApprovalGrant.of(SkillInvocationDecision.ALLOW, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityIsByValue() {
        assertThat(ApprovalGrant.allowForSession())
                .isEqualTo(ApprovalGrant.of(SkillInvocationDecision.ALLOW, ApprovalScope.SESSION))
                .hasSameHashCodeAs(ApprovalGrant.of(SkillInvocationDecision.ALLOW, ApprovalScope.SESSION));
    }

    @Test
    @DisplayName("the same decision at different scopes is not the same grant")
    void scopeParticipatesInEquality() {
        assertThat(ApprovalGrant.allowForSession()).isNotEqualTo(ApprovalGrant.allowForAgent());
    }

    @Test
    void toStringMentionsBothHalves() {
        assertThat(ApprovalGrant.allowForAgent().toString()).contains("ALLOW").contains("AGENT");
    }
}
