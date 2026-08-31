package at.aimon.core.agent.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CompletionReason Tests")
class CompletionReasonTest {

    @Test
    @DisplayName("Only COMPLETED is successful")
    void onlyCompletedIsSuccessful() {
        assertThat(CompletionReason.COMPLETED.isSuccessful()).isTrue();
        assertThat(CompletionReason.MAX_ITERATIONS.isSuccessful()).isFalse();
        assertThat(CompletionReason.TOKEN_BUDGET_EXCEEDED.isSuccessful()).isFalse();
        assertThat(CompletionReason.WALL_CLOCK_EXCEEDED.isSuccessful()).isFalse();
        assertThat(CompletionReason.ABORTED.isSuccessful()).isFalse();
        assertThat(CompletionReason.INTERRUPTED.isSuccessful()).isFalse();
        assertThat(CompletionReason.ERROR.isSuccessful()).isFalse();
    }

    @Test
    @DisplayName("All values round-trip via valueOf")
    void valueOfRoundTrip() {
        for (CompletionReason reason : CompletionReason.values()) {
            assertThat(CompletionReason.valueOf(reason.name())).isSameAs(reason);
        }
    }
}
