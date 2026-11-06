package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolCallOrigin and ToolCallPath")
class ToolCallOriginTest {

    @Test
    @DisplayName("each factory sets its path and only the parent that path knows")
    void factoriesSetTheirPath() {
        assertThat(ToolCallOrigin.direct().getPath()).isEqualTo(ToolCallPath.DIRECT);
        assertThat(ToolCallOrigin.replay().getPath()).isEqualTo(ToolCallPath.REPLAY);

        final ToolCallOrigin code = ToolCallOrigin.code("tu-1", "javascript");
        assertThat(code.getPath()).isEqualTo(ToolCallPath.CODE);
        assertThat(code.getParentToolUseId()).contains("tu-1");
        assertThat(code.getLanguage()).contains("javascript");
        assertThat(code.getSkillName()).isEmpty();

        final ToolCallOrigin skill = ToolCallOrigin.skill("audit");
        assertThat(skill.getPath()).isEqualTo(ToolCallPath.SKILL);
        assertThat(skill.getSkillName()).contains("audit");
        assertThat(skill.getParentToolUseId()).isEmpty();
        assertThat(ToolCallOrigin.direct().getParentToolUseId()).isEmpty();
    }

    @Test
    @DisplayName("value equality, and null parents are refused")
    void valueEqualityAndNullChecks() {
        assertThat(ToolCallOrigin.skill("a")).isEqualTo(ToolCallOrigin.skill("a"))
                .isNotEqualTo(ToolCallOrigin.skill("b"));
        assertThat(ToolCallOrigin.code("p", "js")).hasSameHashCodeAs(ToolCallOrigin.code("p", "js"));
        assertThatNullPointerException().isThrownBy(() -> ToolCallOrigin.skill(null));
        assertThatNullPointerException().isThrownBy(() -> ToolCallOrigin.code(null, "js"));
        assertThatNullPointerException().isThrownBy(() -> ToolCallOrigin.code("p", null));
    }

    @Test
    @DisplayName("the wire name exported to shell hooks is the lower-case constant name")
    void wireNames() {
        assertThat(ToolCallPath.DIRECT.wireName()).isEqualTo("direct");
        assertThat(ToolCallPath.CODE.wireName()).isEqualTo("code");
        assertThat(ToolCallPath.SKILL.wireName()).isEqualTo("skill");
        assertThat(ToolCallPath.REPLAY.wireName()).isEqualTo("replay");
    }
}
