package at.aimon.core.skill.policy.pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link PendingTurnId}. */
class PendingTurnIdTest {

    @Test
    void ofRoundTripsValue() {
        assertThat(PendingTurnId.of("abc").value()).isEqualTo("abc");
    }

    @Test
    void generateProducesNonEmptyValue() {
        assertThat(PendingTurnId.generate().value()).isNotBlank();
    }

    @Test
    void generateProducesDistinctIds() {
        assertThat(PendingTurnId.generate()).isNotEqualTo(PendingTurnId.generate());
    }

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> PendingTurnId.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankValue() {
        assertThatThrownBy(() -> PendingTurnId.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityBasedOnValue() {
        assertThat(PendingTurnId.of("x")).isEqualTo(PendingTurnId.of("x")).hasSameHashCodeAs(PendingTurnId.of("x"));
        assertThat(PendingTurnId.of("x")).isNotEqualTo(PendingTurnId.of("y"));
    }

    @Test
    void toStringReturnsRawValue() {
        assertThat(PendingTurnId.of("hello").toString()).isEqualTo("hello");
    }
}
