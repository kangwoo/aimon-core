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
        assertThat(CompletionReason.BLOCKED.isSuccessful()).isFalse();
        assertThat(CompletionReason.ERROR.isSuccessful()).isFalse();
    }

    @Test
    @DisplayName("All values round-trip via valueOf")
    void valueOfRoundTrip() {
        for (CompletionReason reason : CompletionReason.values()) {
            assertThat(CompletionReason.valueOf(reason.name())).isSameAs(reason);
        }
    }

    @Test
    @DisplayName("fromWireName reads every known name as itself, whatever the success flag says")
    void fromWireNameKeepsAKnownName() {
        // The flag decides the fallback and nothing else: a known reason is never overridden by it, so a success that
        // ended SUSPENDED and a failure that ended TRUNCATED both keep their reason.
        for (CompletionReason reason : CompletionReason.values()) {
            assertThat(CompletionReason.fromWireName(reason.name(), true)).isSameAs(reason);
            assertThat(CompletionReason.fromWireName(reason.name(), false)).isSameAs(reason);
        }
    }

    @Test
    @DisplayName("fromWireName reads a name this build does not know as the coarse reason for the success flag")
    void fromWireNameDegradesAnUnknownName() {
        assertThat(CompletionReason.fromWireName("INVENTED_BY_A_NEWER_NODE", true))
                .isSameAs(CompletionReason.COMPLETED);
        assertThat(CompletionReason.fromWireName("INVENTED_BY_A_NEWER_NODE", false)).isSameAs(CompletionReason.ERROR);
        // Names are matched exactly, as valueOf matches them: a differently-cased name is an unknown one.
        assertThat(CompletionReason.fromWireName("completed", false)).isSameAs(CompletionReason.ERROR);
    }

    @Test
    @DisplayName("fromWireName reads an absent name the same way as an unknown one")
    void fromWireNameDegradesAnAbsentName() {
        assertThat(CompletionReason.fromWireName(null, true)).isSameAs(CompletionReason.COMPLETED);
        assertThat(CompletionReason.fromWireName(null, false)).isSameAs(CompletionReason.ERROR);
    }
}
