package at.aimon.core.skill.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.hook.SkillHookSet;

/** Unit tests for {@link RuleBasedSkillInvocationPolicy}. */
class RuleBasedSkillInvocationPolicyTest {

    @Test
    void denyTakesPrecedenceOverAllow() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().addAllowPattern("commit")
                .addDenyPattern("commit").build();

        assertThat(policy.check(reqFor("commit"))).isEqualTo(SkillInvocationDecision.DENY);
    }

    @Test
    void allowMatchProducesAllow() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().addAllowPattern("commit").build();

        assertThat(policy.check(reqFor("commit"))).isEqualTo(SkillInvocationDecision.ALLOW);
    }

    @Test
    void unmatchedFallsBackToDefaultDecision_DefaultIsDeny() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().safeByDefault(false).build();

        assertThat(policy.check(reqFor("anything"))).isEqualTo(SkillInvocationDecision.DENY);
    }

    @Test
    void unmatchedFallsBackToConfigurableDefaultDecision() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().safeByDefault(false)
                .defaultDecision(SkillInvocationDecision.ASK).build();

        assertThat(policy.check(reqFor("anything"))).isEqualTo(SkillInvocationDecision.ASK);
    }

    @Test
    void safeByDefault_AllowsInlineSkillsWithoutHooks() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().build();

        assertThat(policy.check(reqFor(inlineSkillWithoutHooks("commit")))).isEqualTo(SkillInvocationDecision.ALLOW);
    }

    @Test
    void safeByDefault_DoesNotAllowSkillsWithHooks() {
        final Skill withHooks = Skill.builder().name("hooked")
                .metadata(SkillMetadata.builder().name("hooked").description("d").executionMode(ExecutionMode.INLINE)
                        .hooks(SkillHookSet.builder().addPreTool(noopHook()).build()).build())
                .content(SkillContent.of("body")).build();

        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().build();

        assertThat(policy.check(reqFor(withHooks))).isEqualTo(SkillInvocationDecision.DENY);
    }

    @Test
    void safeByDefault_DoesNotAllowForkSkills() {
        final Skill fork = Skill
                .builder().name("fork-skill").metadata(SkillMetadata.builder().name("fork-skill").description("d")
                        .executionMode(ExecutionMode.FORK).forkAgentName("worker").build())
                .content(SkillContent.of("body")).build();

        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().build();

        assertThat(policy.check(reqFor(fork))).isEqualTo(SkillInvocationDecision.DENY);
    }

    @Test
    void safeByDefault_DisableForcesExplicitAllow() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().safeByDefault(false).build();

        assertThat(policy.check(reqFor(inlineSkillWithoutHooks("safe-skill")))).isEqualTo(SkillInvocationDecision.DENY);
    }

    @Test
    void wildcardStarMatchesAnyName() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().safeByDefault(false)
                .addAllowPattern("*").build();

        assertThat(policy.check(reqFor("anything-goes"))).isEqualTo(SkillInvocationDecision.ALLOW);
    }

    @Test
    void wildcardStarPrefixMatchesNameWithThatPrefix() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().safeByDefault(false)
                .addDenyPattern("wiki-*").build();

        assertThat(policy.check(reqFor("wiki-maintenance"))).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(policy.check(reqFor("commit"))).isEqualTo(SkillInvocationDecision.DENY);  // safeByDefault off
    }

    @Test
    void wildcardStarMatchesNamespacedSkill() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().safeByDefault(false)
                .addAllowPattern("*:prod").build();

        assertThat(policy.check(reqFor("ops:prod"))).isEqualTo(SkillInvocationDecision.ALLOW);
        assertThat(policy.check(reqFor("ops:dev"))).isEqualTo(SkillInvocationDecision.DENY);
    }

    @Test
    void questionMarkMatchesSingleCharacter() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().safeByDefault(false)
                .addAllowPattern("v?").build();

        assertThat(policy.check(reqFor("v1"))).isEqualTo(SkillInvocationDecision.ALLOW);
        assertThat(policy.check(reqFor("v12"))).isEqualTo(SkillInvocationDecision.DENY);
    }

    @Test
    void blankPatternRejected() {
        assertThatThrownBy(() -> RuleBasedSkillInvocationPolicy.builder().addDenyPattern("   ").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
    }

    @Test
    void nullRequestRejected() {
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().build();
        assertThatThrownBy(() -> policy.check(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildWithListsCopiesInputs() {
        final List<String> mutableDeny = new java.util.ArrayList<>(List.of("a"));
        final SkillInvocationPolicy policy = RuleBasedSkillInvocationPolicy.builder().safeByDefault(false)
                .denyPatterns(mutableDeny).build();
        // Mutating the source list after build must not affect the policy.
        mutableDeny.add("b");

        assertThat(policy.check(reqFor("a"))).isEqualTo(SkillInvocationDecision.DENY);
        assertThat(policy.check(reqFor("b"))).isEqualTo(SkillInvocationDecision.DENY);  // default is DENY
    }

    private static SkillInvocationRequest reqFor(String name) {
        return reqFor(inlineSkillWithoutHooks(name));
    }

    private static SkillInvocationRequest reqFor(Skill skill) {
        return SkillInvocationRequest.builder().skill(skill).build();
    }

    private static Skill inlineSkillWithoutHooks(String name) {
        return Skill.builder().name(name).metadata(SkillMetadata.builder().name(name).description("desc").build())
                .content(SkillContent.of("body")).build();
    }

    private static PreToolHook noopHook() {
        return ctx -> HookResult.success();
    }
}
