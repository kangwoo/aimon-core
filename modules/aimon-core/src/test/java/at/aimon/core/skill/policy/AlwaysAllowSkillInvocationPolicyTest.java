package at.aimon.core.skill.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

/** Unit tests for {@link AlwaysAllowSkillInvocationPolicy}. */
class AlwaysAllowSkillInvocationPolicyTest {

    @Test
    void alwaysReturnsAllow() {
        final SkillInvocationPolicy policy = AlwaysAllowSkillInvocationPolicy.INSTANCE;
        final Skill skill = Skill.builder().name("anything")
                .metadata(SkillMetadata.builder().name("anything").description("d").build())
                .content(SkillContent.of("body")).build();

        assertThat(policy.check(SkillInvocationRequest.builder().skill(skill).build()))
                .isEqualTo(SkillInvocationDecision.ALLOW);
    }

    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(() -> AlwaysAllowSkillInvocationPolicy.INSTANCE.check(null))
                .isInstanceOf(NullPointerException.class);
    }
}
