package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.budget.ExecutionDeadline;

@DisplayName("SubagentExecutionPolicy, its resolver and ExecutionDeadline")
class SubagentExecutionPolicyTest {

    @Test
    @DisplayName("the capabilities a policy uses are exactly the ones it sets")
    void capabilitiesFollowWhatIsSet() {
        assertThat(SubagentExecutionPolicy.builder("r").build().getCapabilities()).isEmpty();
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("r").maxToolCalls(3)
                .maxWallClock(Duration.ofSeconds(1)).inheritContextEngine(true).build();
        assertThat(policy.getCapabilities()).containsExactlyInAnyOrder(SubagentPolicyCapability.MAX_TOOL_CALLS,
                SubagentPolicyCapability.MAX_WALL_CLOCK, SubagentPolicyCapability.INHERIT_CONTEXT_ENGINE);
        assertThat(policy.unsupportedBy(Set.of(SubagentPolicyCapability.MAX_WALL_CLOCK))).containsExactlyInAnyOrder(
                SubagentPolicyCapability.MAX_TOOL_CALLS, SubagentPolicyCapability.INHERIT_CONTEXT_ENGINE);
        assertThat(policy.unsupportedBy(EnumSet.allOf(SubagentPolicyCapability.class))).isEmpty();
    }

    @Test
    @DisplayName("toBudget carries iterations, tokens and the threshold, not the wall clock")
    void toBudget() {
        final ExecutionBudget budget = SubagentExecutionPolicy.builder("r").maxIterations(8).maxTokens(1000)
                .compactionTokenThreshold(500).maxWallClock(Duration.ofSeconds(9)).build().toBudget();
        assertThat(budget.getMaxIterations()).contains(8);
        assertThat(budget.getMaxTokens()).contains(1000);
        assertThat(budget.getCompactionTokenThreshold()).contains(500);
        assertThat(budget.getMaxWallClockDuration()).isEmpty();
    }

    @Test
    @DisplayName("invalid policies are refused when built, before any execution")
    void invalidPolicies() {
        assertThatIllegalArgumentException().isThrownBy(() -> SubagentExecutionPolicy.builder(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> SubagentExecutionPolicy.builder("r").maxToolCalls(0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubagentExecutionPolicy.builder("r").maxWallClock(Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> SubagentExecutionPolicy.builder("r").maxTokens(100).compactionTokenThreshold(100).build())
                .withMessageContaining("below maxTokens");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubagentExecutionPolicy.builder("r").exitGracePeriod(Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("the map resolver answers by name and nothing else")
    void mapResolver() {
        final SubagentExecutionPolicy policy = SubagentExecutionPolicy.builder("r").maxIterations(1).build();
        final SubagentExecutionPolicyResolver resolver = SubagentExecutionPolicyResolver.of(Map.of("v", policy));
        assertThat(resolver.resolve("v")).containsSame(policy);
        assertThat(resolver.resolve("other")).isEmpty();
        assertThat(SubagentExecutionPolicyResolver.none().resolve("v")).isEmpty();
    }

    @Test
    @DisplayName("a deadline reads its own clock and the earlier of two wins")
    void deadline() {
        final AtomicLong now = new AtomicLong(1_000);
        // One clock instance: deadlines compare only when they read the same one.
        final LongSupplier clock = now::get;
        final ExecutionDeadline late = ExecutionDeadline.after(Duration.ofSeconds(10), clock);
        final ExecutionDeadline early = ExecutionDeadline.after(Duration.ofSeconds(3), clock);
        assertThat(late.earlierOf(early)).isSameAs(early);
        assertThat(early.earlierOf(late)).isSameAs(early);
        assertThat(late.earlierOf(null)).isSameAs(late);
        assertThat(early.isExpired()).isFalse();
        now.addAndGet(Duration.ofSeconds(3).toNanos());
        assertThat(early.isExpired()).isTrue();
        assertThat(early.remaining()).isZero();
        assertThat(late.remaining()).isEqualTo(Duration.ofSeconds(7));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> late.earlierOf(ExecutionDeadline.after(Duration.ofSeconds(1))));
    }
}
