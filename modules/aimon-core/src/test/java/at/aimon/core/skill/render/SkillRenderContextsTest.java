package at.aimon.core.skill.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

@DisplayName("SkillRenderContexts")
class SkillRenderContextsTest {

    @Nested
    @DisplayName("resolveSkillBaseDir")
    class ResolveSkillBaseDir {

        @Test
        @DisplayName("returns the explicit base directory")
        void shouldReturnExplicitBaseDir() {
            Skill skill = skill("s").baseDir("/skills/s").build();

            assertThat(SkillRenderContexts.resolveSkillBaseDir(skill)).contains("/skills/s");
        }

        @Test
        @DisplayName("explicit base directory wins over the parent of a resource path")
        void shouldPreferExplicitBaseDirOverDerived() {
            Skill skill = skill("s").baseDir("/skills/s").putRootFile("SKILL.md", "/other/loc/SKILL.md").build();

            assertThat(SkillRenderContexts.resolveSkillBaseDir(skill)).contains("/skills/s");
        }

        @Test
        @DisplayName("derives from the first root file when no base directory is set")
        void shouldDeriveFromRootFile() {
            Skill skill = skill("s").putRootFile("SKILL.md", "/skills/s/SKILL.md")
                    .putScript("run.sh", "/elsewhere/scripts/run.sh").build();

            assertThat(SkillRenderContexts.resolveSkillBaseDir(skill)).contains("/skills/s");
        }

        @Test
        @DisplayName("falls back to scripts, then references, then assets — each resolving to the skill root")
        void shouldFallBackThroughResourceKinds() {
            assertThat(SkillRenderContexts
                    .resolveSkillBaseDir(skill("s").putScript("run.sh", "/a/scripts/run.sh").build())).contains("/a");
            assertThat(SkillRenderContexts
                    .resolveSkillBaseDir(skill("s").putReference("r.md", "/b/references/r.md").build())).contains("/b");
            assertThat(
                    SkillRenderContexts.resolveSkillBaseDir(skill("s").putAsset("a.json", "/c/assets/a.json").build()))
                    .contains("/c");
        }

        /**
         * Regression: the fallback used to take the plain parent of the first script, so a scripts-only skill
         * resolved to {@code .../scripts} and {@code ${AIMON_SKILL_DIR}/scripts/x.sh} doubled the segment. With a
         * nested key the parent was deeper still, and which one won depended on map order.
         */
        @Test
        @DisplayName("a nested resource key still resolves to the skill root, whichever resource is first")
        void shouldStripNestedKeyToSkillRoot() {
            Skill nested = skill("s").putScript("lib/util/y.sh", "/skills/s/scripts/lib/util/y.sh").build();
            Skill mixed = skill("s")
                    .scripts(Map.of("x.sh", "/skills/s/scripts/x.sh", "lib/y.sh", "/skills/s/scripts/lib/y.sh"))
                    .build();

            assertThat(SkillRenderContexts.resolveSkillBaseDir(nested)).contains("/skills/s");
            assertThat(SkillRenderContexts.resolveSkillBaseDir(mixed)).contains("/skills/s");
        }

        @Test
        @DisplayName("a path outside the conventional layout falls back to its parent")
        void shouldFallBackToParentForUnconventionalPath() {
            assertThat(SkillRenderContexts
                    .resolveSkillBaseDir(skill("s").putScript("run.sh", "/flat/elsewhere/run.sh").build()))
                    .contains("/flat/elsewhere");
        }

        @Test
        @DisplayName("a resource at the root or with no directory derives an empty base directory")
        void shouldDeriveEmptyForTopLevelPath() {
            assertThat(SkillRenderContexts.resolveSkillBaseDir(skill("s").putRootFile("SKILL.md", "/SKILL.md").build()))
                    .contains("");
            assertThat(SkillRenderContexts.resolveSkillBaseDir(skill("s").putRootFile("SKILL.md", "SKILL.md").build()))
                    .contains("");
        }

        @Test
        @DisplayName("returns empty when the skill has neither a base directory nor resources")
        void shouldReturnEmptyWithoutBaseDirOrResources() {
            assertThat(SkillRenderContexts.resolveSkillBaseDir(skill("s").build())).isEmpty();
        }

        @Test
        @DisplayName("rejects a null skill")
        void shouldRejectNullSkill() {
            assertThatThrownBy(() -> SkillRenderContexts.resolveSkillBaseDir(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("builderFor")
    class BuilderFor {

        @Test
        @DisplayName("sets only the resolved base directory")
        void shouldSetOnlyBaseDir() {
            RenderContext rc = SkillRenderContexts.builderFor(skill("s").baseDir("/skills/s").build()).build();

            assertThat(rc.getSkillBaseDir()).contains("/skills/s");
            assertThat(rc.getAgentRuntimeId()).isEmpty();
            assertThat(rc.getSessionId()).isEmpty();
            assertThat(rc.getExecutionId()).isEmpty();
            assertThat(rc.getPrincipal()).isEmpty();
        }

        @Test
        @DisplayName("a skill with no base directory and no resources yields an empty context")
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
