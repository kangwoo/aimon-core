package at.aimon.core.skill.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

/** Unit tests for {@link NoOpSkillHookActivator}. */
class NoOpSkillHookActivatorTest {

    private static Skill testSkill() {
        return Skill.builder().name("s").metadata(SkillMetadata.builder().name("s").description("d").build())
                .content(SkillContent.of("body")).build();
    }

    @Test
    void activate_returnsEmptyScope() {
        SkillHookActivator activator = new NoOpSkillHookActivator();

        SkillHookScope scope = activator.activate(testSkill());

        assertThat(scope).isSameAs(SkillHookScope.EMPTY);
    }

    @Test
    void activate_nullSkill_throws() {
        SkillHookActivator activator = new NoOpSkillHookActivator();

        assertThatThrownBy(() -> activator.activate(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void scopeClose_isIdempotent() {
        SkillHookScope scope = new NoOpSkillHookActivator().activate(testSkill());

        scope.close();
        scope.close(); // must not throw
    }
}
