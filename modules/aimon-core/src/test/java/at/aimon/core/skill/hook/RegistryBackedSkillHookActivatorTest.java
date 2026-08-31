package at.aimon.core.skill.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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

/** Unit tests for {@link RegistryBackedSkillHookActivator}. */
class RegistryBackedSkillHookActivatorTest {

    private static Skill skillWith(SkillHookSet hooks) {
        SkillMetadata metadata = SkillMetadata.builder().name("s").description("d").hooks(hooks).build();
        return Skill.builder().name("s").metadata(metadata).content(SkillContent.of("body")).build();
    }

    @Test
    void constructor_nullRegistry_throws() {
        assertThatThrownBy(() -> new RegistryBackedSkillHookActivator(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void activate_emptyHooks_returnsEmptyScopeAndDoesNotTouchRegistry() {
        HookRegistry registry = mock(HookRegistry.class);
        SkillHookActivator activator = new RegistryBackedSkillHookActivator(registry);

        SkillHookScope scope = activator.activate(skillWith(SkillHookSet.empty()));

        assertThat(scope).isSameAs(SkillHookScope.EMPTY);
        verifyNoMoreInteractions(registry);
    }

    @Test
    void activate_registersAllHookTypes_andCloseUnregistersInLifoOrder() {
        OnStartHook onStart = ctx -> HookResult.success();
        PreToolHook preTool = ctx -> HookResult.success();
        PostToolHook postTool = ctx -> HookResult.success();
        OnStopHook onStop = ctx -> HookResult.success();

        SkillHookSet hooks = SkillHookSet.builder().addOnStart(onStart).addPreTool(preTool).addPostTool(postTool)
                .addOnStop(onStop).build();

        HookRegistry registry = mock(HookRegistry.class);
        SkillHookActivator activator = new RegistryBackedSkillHookActivator(registry);

        SkillHookScope scope = activator.activate(skillWith(hooks));

        InOrder registerOrder = inOrder(registry);
        registerOrder.verify(registry).register(HookEventType.ON_START, onStart);
        registerOrder.verify(registry).register(HookEventType.PRE_TOOL, preTool);
        registerOrder.verify(registry).register(HookEventType.POST_TOOL, postTool);
        registerOrder.verify(registry).register(HookEventType.ON_STOP, onStop);

        scope.close();

        // LIFO unregister: stop -> postTool -> preTool -> onStart
        InOrder unregisterOrder = inOrder(registry);
        unregisterOrder.verify(registry).unregister(HookEventType.ON_STOP, onStop);
        unregisterOrder.verify(registry).unregister(HookEventType.POST_TOOL, postTool);
        unregisterOrder.verify(registry).unregister(HookEventType.PRE_TOOL, preTool);
        unregisterOrder.verify(registry).unregister(HookEventType.ON_START, onStart);
    }

    @Test
    void scopeClose_isIdempotent() {
        OnStartHook onStart = ctx -> HookResult.success();
        SkillHookSet hooks = SkillHookSet.builder().addOnStart(onStart).build();
        HookRegistry registry = mock(HookRegistry.class);

        SkillHookScope scope = new RegistryBackedSkillHookActivator(registry).activate(skillWith(hooks));

        scope.close();
        scope.close();
        scope.close();

        verify(registry, times(1)).unregister(HookEventType.ON_START, onStart);
    }

    @Test
    void activate_partialFailure_rollsBackPreviousRegistrationsAndPropagates() {
        OnStartHook onStart = ctx -> HookResult.success();
        PreToolHook preTool = ctx -> HookResult.success();
        PostToolHook postTool = ctx -> HookResult.success();
        OnStopHook onStop = ctx -> HookResult.success();

        SkillHookSet hooks = SkillHookSet.builder().addOnStart(onStart).addPreTool(preTool).addPostTool(postTool)
                .addOnStop(onStop).build();

        HookRegistry registry = mock(HookRegistry.class);
        // Fail on the third register call (postTool)
        doThrow(new IllegalStateException("registry full")).when(registry).register(eq(HookEventType.POST_TOOL), any());

        SkillHookActivator activator = new RegistryBackedSkillHookActivator(registry);

        assertThatThrownBy(() -> activator.activate(skillWith(hooks))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registry full");

        // The successful registrations (onStart, preTool) must be rolled back, LIFO.
        InOrder rollback = inOrder(registry);
        rollback.verify(registry).register(HookEventType.ON_START, onStart);
        rollback.verify(registry).register(HookEventType.PRE_TOOL, preTool);
        rollback.verify(registry).register(HookEventType.POST_TOOL, postTool);
        rollback.verify(registry).unregister(HookEventType.PRE_TOOL, preTool);
        rollback.verify(registry).unregister(HookEventType.ON_START, onStart);

        // OnStop was never reached; postTool's registration threw before adding to the teardown list.
        verify(registry, never()).register(eq(HookEventType.ON_STOP), any());
        verify(registry, never()).unregister(eq(HookEventType.POST_TOOL), any());
        verify(registry, never()).unregister(eq(HookEventType.ON_STOP), any());
    }

    @Test
    void scopeClose_unregisterThrows_doesNotPreventOtherTeardowns() {
        OnStartHook onStart = ctx -> HookResult.success();
        OnStopHook onStop = ctx -> HookResult.success();
        SkillHookSet hooks = SkillHookSet.builder().addOnStart(onStart).addOnStop(onStop).build();

        HookRegistry registry = mock(HookRegistry.class);
        // First teardown to run is onStop (LIFO); make it throw and verify we still tear down onStart.
        doThrow(new RuntimeException("boom")).when(registry).unregister(HookEventType.ON_STOP, onStop);

        SkillHookScope scope = new RegistryBackedSkillHookActivator(registry).activate(skillWith(hooks));

        scope.close(); // must not propagate

        verify(registry).unregister(HookEventType.ON_STOP, onStop);
        verify(registry).unregister(HookEventType.ON_START, onStart);
    }

    @Test
    void activate_nullSkill_throws() {
        SkillHookActivator activator = new RegistryBackedSkillHookActivator(mock(HookRegistry.class));

        assertThatThrownBy(() -> activator.activate(null)).isInstanceOf(NullPointerException.class);
    }
}
