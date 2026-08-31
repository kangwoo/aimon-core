package at.aimon.core.skill.fork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

class NoOpSkillForkExecutorTest {

    private final NoOpSkillForkExecutor executor = new NoOpSkillForkExecutor();

    private static Skill forkSkill() {
        return Skill.builder().name("review")
                .metadata(SkillMetadata.builder().name("review").description("Review code")
                        .executionMode(ExecutionMode.FORK).forkAgentName("code-reviewer").build())
                .content(SkillContent.of("body")).build();
    }

    @Test
    void fork_ReturnsFailureMentioningSkillNameAndConfiguration() {
        SkillForkOutcome outcome = executor.fork(forkSkill(), "goal", ToolContext.empty());

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getErrorMessage()).get().asString().contains("Skill 'review'")
                .contains("execution.mode=fork").contains("NoOpSkillForkExecutor");
    }

    @Test
    void fork_NullSkill_Throws() {
        assertThatThrownBy(() -> executor.fork(null, "goal", ToolContext.empty()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Skill");
    }

    @Test
    void fork_NullGoal_Throws() {
        assertThatThrownBy(() -> executor.fork(forkSkill(), null, ToolContext.empty()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Goal");
    }

    @Test
    void fork_NullToolContext_Throws() {
        assertThatThrownBy(() -> executor.fork(forkSkill(), "goal", null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Tool context");
    }
}
