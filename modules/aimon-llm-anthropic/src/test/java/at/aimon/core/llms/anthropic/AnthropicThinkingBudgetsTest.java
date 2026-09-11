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
        // The default maxTokens of 4096 makes this the common case rather than an edge: HIGH clamps whenever the call's
        // LlmModel sets no maxTokens — an agent definition with no model.maxTokens — and
        // AnthropicConfig.Builder.maxTokens(int) was not used.
        assertThat(AnthropicThinkingBudgets.budgetFor(ReasoningEffort.HIGH, null, 4096)).hasValue(4095);
        assertThat(AnthropicThinkingBudgets.budgetFor(ReasoningEffort.HIGH, null, 8000)).hasValue(7999);
        // So does an unset effort, whose middle rung is exactly 4096 -- the request #83 decided to leave as it is
        // (docs/design/llm/thinking-reporting-and-dialect-records.md section 16).
        assertThat(AnthropicThinkingBudgets.budgetFor(null, null, 4096)).hasValue(4095);
    }

    @Test
    @DisplayName("a budget that already fits is not clamped")
    void aFittingBudgetIsLeftAlone() {
        assertThat(AnthropicThinkingBudgets.budgetFor(ReasoningEffort.MINIMAL, null, ROOMY_MAX_TOKENS))
                .hasValue(AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS);
    }

    @Test
    @DisplayName("a budget one token under maxTokens is sent as asked — the clamp begins at maxTokens, not before it")
    void aBudgetUnderMaxTokensIsSentAsAskedAndOneAtMaxTokensIsClamped() {
        // The other half of budgetIsClampedBelowMaxTokens, on #89's rows. The first three are sent as asked however
        // little they leave the answer -- one token, four, one -- and only the last is a clamp, so only it is warned
        // about (docs/design/llm/thinking-reporting-and-dialect-records.md section 16.8).
        assertThat(AnthropicThinkingBudgets.budgetFor(null, null, 4097)).hasValue(4096);
        assertThat(AnthropicThinkingBudgets.budgetFor(null, null, 4100)).hasValue(4096);
        assertThat(AnthropicThinkingBudgets.budgetFor(null, 8000, 8001)).hasValue(8000);
        assertThat(AnthropicThinkingBudgets.budgetFor(null, 8000, 8000)).hasValue(7999);
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

    @Test
    @DisplayName("the inverse lands each rung's own budget back on that rung")
    void nearestEffortIsAnInverseOnTheLadderItself() {
        // The property that matters is round-tripping: a budget the ladder itself produced must come back as the rung
        // that produced it, or a translated request would ask for a different amount of thinking than an untranslated
        // one carrying the same effort.
        for (ReasoningEffort rung : new ReasoningEffort[]{ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM, ReasoningEffort.HIGH}) {
            final int budget = AnthropicThinkingBudgets.requestedBudget(rung, null);
            assertThat(AnthropicThinkingBudgets.nearestEffort(budget)).as("round trip of %s", rung).isEqualTo(rung);
        }
    }

    @Test
    @DisplayName("a budget between two rungs takes the nearer one, and a tie takes the lower")
    void nearestEffortPicksTheNearerRung() {
        assertThat(AnthropicThinkingBudgets.nearestEffort(1100)).isEqualTo(ReasoningEffort.MINIMAL);
        assertThat(AnthropicThinkingBudgets.nearestEffort(1900)).isEqualTo(ReasoningEffort.LOW);
        assertThat(AnthropicThinkingBudgets.nearestEffort(5000)).isEqualTo(ReasoningEffort.MEDIUM);
        assertThat(AnthropicThinkingBudgets.nearestEffort(12_000)).isEqualTo(ReasoningEffort.HIGH);
        // Exactly halfway between LOW (2048) and MEDIUM (4096). Asking for less thinking than an ambiguous number
        // might have meant is the cheaper of the two mistakes, so the tie resolves downwards.
        assertThat(AnthropicThinkingBudgets.nearestEffort(3072)).isEqualTo(ReasoningEffort.LOW);
    }

    @Test
    @DisplayName("budgets off both ends of the ladder clamp to its ends, and NONE is never the answer")
    void nearestEffortClampsAtBothEnds() {
        // Below the floor is unreachable through AnthropicConfig, which refuses a budget under 1024, but the function
        // is total and the answer has to be an amount of thinking rather than the absence of it: NONE means "send no
        // thinking parameter", which is not what an operator who wrote a budget asked for.
        assertThat(AnthropicThinkingBudgets.nearestEffort(0)).isEqualTo(ReasoningEffort.MINIMAL);
        assertThat(AnthropicThinkingBudgets.nearestEffort(1024)).isEqualTo(ReasoningEffort.MINIMAL);
        assertThat(AnthropicThinkingBudgets.nearestEffort(1_000_000)).isEqualTo(ReasoningEffort.HIGH);
        assertThat(AnthropicThinkingBudgets.nearestEffort(Integer.MAX_VALUE)).isEqualTo(ReasoningEffort.HIGH);
    }
}
