package at.aimon.core.skill.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VirtualFileSystems;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

@DisplayName("SkillRenderContexts")
class SkillRenderContextsTest {

    @Nested
    @DisplayName("builderFor")
    class BuilderFor {

        /**
         * The skill directory is no longer derived here: {@code ${AIMON_SKILL_DIR}} is the path the execution
         * environment returns when it stages the skill (execution-environment design §4.4), which only
         * {@code SkillRenderContextAccess} can compute. A skill's resource paths — repository paths — must never leak
         * into the context from this side.
         */
        @Test
        @DisplayName("never sets the skill directory, not even from a staged resource or resource paths")
        void shouldNotSetSkillBaseDir() {
            StagedResource resource = StagedResource.builder()
                    .sourceFileSystem(VirtualFileSystems.readOnlyLocal(Path.of("/skills"))).sourceDir("s")
                    .contentKey("abcdef0123456789").name("s").build();
            Skill skill = skill("s").stagedResource(resource).putRootFile("SKILL.md", "/skills/s/SKILL.md")
                    .putScript("run.sh", "/skills/s/scripts/run.sh").build();

            RenderContext rc = SkillRenderContexts.builderFor(skill).build();

            assertThat(rc.getSkillBaseDir()).isEmpty();
            assertThat(rc.getAgentRuntimeId()).isEmpty();
            assertThat(rc.getSessionId()).isEmpty();
            assertThat(rc.getExecutionId()).isEmpty();
            assertThat(rc.getPrincipal()).isEmpty();
        }

        @Test
        @DisplayName("a skill with no resources yields an empty context")
        void shouldYieldEmptyContext() {
            assertThat(SkillRenderContexts.builderFor(skill("s").build()).build()).isEqualTo(RenderContext.empty());
        }

        @Test
        @DisplayName("rejects a null skill")
        void shouldRejectNullSkill() {
            assertThatThrownBy(() -> SkillRenderContexts.builderFor(null)).isInstanceOf(NullPointerException.class);
        }
    }

    private static Skill.Builder skill(String name) {
        SkillMetadata m = SkillMetadata.builder().name(name).description("desc").build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body"));
    }
}
