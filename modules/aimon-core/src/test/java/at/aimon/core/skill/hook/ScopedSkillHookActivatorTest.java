package at.aimon.core.skill.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.tools.ToolContextKeys;

/** Unit tests for {@link ScopedSkillHookActivator}. */
class ScopedSkillHookActivatorTest {

    private static Skill skillWith(String name, SkillHookSet hooks) {
        SkillMetadata metadata = SkillMetadata.builder().name(name).description("d").hooks(hooks).build();
        return Skill.builder().name(name).metadata(metadata).content(SkillContent.of("body")).build();
    }

    @Test
    void constructor_nullRegistry_throws() {
        assertThatThrownBy(() -> new ScopedSkillHookActivator(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void activate_emptyHooks_returnsEmptyScopeThatLayersNothing() {
        SkillHookScope scope = new ScopedSkillHookActivator(new DefaultHookRegistry())
                .activate(skillWith("s", SkillHookSet.empty()), ToolContext.empty());

        assertThat(scope).isSameAs(SkillHookScope.EMPTY);
        assertThat(scope.hookRegistry()).isEmpty();
    }

    @Test
    void activate_neverTouchesTheRuntimeRegistry() {
        // The regression EE-49 is about: another session of the same agent dispatches against this registry.
        OnStartHook onStart = ctx -> HookResult.success();
        PreToolHook preTool = ctx -> HookResult.success();
        PostToolHook postTool = ctx -> HookResult.success();
        OnStopHook onStop = ctx -> HookResult.success();
        SkillHookSet hooks = SkillHookSet.builder().addOnStart(onStart).addPreTool(preTool).addPostTool(postTool)
                .addOnStop(onStop).build();
        DefaultHookRegistry runtime = new DefaultHookRegistry();

        SkillHookScope scope = new ScopedSkillHookActivator(runtime).activate(skillWith("s", hooks),
                ToolContext.empty());

        assertThat(runtime.isEmpty()).isTrue();
        for (HookEventType<?> type : HookEventType.values()) {
            assertThat(runtime.getHooks(type)).as(type.name()).isEmpty();
        }
        HookRegistry forFork = scope.hookRegistry().orElseThrow();
        assertThat(forFork.getHooks(HookEventType.ON_START)).containsExactly(onStart);
        assertThat(forFork.getHooks(HookEventType.PRE_TOOL)).containsExactly(preTool);
        assertThat(forFork.getHooks(HookEventType.POST_TOOL)).containsExactly(postTool);
        assertThat(forFork.getHooks(HookEventType.ON_STOP)).containsExactly(onStop);

        scope.close();

        assertThat(runtime.isEmpty()).isTrue();
    }

    @Test
    void scopeClose_endsTheHooksForWhoeverStillHoldsTheRegistry_andIsIdempotent() {
        PreToolHook preTool = ctx -> HookResult.success();
        SkillHookScope scope = new ScopedSkillHookActivator(new DefaultHookRegistry())
                .activate(skillWith("s", SkillHookSet.builder().addPreTool(preTool).build()), ToolContext.empty());
        HookRegistry heldByABackgroundDescendant = scope.hookRegistry().orElseThrow();

        scope.close();
        scope.close();

        assertThat(heldByABackgroundDescendant.getHooks(HookEventType.PRE_TOOL)).isEmpty();
    }

    @Test
    void activate_layersOverTheRegistryInTheInvokingContext_soNestedSkillsStack() {
        PreToolHook outerGuard = ctx -> HookResult.success();
        PreToolHook innerGuard = ctx -> HookResult.success();
        PreToolHook runtimeHook = ctx -> HookResult.success();
        DefaultHookRegistry runtime = new DefaultHookRegistry();
        runtime.register(HookEventType.PRE_TOOL, runtimeHook);
        ScopedSkillHookActivator activator = new ScopedSkillHookActivator(runtime);

        SkillHookScope outer = activator.activate(
                skillWith("outer", SkillHookSet.builder().addPreTool(outerGuard).build()), ToolContext.empty());
        ToolContext insideOuterFork = ToolContext.builder()
                .put(ToolContextKeys.HOOK_REGISTRY, outer.hookRegistry().orElseThrow()).build();
        SkillHookScope inner = activator
                .activate(skillWith("inner", SkillHookSet.builder().addPreTool(innerGuard).build()), insideOuterFork);

        assertThat(inner.hookRegistry().orElseThrow().getHooks(HookEventType.PRE_TOOL)).containsExactly(runtimeHook,
                outerGuard, innerGuard);

        // The outer skill returning takes only its own layer away.
        outer.close();
        assertThat(inner.hookRegistry().orElseThrow().getHooks(HookEventType.PRE_TOOL)).containsExactly(runtimeHook,
                innerGuard);
    }

    @Test
    void activate_nullArguments_throw() {
        SkillHookActivator activator = new ScopedSkillHookActivator(new DefaultHookRegistry());

        assertThatThrownBy(() -> activator.activate(null, ToolContext.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> activator.activate(skillWith("s", SkillHookSet.empty()), null))
                .isInstanceOf(NullPointerException.class);
    }
}
