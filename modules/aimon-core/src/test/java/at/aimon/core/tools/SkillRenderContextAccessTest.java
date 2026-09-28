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
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.render.RenderContext;

@DisplayName("SkillRenderContextAccess")
class SkillRenderContextAccessTest {

    @Test
    @DisplayName("copies the ToolContext ids and principal and sets the resolved base directory")
    void shouldPopulateFromToolContextAndSkill() {
        Principal principal = Principal.user("alice", "Alice");
        ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.fromName("ops"))
                .put(ToolContextKeys.SESSION_ID, SessionId.of("conv-1")).put(ToolContextKeys.PRINCIPAL, principal)
                .build();

        RenderContext rc = SkillRenderContextAccess.builderFor(skill("s").baseDir("/skills/s").build(), context)
                .build();

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

    private static Skill.Builder skill(String name) {
        SkillMetadata m = SkillMetadata.builder().name(name).description("desc").build();
        return Skill.builder().name(name).metadata(m).content(SkillContent.of("body"));
    }
}
