package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("InterruptReason — reading a name another node wrote")
class InterruptReasonTest {

    @ParameterizedTest
    @EnumSource(InterruptReason.class)
    @DisplayName("every name this build defines reads back as itself")
    void knownNamesRoundTrip(InterruptReason reason) {
        assertThat(InterruptReason.fromWireName(reason.name())).isSameAs(reason);
    }

    @Test
    @DisplayName("a name from a newer build reads as UNKNOWN instead of throwing")
    void unknownNameReadsAsUnknown() {
        assertThat(InterruptReason.fromWireName("INVENTED_BY_A_NEWER_NODE")).isSameAs(InterruptReason.UNKNOWN);
    }

    @Test
    @DisplayName("an absent name reads as UNKNOWN")
    void absentNameReadsAsUnknown() {
        assertThat(InterruptReason.fromWireName(null)).isSameAs(InterruptReason.UNKNOWN);
    }

    @Test
    @DisplayName("names are matched exactly — the wire carries name(), not a display form")
    void matchingIsExact() {
        assertThat(InterruptReason.fromWireName("user_sigint")).isSameAs(InterruptReason.UNKNOWN);
    }
}
