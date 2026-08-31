package at.aimon.core.skill.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

/** Unit tests for {@link NoOpSkillContentRenderer}. */
class NoOpSkillContentRendererTest {

    private NoOpSkillContentRenderer renderer;
    private Skill skill;

    @BeforeEach
    void setUp() {
        renderer = new NoOpSkillContentRenderer();
        skill = Skill.builder().name("sample")
                .metadata(SkillMetadata.builder().name("sample").description("desc").build())
                .content(SkillContent.of("Original instructions body")).build();
    }

    @Test
    void render_ReturnsSkillInstructionsUnchanged() {
        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Original instructions body");
    }

    @Test
    void render_NonEmptyArgs_StillReturnsBodyUnchanged() {
        final String result = renderer.render(skill, "some args here", RenderContext.empty());

        assertThat(result).isEqualTo("Original instructions body");
    }

    @Test
    void render_NullSkill_ThrowsNullPointerException() {
        assertThatThrownBy(() -> renderer.render(null, "", RenderContext.empty()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Skill");
    }

    @Test
    void render_NullArgs_ThrowsNullPointerException() {
        assertThatThrownBy(() -> renderer.render(skill, null, RenderContext.empty()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Args");
    }

    @Test
    void render_NullContext_ThrowsNullPointerException() {
        assertThatThrownBy(() -> renderer.render(skill, "", null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Context");
    }
}
