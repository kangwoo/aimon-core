package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.anthropic.models.messages.OutputConfig;

import at.aimon.core.llm.ReasoningEffort;

/**
 * Pins the four properties of the effort ladder that are <em>not</em> arbitrary.
 *
 * <p>
 * Two of the four budgets are the vendor's own published numbers and two are doublings of the floor; which is which is
 * documented on {@link AnthropicThinkingBudgets} rather than asserted here, because a test that pinned the arbitrary
 * numbers would only make them harder to change. What is pinned is monotonicity, the floor, the clamp and the loud
 * give-up.
 */
@DisplayName("AnthropicThinkingBudgets - the effort ladder, its floor and its clamp")
class AnthropicThinkingBudgetsTest {

    /** Large enough that no rung clamps, so the ladder itself is what is under test. */
    private static final int ROOMY_MAX_TOKENS = 64_000;

    @Test
    @DisplayName("the ladder is monotonic non-decreasing across all five rungs")
    void ladderIsMonotonic() {
        int previous = 0;
        for (ReasoningEffort effort : ReasoningEffort.values()) {
            final int budget = AnthropicThinkingBudgets.requestedBudget(effort, null);
            assertThat(budget).as("budget for %s", effort).isGreaterThanOrEqualTo(previous);
            previous = budget;
        }
        // The enum's declaration order is documented as load-bearing, so an inverted pair would silently invert the
        // caller's intent rather than fail anywhere else.
        assertThat(AnthropicThinkingBudgets.requestedBudget(ReasoningEffort.HIGH, null))
                .isGreaterThan(AnthropicThinkingBudgets.requestedBudget(ReasoningEffort.MINIMAL, null));
    }

    @Test
    @DisplayName("no rung ever falls below the API's 1024 floor")
    void everyRungClearsTheFloor() {
        for (ReasoningEffort effort : ReasoningEffort.values()) {
            final OptionalInt budget = AnthropicThinkingBudgets.budgetFor(effort, null, ROOMY_MAX_TOKENS);
            assertThat(budget).isPresent();
            assertThat(budget.getAsInt()).as("budget for %s", effort)
                    .isGreaterThanOrEqualTo(AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS);
        }
    }

    @Test
    @DisplayName("an absent effort resolves to the middle rung")
    void absentEffortTakesTheMiddleRung() {
        assertThat(AnthropicThinkingBudgets.requestedBudget(null, null))
                .isEqualTo(AnthropicThinkingBudgets.requestedBudget(ReasoningEffort.MEDIUM, null));
    }

    @Test
    @DisplayName("an explicit configured budget wins over the effort ladder")
    void configuredBudgetWinsOverEffort() {
        assertThat(AnthropicThinkingBudgets.requestedBudget(ReasoningEffort.HIGH, 3000)).isEqualTo(3000);
        assertThat(AnthropicThinkingBudgets.budgetFor(ReasoningEffort.MINIMAL, 3000, ROOMY_MAX_TOKENS)).hasValue(3000);
    }

    @Test
    @DisplayName("the budget is clamped to maxTokens - 1, not to maxTokens")
    void budgetIsClampedBelowMaxTokens() {
        // The default maxTokens of 4096 makes this the common case rather than an edge: HIGH clamps out of the box.
        assertThat(AnthropicThinkingBudgets.budgetFor(ReasoningEffort.HIGH, null, 4096)).hasValue(4095);
        assertThat(AnthropicThinkingBudgets.budgetFor(ReasoningEffort.HIGH, null, 8000)).hasValue(7999);
    }

    @Test
    @DisplayName("a budget that already fits is not clamped")
    void aFittingBudgetIsLeftAlone() {
        assertThat(AnthropicThinkingBudgets.budgetFor(ReasoningEffort.MINIMAL, null, ROOMY_MAX_TOKENS))
                .hasValue(AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS);
    }

    @Test
    @DisplayName("no legal budget exists when maxTokens leaves no room, and the answer is empty rather than illegal")
    void givesUpWhenNoLegalBudgetExists() {
        assertThat(AnthropicThinkingBudgets.budgetFor(ReasoningEffort.HIGH, null, 1024)).isEmpty();
        assertThat(AnthropicThinkingBudgets.budgetFor(ReasoningEffort.HIGH, null, 500)).isEmpty();
        // One token above the floor is the first maxTokens that works, and it yields exactly the floor.
        final OptionalInt justEnough = AnthropicThinkingBudgets.budgetFor(ReasoningEffort.HIGH, null, 1025);
        assertThat(justEnough).hasValue(AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS);
    }

    @Test
    @DisplayName("adaptive effort is a ladder-to-ladder map, with MINIMAL and LOW collapsing onto low")
    void adaptiveEffortLadder() {
        assertThat(AnthropicThinkingBudgets.effortFor(ReasoningEffort.MINIMAL)).contains(OutputConfig.Effort.LOW);
        assertThat(AnthropicThinkingBudgets.effortFor(ReasoningEffort.LOW)).contains(OutputConfig.Effort.LOW);
        assertThat(AnthropicThinkingBudgets.effortFor(ReasoningEffort.MEDIUM)).contains(OutputConfig.Effort.MEDIUM);
        assertThat(AnthropicThinkingBudgets.effortFor(ReasoningEffort.HIGH)).contains(OutputConfig.Effort.HIGH);
    }

    @Test
    @DisplayName("NONE and an absent effort carry no adaptive effort value")
    void noneAndAbsentCarryNoEffort() {
        assertThat(AnthropicThinkingBudgets.effortFor(ReasoningEffort.NONE)).isEmpty();
        assertThat(AnthropicThinkingBudgets.effortFor(null)).isEmpty();
    }
}
