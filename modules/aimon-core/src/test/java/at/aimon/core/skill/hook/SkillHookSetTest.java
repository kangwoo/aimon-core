package at.aimon.core.skill.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;

/** Unit tests for {@link SkillHookSet}. */
class SkillHookSetTest {

    @Test
    void emptySingleton_isReusedAndIsEmpty() {
        SkillHookSet a = SkillHookSet.empty();
        SkillHookSet b = SkillHookSet.empty();

        assertThat(a).isSameAs(b);
        assertThat(a.isEmpty()).isTrue();
        assertThat(a.getOnStartHooks()).isEmpty();
        assertThat(a.getPreToolHooks()).isEmpty();
        assertThat(a.getPostToolHooks()).isEmpty();
        assertThat(a.getOnStopHooks()).isEmpty();
    }

    @Test
    void builder_acceptsEachHookTypeAndPreservesOrder() {
        OnStartHook on1 = ctx -> HookResult.success();
        OnStartHook on2 = ctx -> HookResult.success();
        PreToolHook pre = ctx -> HookResult.success();
        PostToolHook post = ctx -> HookResult.success();
        OnStopHook stop = ctx -> HookResult.success();

        SkillHookSet set = SkillHookSet.builder().addOnStart(on1).addOnStart(on2).addPreTool(pre).addPostTool(post)
                .addOnStop(stop).build();

        assertThat(set.isEmpty()).isFalse();
        assertThat(set.getOnStartHooks()).containsExactly(on1, on2);
        assertThat(set.getPreToolHooks()).containsExactly(pre);
        assertThat(set.getPostToolHooks()).containsExactly(post);
        assertThat(set.getOnStopHooks()).containsExactly(stop);
    }

    @Test
    void builder_returnedListsAreImmutable() {
        SkillHookSet set = SkillHookSet.builder().addOnStart(ctx -> HookResult.success()).build();

        assertThatThrownBy(() -> set.getOnStartHooks().add(ctx -> HookResult.success()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builder_rejectsNullHooks() {
        SkillHookSet.Builder b = SkillHookSet.builder();
        assertThatThrownBy(() -> b.addOnStart(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.addPreTool(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.addPostTool(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> b.addOnStop(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalsAndHashCode_basedOnHookListContents() {
        OnStartHook on = ctx -> HookResult.success();

        SkillHookSet a = SkillHookSet.builder().addOnStart(on).build();
        SkillHookSet b = SkillHookSet.builder().addOnStart(on).build();
        SkillHookSet c = SkillHookSet.builder().build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toString_summarisesHookCounts() {
        SkillHookSet set = SkillHookSet.builder().addOnStart(ctx -> HookResult.success())
                .addPreTool(ctx -> HookResult.success()).build();

        assertThat(set.toString()).contains("onStart=1").contains("preTool=1").contains("postTool=0")
                .contains("onStop=0");
    }
}
