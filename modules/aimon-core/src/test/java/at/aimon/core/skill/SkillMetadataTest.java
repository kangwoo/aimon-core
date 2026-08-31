package at.aimon.core.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.hook.SkillHookSet;

/** Unit tests for {@link SkillMetadata}, focused on the {@code argumentNames} extension. */
class SkillMetadataTest {

    private static SkillMetadata.Builder baseBuilder() {
        return SkillMetadata.builder().name("sample").description("desc");
    }

    @Test
    void argumentNames_DefaultIsEmpty() {
        final SkillMetadata md = baseBuilder().build();

        assertThat(md.getArgumentNames()).isEmpty();
    }

    @Test
    void argumentNames_NullDefaultsToEmpty() {
        final SkillMetadata md = baseBuilder().argumentNames(null).build();

        assertThat(md.getArgumentNames()).isEmpty();
    }

    @Test
    void argumentNames_PreservesOrder() {
        final SkillMetadata md = baseBuilder().argumentNames(List.of("message", "title", "body")).build();

        assertThat(md.getArgumentNames()).containsExactly("message", "title", "body");
    }

    @Test
    void argumentNames_DuplicateThrows() {
        assertThatThrownBy(() -> baseBuilder().argumentNames(List.of("a", "b", "a")).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate argument name")
                .hasMessageContaining("a");
    }

    @Test
    void argumentNames_NullEntryThrows() {
        final List<String> names = Arrays.asList("a", null);

        assertThatThrownBy(() -> baseBuilder().argumentNames(names).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
    }

    @Test
    void argumentNames_BlankEntryThrows() {
        assertThatThrownBy(() -> baseBuilder().argumentNames(List.of("a", "  ")).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
    }

    @Test
    void argumentNames_ResultIsUnmodifiable() {
        final SkillMetadata md = baseBuilder().argumentNames(List.of("x")).build();

        assertThatThrownBy(() -> md.getArgumentNames().add("y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void argumentNames_DefensiveCopy() {
        final List<String> source = new ArrayList<>(List.of("a", "b"));
        final SkillMetadata md = baseBuilder().argumentNames(source).build();

        source.add("c");

        assertThat(md.getArgumentNames()).containsExactly("a", "b");
    }

    @Test
    void equalsAndHashCode_ConsiderArgumentNames() {
        final SkillMetadata a = baseBuilder().argumentNames(List.of("x")).build();
        final SkillMetadata b = baseBuilder().argumentNames(List.of("x")).build();
        final SkillMetadata c = baseBuilder().argumentNames(List.of("y")).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toString_ContainsArgumentNames() {
        final SkillMetadata md = baseBuilder().argumentNames(List.of("foo", "bar")).build();

        assertThat(md.toString()).contains("argumentNames").contains("foo").contains("bar");
    }

    @Test
    void invokePolicy_DefaultIsUserFalseModelTrue() {
        final SkillMetadata md = baseBuilder().build();

        assertThat(md.getInvokePolicy()).isEqualTo(InvokePolicy.defaults());
        assertThat(md.getInvokePolicy().isUserInvocable()).isFalse();
        assertThat(md.getInvokePolicy().isModelInvocable()).isTrue();
    }

    @Test
    void invokePolicy_NullDefaultsToInvokePolicyDefaults() {
        final SkillMetadata md = baseBuilder().invokePolicy(null).build();

        assertThat(md.getInvokePolicy()).isEqualTo(InvokePolicy.defaults());
    }

    @Test
    void invokePolicy_CustomValueIsRetained() {
        final SkillMetadata md = baseBuilder().invokePolicy(InvokePolicy.of(true, false)).build();

        assertThat(md.getInvokePolicy()).isEqualTo(InvokePolicy.of(true, false));
    }

    @Test
    void equalsAndHashCode_ConsiderInvokePolicy() {
        final SkillMetadata a = baseBuilder().invokePolicy(InvokePolicy.of(true, true)).build();
        final SkillMetadata b = baseBuilder().invokePolicy(InvokePolicy.of(true, true)).build();
        final SkillMetadata c = baseBuilder().invokePolicy(InvokePolicy.of(false, true)).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toString_ContainsInvokePolicy() {
        final SkillMetadata md = baseBuilder().invokePolicy(InvokePolicy.of(true, false)).build();

        assertThat(md.toString()).contains("invokePolicy").contains("user=true").contains("model=false");
    }

    @Test
    void maxIterations_DefaultIsHundred() {
        final SkillMetadata md = baseBuilder().build();

        assertThat(md.getMaxIterations()).isEqualTo(SkillMetadata.DEFAULT_MAX_ITERATIONS);
        assertThat(md.getMaxIterations()).isEqualTo(100);
    }

    @Test
    void maxIterations_NullDefaultsToHundred() {
        final SkillMetadata md = baseBuilder().maxIterations(null).build();

        assertThat(md.getMaxIterations()).isEqualTo(SkillMetadata.DEFAULT_MAX_ITERATIONS);
    }

    @Test
    void maxIterations_CustomValueIsRetained() {
        final SkillMetadata md = baseBuilder().maxIterations(42).build();

        assertThat(md.getMaxIterations()).isEqualTo(42);
    }

    @Test
    void maxIterations_ZeroThrows() {
        assertThatThrownBy(() -> baseBuilder().maxIterations(0).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIterations").hasMessageContaining("positive");
    }

    @Test
    void maxIterations_NegativeThrows() {
        assertThatThrownBy(() -> baseBuilder().maxIterations(-3).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIterations").hasMessageContaining("positive");
    }

    @Test
    void equalsAndHashCode_ConsiderMaxIterations() {
        final SkillMetadata a = baseBuilder().maxIterations(50).build();
        final SkillMetadata b = baseBuilder().maxIterations(50).build();
        final SkillMetadata c = baseBuilder().maxIterations(60).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toString_ContainsMaxIterations() {
        final SkillMetadata md = baseBuilder().maxIterations(75).build();

        assertThat(md.toString()).contains("maxIterations=75");
    }

    @Test
    void executionMode_DefaultIsInline() {
        final SkillMetadata md = baseBuilder().build();

        assertThat(md.getExecutionMode()).isEqualTo(ExecutionMode.INLINE);
        assertThat(md.getForkAgentName()).isNull();
    }

    @Test
    void executionMode_NullDefaultsToInline() {
        final SkillMetadata md = baseBuilder().executionMode(null).build();

        assertThat(md.getExecutionMode()).isEqualTo(ExecutionMode.INLINE);
    }

    @Test
    void executionMode_ExplicitInlineWithNoAgent() {
        final SkillMetadata md = baseBuilder().executionMode(ExecutionMode.INLINE).build();

        assertThat(md.getExecutionMode()).isEqualTo(ExecutionMode.INLINE);
        assertThat(md.getForkAgentName()).isNull();
    }

    @Test
    void executionMode_ForkWithAgentRetainsName() {
        final SkillMetadata md = baseBuilder().executionMode(ExecutionMode.FORK).forkAgentName("code-reviewer").build();

        assertThat(md.getExecutionMode()).isEqualTo(ExecutionMode.FORK);
        assertThat(md.getForkAgentName()).isEqualTo("code-reviewer");
    }

    @Test
    void executionMode_ForkAgentNameIsTrimmed() {
        final SkillMetadata md = baseBuilder().executionMode(ExecutionMode.FORK).forkAgentName("  reviewer  ").build();

        assertThat(md.getForkAgentName()).isEqualTo("reviewer");
    }

    @Test
    void executionMode_ForkWithoutAgentThrows() {
        assertThatThrownBy(() -> baseBuilder().executionMode(ExecutionMode.FORK).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("execution.agent")
                .hasMessageContaining("fork");
    }

    @Test
    void executionMode_ForkWithBlankAgentThrows() {
        assertThatThrownBy(() -> baseBuilder().executionMode(ExecutionMode.FORK).forkAgentName("   ").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("execution.agent");
    }

    @Test
    void executionMode_InlineWithAgentThrows() {
        assertThatThrownBy(() -> baseBuilder().executionMode(ExecutionMode.INLINE).forkAgentName("foo").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("execution.agent")
                .hasMessageContaining("inline");
    }

    @Test
    void executionMode_InlineWithBlankAgentIsAccepted() {
        // Blank == "not specified", which is fine for INLINE.
        final SkillMetadata md = baseBuilder().executionMode(ExecutionMode.INLINE).forkAgentName("   ").build();

        assertThat(md.getForkAgentName()).isNull();
    }

    @Test
    void equalsAndHashCode_ConsiderExecutionMode() {
        final SkillMetadata a = baseBuilder().executionMode(ExecutionMode.FORK).forkAgentName("agent-x").build();
        final SkillMetadata b = baseBuilder().executionMode(ExecutionMode.FORK).forkAgentName("agent-x").build();
        final SkillMetadata c = baseBuilder().executionMode(ExecutionMode.FORK).forkAgentName("agent-y").build();
        final SkillMetadata d = baseBuilder().executionMode(ExecutionMode.INLINE).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(d);
    }

    @Test
    void toString_ContainsExecutionMode() {
        final SkillMetadata md = baseBuilder().executionMode(ExecutionMode.FORK).forkAgentName("rev").build();

        assertThat(md.toString()).contains("executionMode=FORK").contains("forkAgentName='rev'");
    }

    @Test
    void hooks_DefaultIsEmptyHookSet() {
        final SkillMetadata md = baseBuilder().build();

        assertThat(md.getHooks()).isNotNull();
        assertThat(md.getHooks().isEmpty()).isTrue();
    }

    @Test
    void hooks_NullDefaultsToEmptyHookSet() {
        final SkillMetadata md = baseBuilder().hooks(null).build();

        assertThat(md.getHooks()).isSameAs(SkillHookSet.empty());
    }

    @Test
    void hooks_CustomValueIsRetained() {
        final SkillHookSet hooks = SkillHookSet.builder().addOnStart(ctx -> HookResult.success()).build();

        final SkillMetadata md = baseBuilder().hooks(hooks).build();

        assertThat(md.getHooks()).isSameAs(hooks);
    }

    @Test
    void equalsAndHashCode_ConsiderHooks() {
        final SkillHookSet hooks = SkillHookSet.builder().addOnStart(ctx -> HookResult.success()).build();

        final SkillMetadata a = baseBuilder().hooks(hooks).build();
        final SkillMetadata b = baseBuilder().hooks(hooks).build();
        final SkillMetadata c = baseBuilder().build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
