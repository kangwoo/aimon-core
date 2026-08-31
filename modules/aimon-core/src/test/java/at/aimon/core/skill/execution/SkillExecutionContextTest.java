package at.aimon.core.skill.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

/**
 * Stage 6-3 — pins the run identity a skill invocation carries.
 *
 * <p>
 * The interesting field is {@code executionId}, and the interesting thing about it is that it is <em>mandatory</em>: an
 * optional run id would leave {@code LlmSkillExecutor} a reason to fall back to minting one, which is the defect this
 * slice removes.
 */
class SkillExecutionContextTest {

    private static SkillExecutionContext.Builder minimal() {
        final SkillMetadata metadata = SkillMetadata.builder().name("commit").description("desc").build();
        final Skill skill = Skill.builder().name("commit").metadata(metadata).content(SkillContent.of("body")).build();
        return SkillExecutionContext.builder().skill(skill).defaultModel(LlmModel.builder().build())
                .toolRegistry(new DefaultToolRegistry());
    }

    /**
     * A skill run has no session of its own, but it is still a run — so it must be identifiable. Leaving the id out is
     * how a caller would previously have got a fabricated one for free.
     */
    @Test
    void executionIdIsMandatory() {
        assertThatNullPointerException().isThrownBy(() -> minimal().build())
                .withMessageContaining("Execution id cannot be null");
    }

    @Test
    void executionIdIsExposedUnchanged() {
        final ExecutionId run = ExecutionId.of("skill:commit:run-1");

        final SkillExecutionContext context = minimal().executionId(run).build();

        assertThat(context.getExecutionId()).isEqualTo(run);
    }

    /**
     * The run's own id and the session it acts for travel on different rails: the {@link ExecutionId} identifies
     * <em>this</em> invocation, while the tool context carries the invoking session. Neither substitutes for the other.
     */
    @Test
    void toolContextDefaultsToEmptyAndIsIndependentOfTheRunId() {
        final SkillExecutionContext context = minimal().executionId(ExecutionId.generate("skill:commit")).build();

        assertThat(context.getToolContext()).isNotNull();
        assertThat(context.getToolContext().getContext()).isEmpty();

        final ToolContext supplied = ToolContext.builder().build();
        assertThat(minimal().executionId(ExecutionId.generate("skill:commit")).toolContext(supplied).build()
                .getToolContext()).isSameAs(supplied);
    }
}
