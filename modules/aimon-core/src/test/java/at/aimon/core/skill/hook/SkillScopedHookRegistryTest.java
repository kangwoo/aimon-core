package at.aimon.core.skill.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;

/** Unit tests for {@link SkillScopedHookRegistry}. */
class SkillScopedHookRegistryTest {

    private final PreToolHook runtimeHook = ctx -> HookResult.success();
    private final PreToolHook skillGuard = ctx -> HookResult.success();
    private final PostToolHook skillAudit = ctx -> HookResult.success();
    private final DefaultHookRegistry base = new DefaultHookRegistry();

    private SkillScopedHookRegistry view(String skill, SkillHookSet layer) {
        return new SkillScopedHookRegistry(base, skill, layer);
    }

    @Test
    void getHooks_returnsBaseHooksThenTheSkillLayer() {
        base.register(HookEventType.PRE_TOOL, runtimeHook);
        SkillScopedHookRegistry view = view("s",
                SkillHookSet.builder().addPreTool(skillGuard).addPostTool(skillAudit).build());

        assertThat(view.getHooks(HookEventType.PRE_TOOL)).containsExactly(runtimeHook, skillGuard);
        assertThat(view.getHooks(HookEventType.POST_TOOL)).containsExactly(skillAudit);
        assertThat(view.getHooks(HookEventType.ON_SESSION_START)).isEmpty();
        // The base never learns about the layer.
        assertThat(base.getHooks(HookEventType.PRE_TOOL)).containsExactly(runtimeHook);
        assertThat(base.getHooks(HookEventType.POST_TOOL)).isEmpty();
    }

    @Test
    void deactivate_leavesTheBaseAlone() {
        base.register(HookEventType.PRE_TOOL, runtimeHook);
        SkillScopedHookRegistry view = view("s", SkillHookSet.builder().addPreTool(skillGuard).build());

        view.deactivate();
        view.deactivate();

        assertThat(view.isActive()).isFalse();
        assertThat(view.getHooks(HookEventType.PRE_TOOL)).containsExactly(runtimeHook);
        assertThat(view.activeSkills()).isEmpty();
        assertThat(view.activeGuardSkills()).isEmpty();
    }

    @Test
    void getHooks_readsTheBaseEveryTime_soLateRegistrationsAndReloadsShowThrough() {
        SkillScopedHookRegistry view = view("s", SkillHookSet.builder().addPreTool(skillGuard).build());

        base.register(HookEventType.PRE_TOOL, runtimeHook);
        assertThat(view.getHooks(HookEventType.PRE_TOOL)).containsExactly(runtimeHook, skillGuard);

        base.clearAll();
        assertThat(view.getHooks(HookEventType.PRE_TOOL)).containsExactly(skillGuard);
    }

    @Test
    void writes_goToTheBase_andNeverToTheLayer() {
        SkillScopedHookRegistry view = view("s", SkillHookSet.builder().addPreTool(skillGuard).build());
        OnStopHook registeredInFork = ctx -> HookResult.success();

        view.register(HookEventType.ON_STOP, registeredInFork);
        assertThat(base.getHooks(HookEventType.ON_STOP)).containsExactly(registeredInFork);

        assertThat(view.unregister(HookEventType.ON_STOP, registeredInFork)).isTrue();
        assertThat(base.getHooks(HookEventType.ON_STOP)).isEmpty();
        // A skill hook is not something the fork can unregister.
        assertThat(view.unregister(HookEventType.PRE_TOOL, skillGuard)).isFalse();

        base.register(HookEventType.PRE_TOOL, runtimeHook);
        view.clearAll();
        assertThat(base.isEmpty()).isTrue();
        assertThat(view.getHooks(HookEventType.PRE_TOOL)).containsExactly(skillGuard);
    }

    @Test
    void isEmpty_onlyWhenBaseAndActiveLayerAreBothEmpty() {
        SkillScopedHookRegistry view = view("s", SkillHookSet.builder().addPreTool(skillGuard).build());

        assertThat(view.isEmpty()).isFalse();
        view.deactivate();
        assertThat(view.isEmpty()).isTrue();
        base.register(HookEventType.PRE_TOOL, runtimeHook);
        assertThat(view.isEmpty()).isFalse();
    }

    @Test
    void activeGuardSkills_namesOnlyActiveLayersThatCanVeto_throughTheWholeChain() {
        SkillScopedHookRegistry guarded = view("deploy", SkillHookSet.builder().addPreTool(skillGuard).build());
        SkillScopedHookRegistry observing = new SkillScopedHookRegistry(guarded, "audit",
                SkillHookSet.builder().addPostTool(skillAudit).build());

        assertThat(observing.activeSkills()).containsExactly("deploy", "audit");
        // The inner layer only observes, but the outer one beneath it guards.
        assertThat(observing.activeGuardSkills()).containsExactly("deploy");

        guarded.deactivate();

        assertThat(observing.activeSkills()).containsExactly("audit");
        assertThat(observing.activeGuardSkills()).isEmpty();
    }

    @Test
    void constructor_nullArguments_throw() {
        assertThatThrownBy(() -> new SkillScopedHookRegistry(null, "s", SkillHookSet.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SkillScopedHookRegistry(base, null, SkillHookSet.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SkillScopedHookRegistry(base, "s", null)).isInstanceOf(NullPointerException.class);
    }
}
