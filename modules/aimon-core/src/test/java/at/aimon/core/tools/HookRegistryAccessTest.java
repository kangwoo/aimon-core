package at.aimon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.hook.SkillHookSet;
import at.aimon.core.skill.hook.SkillScopedHookRegistry;

/** Unit tests for {@link HookRegistryAccess}. */
class HookRegistryAccessTest {

    private static final PreToolHook GUARD = ctx -> HookResult.success();
    private static final PostToolHook AUDIT = ctx -> HookResult.success();

    private final DefaultHookRegistry runtime = new DefaultHookRegistry();

    private static ToolContext contextWith(HookRegistry registry) {
        return ToolContext.builder().put(ToolContextKeys.HOOK_REGISTRY, registry).build();
    }

    @Test
    void of_readsTheRegistryOrEmpty() {
        assertThat(HookRegistryAccess.of(ToolContext.empty())).isEmpty();
        assertThat(HookRegistryAccess.of(contextWith(runtime))).containsSame(runtime);
    }

    @Test
    void hookRegistryKey_isWriteOnce() {
        ToolContext.Builder builder = ToolContext.builder().put(ToolContextKeys.HOOK_REGISTRY, runtime);

        // An enricher that tries to swap the registry — and with it a skill's guards — fails instead.
        assertThatThrownBy(() -> builder.put(ToolContextKeys.HOOK_REGISTRY, new DefaultHookRegistry()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withHookRegistry_replacesOnlyThatKey() {
        AgentRuntimeId runtimeId = AgentRuntimeId.fromName("ops", "acme");
        SessionId session = SessionId.of("session-a");
        ExecutionEnvironment environment = org.mockito.Mockito.mock(ExecutionEnvironment.class);
        ToolContext original = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, runtimeId)
                .put(ToolContextKeys.SESSION_ID, session).put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment)
                .put(ToolContextKeys.HOOK_REGISTRY, runtime).put("custom", "kept").build();
        HookRegistry view = new SkillScopedHookRegistry(runtime, "s", SkillHookSet.builder().addPreTool(GUARD).build());

        ToolContext copy = HookRegistryAccess.withHookRegistry(original, view);

        assertThat(HookRegistryAccess.of(copy)).containsSame(view);
        assertThat(copy.get(ToolContextKeys.AGENT_RUNTIME_ID)).contains(runtimeId);
        assertThat(copy.get(ToolContextKeys.SESSION_ID)).contains(session);
        assertThat(copy.get(ToolContextKeys.EXECUTION_ENVIRONMENT)).containsSame(environment);
        assertThat(copy.get("custom")).contains("kept");
        assertThat(copy.size()).isEqualTo(original.size());
        // The original is untouched: the invoking execution keeps dispatching against its own registry.
        assertThat(HookRegistryAccess.of(original)).containsSame(runtime);
    }

    @Test
    void withHookRegistry_addsTheKeyToAContextThatHadNone() {
        ToolContext copy = HookRegistryAccess.withHookRegistry(ToolContext.empty(), runtime);

        assertThat(HookRegistryAccess.of(copy)).containsSame(runtime);
    }

    @Test
    void activeSkillGuards_emptyWithoutASkillView() {
        assertThat(HookRegistryAccess.activeSkillGuards(ToolContext.empty())).isEmpty();
        assertThat(HookRegistryAccess.activeSkillGuards(contextWith(runtime))).isEmpty();
        assertThat(HookRegistryAccess.hasActiveSkillGuards(contextWith(runtime))).isFalse();
        assertThat(HookRegistryAccess.activeSkillHooks(contextWith(runtime))).isEmpty();
    }

    @Test
    void activeSkillGuards_walksTheChainAndFollowsDeactivation() {
        SkillScopedHookRegistry outer = new SkillScopedHookRegistry(runtime, "deploy",
                SkillHookSet.builder().addPreTool(GUARD).build());
        SkillScopedHookRegistry inner = new SkillScopedHookRegistry(outer, "audit",
                SkillHookSet.builder().addPostTool(AUDIT).build());
        ToolContext context = contextWith(inner);

        assertThat(HookRegistryAccess.activeSkillGuards(context)).containsExactly("deploy");
        assertThat(HookRegistryAccess.hasActiveSkillGuards(context)).isTrue();
        assertThat(HookRegistryAccess.activeSkillHooks(context)).containsExactly("deploy", "audit");

        outer.deactivate();

        assertThat(HookRegistryAccess.hasActiveSkillGuards(context)).isFalse();
        assertThat(HookRegistryAccess.activeSkillHooks(context)).containsExactly("audit");
    }

    @Test
    void backgroundRefusal_namesTheSkillsAndTheWayForward() {
        assertThat(HookRegistryAccess.backgroundRefusal(List.of("deploy", "audit"))).contains("'deploy', 'audit'")
                .contains("guard hooks").contains("foreground");
    }

    @Test
    void backgroundTaskRefusal_parallelsTheWorkflowWordingAndSaysWhatOutlivesTheSkill() {
        final String workflow = HookRegistryAccess.backgroundRefusal(List.of("deploy", "audit"));
        final String task = HookRegistryAccess.backgroundTaskRefusal(List.of("deploy", "audit"));

        // One opening for every tool that refuses background work under a guard skill.
        final String opening = "Background mode is not available here: skill 'deploy', 'audit' has guard hooks active";
        assertThat(workflow).startsWith(opening);
        assertThat(task).startsWith(opening).contains("background subagent").contains("once the skill returns")
                .contains("foreground").contains("run_in_background");
    }
}
