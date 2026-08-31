package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.CompletionReason;

@DisplayName("StepOutcome — transcript-free cache value")
class StepOutcomeTest {

    private static StepOutcome.Builder base() {
        return StepOutcome.builder().text("answer").completionReason(CompletionReason.COMPLETED).inputHash("h1")
                .structureFingerprint("fp1");
    }

    @Test
    @DisplayName("builder captures all fields; structured defaults to empty")
    void buildFull() {
        final StepOutcome o = base().totalTokens(1200).costMicros(340).structured(Map.of("score", 5)).build();

        assertThat(o.text()).isEqualTo("answer");
        assertThat(o.totalTokens()).isEqualTo(1200);
        assertThat(o.costMicros()).isEqualTo(340);
        assertThat(o.completionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(o.inputHash()).isEqualTo("h1");
        assertThat(o.structured()).get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("score", 5);
    }

    @Test
    @DisplayName("no structured output → empty Optional; tokens/cost default to 0")
    void defaults() {
        final StepOutcome o = base().build();

        assertThat(o.structured()).isEmpty();
        assertThat(o.totalTokens()).isZero();
        assertThat(o.costMicros()).isZero();
    }

    @Test
    @DisplayName("structured is defensively copied (mutating the source afterwards does not leak in)")
    void defensiveCopy() {
        final Map<String, Object> src = new HashMap<>();
        src.put("a", 1);
        final StepOutcome o = base().structured(src).build();

        src.put("b", 2);

        assertThat(o.structured()).get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("a");
    }

    @Test
    @DisplayName("required fields (text, completionReason, inputHash, structureFingerprint) are non-null")
    void requiredFields() {
        assertThatThrownBy(() -> StepOutcome.builder().completionReason(CompletionReason.COMPLETED).inputHash("h")
                .structureFingerprint("fp").build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StepOutcome.builder().text("t").inputHash("h").structureFingerprint("fp").build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StepOutcome.builder().text("t").completionReason(CompletionReason.COMPLETED)
                .structureFingerprint("fp").build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StepOutcome.builder().text("t").completionReason(CompletionReason.COMPLETED)
                .inputHash("h").build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("equals/hashCode cover all fields")
    void equality() {
        assertThat(base().totalTokens(10).build()).isEqualTo(base().totalTokens(10).build())
                .hasSameHashCodeAs(base().totalTokens(10).build());
        assertThat(base().totalTokens(10).build()).isNotEqualTo(base().totalTokens(11).build());
    }

    @Test
    @DisplayName("negative totalTokens/costMicros are rejected — a replay must never decrement the budget counters")
    void negativeAccountingRejected() {
        assertThatThrownBy(() -> base().totalTokens(-1).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalTokens");
        assertThatThrownBy(() -> base().costMicros(-1).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("costMicros");
    }
}
