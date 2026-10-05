package at.aimon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.base.Principal;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.render.RenderContext;

@DisplayName("SkillRenderContextAccess")
class SkillRenderContextAccessTest {

    @Test
    @DisplayName("copies the ToolContext ids and principal and sets the skill directory to what the environment staged")
    void shouldPopulateFromToolContextAndSkill() {
        Principal principal = Principal.user("alice", "Alice");
        ExecutionEnvironment environment = TestExecutionEnvironments.builder().workingDirectory("/ws").build();
        ToolContext context = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment)
                .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.fromName("ops"))
                .put(ToolContextKeys.SESSION_ID, SessionId.of("conv-1")).put(ToolContextKeys.PRINCIPAL, principal)
                .build();

        // The test environment's stage() answers with the resource's source directory.
        RenderContext rc = SkillRenderContextAccess
                .builderFor(skill("s").stagedResource(resource("/ws/.aimon-staged/s/k1")).build(), context).build();

        assertThat(rc.getAgentRuntimeId()).contains("agent:ops");
        assertThat(rc.getSessionId()).contains("conv-1");
        assertThat(rc.getExecutionId()).isEmpty();
        assertThat(rc.getPrincipal()).contains(principal);
        assertThat(rc.getSkillBaseDir()).contains("/ws/.aimon-staged/s/k1");
    }

    /**
     * EE-20: a skill with something to stage and a context with no environment to stage it into is an error, not an
     * empty {@code ${AIMON_SKILL_DIR}} — execution-environment design §3, no host fallback. The tools that need the
     * environment fail the same way ({@link ExecutionEnvironmentAccess#require}).
     */
    @Test
    @DisplayName("EE-20: refuses to stage a skill when the context carries no execution environment")
    void shouldRefuseToStageWithoutEnvironment() {
        Skill skill = skill("s").stagedResource(resource("/ws/.aimon-staged/s/k1")).build();

        assertThatThrownBy(() -> SkillRenderContextAccess.builderFor(skill, ToolContext.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ExecutionEnvironmentAccess.NO_ENVIRONMENT_MESSAGE);
    }

    @Test
    @DisplayName("propagates a staging failure to the caller, which turns it into an error")
    void shouldPropagateStagingFailure() {
        ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, UnavailableExecutionEnvironment.of("sandbox is down"))
                .build();
        Skill skill = skill("s").stagedResource(resource("/ws/.aimon-staged/s/k1")).build();

        assertThatThrownBy(() -> SkillRenderContextAccess.builderFor(skill, context))
                .isInstanceOf(ExecutionEnvironmentUnavailableException.class).hasMessageContaining("sandbox is down");
    }

    @Test
    @DisplayName("copies an execution id without inventing a session id")
    void shouldCopyExecutionIdOnly() {
        ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ID, ExecutionId.of("subagent:reviewer:fork-7")).build();

        RenderContext rc = SkillRenderContextAccess.builderFor(skill("s").build(), context).build();

        assertThat(rc.getExecutionId()).contains("subagent:reviewer:fork-7");
        assertThat(rc.getSessionId()).isEmpty();
    }

    @Test
    @DisplayName("an empty ToolContext and a resource-less skill yield an empty context")
    void shouldYieldEmptyContext() {
        RenderContext rc = SkillRenderContextAccess.builderFor(skill("s").build(), ToolContext.empty()).build();

        assertThat(rc).isEqualTo(RenderContext.empty());
    }

    @Test
    @DisplayName("rejects null arguments")
    void shouldRejectNulls() {
        Skill skill = skill("s").build();

        assertThatThrownBy(() -> SkillRenderContextAccess.builderFor(null, ToolContext.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SkillRenderContextAccess.builderFor(skill, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static StagedResource resource(String sourceDir) {
        return StagedResource.builder().sourceFileSystem(TestExecutionEnvironments.builder().build().fileSystem())
                .sourceDir(sourceDir).contentKey("k1").name("s").build();
    }

    private static Skill.Builder skill(String name) {
        SkillMetadata m = SkillMetadata.builder().name(name).description("desc").build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body"));
    }
}
