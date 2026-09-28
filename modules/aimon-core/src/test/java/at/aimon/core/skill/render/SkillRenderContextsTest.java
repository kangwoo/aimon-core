package at.aimon.core.skill.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.base.Principal;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.tools.ToolContextKeys;

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
        @DisplayName("falls back to scripts, then references, then assets")
        void shouldFallBackThroughResourceKinds() {
            assertThat(SkillRenderContexts
                    .resolveSkillBaseDir(skill("s").putScript("run.sh", "/a/scripts/run.sh").build()))
                    .contains("/a/scripts");
            assertThat(SkillRenderContexts
                    .resolveSkillBaseDir(skill("s").putReference("r.md", "/b/references/r.md").build()))
                    .contains("/b/references");
            assertThat(
                    SkillRenderContexts.resolveSkillBaseDir(skill("s").putAsset("a.json", "/c/assets/a.json").build()))
                    .contains("/c/assets");
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
        @DisplayName("copies the ToolContext ids and principal and sets the resolved base directory")
        void shouldPopulateFromToolContextAndSkill() {
            Principal principal = Principal.user("alice", "Alice");
            ToolContext context = ToolContext.builder()
                    .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.fromName("ops"))
                    .put(ToolContextKeys.SESSION_ID, SessionId.of("conv-1")).put(ToolContextKeys.PRINCIPAL, principal)
                    .build();

            RenderContext rc = SkillRenderContexts.builderFor(skill("s").baseDir("/skills/s").build(), context).build();

            assertThat(rc.getAgentRuntimeId()).contains("agent:ops");
            assertThat(rc.getSessionId()).contains("conv-1");
            assertThat(rc.getExecutionId()).isEmpty();
            assertThat(rc.getPrincipal()).contains(principal);
            assertThat(rc.getSkillBaseDir()).contains("/skills/s");
        }

        @Test
        @DisplayName("copies an execution id without inventing a session id")
        void shouldCopyExecutionIdOnly() {
            ToolContext context = ToolContext.builder()
                    .put(ToolContextKeys.EXECUTION_ID, ExecutionId.of("subagent:reviewer:fork-7")).build();

            RenderContext rc = SkillRenderContexts.builderFor(skill("s").build(), context).build();

            assertThat(rc.getExecutionId()).contains("subagent:reviewer:fork-7");
            assertThat(rc.getSessionId()).isEmpty();
        }

        @Test
        @DisplayName("an empty ToolContext and a resource-less skill yield an empty context")
        void shouldYieldEmptyContext() {
            RenderContext rc = SkillRenderContexts.builderFor(skill("s").build(), ToolContext.empty()).build();

            assertThat(rc).isEqualTo(RenderContext.empty());
        }

        @Test
        @DisplayName("rejects null arguments")
        void shouldRejectNulls() {
            Skill skill = skill("s").build();

            assertThatThrownBy(() -> SkillRenderContexts.builderFor(null, ToolContext.empty()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SkillRenderContexts.builderFor(skill, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    private static Skill.Builder skill(String name) {
        SkillMetadata m = SkillMetadata.builder().name(name).description("desc").build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body"));
    }
}
