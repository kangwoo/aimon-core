package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PayloadValues#asCounterOrNull(Object)} across the number shapes a bus codec can hand back: a counter survives
 * int/long/double normalization, and anything that is not a non-negative int reads as unmeasured.
 */
@DisplayName("PayloadValues.asCounterOrNull")
class PayloadValuesTest {

    @Test
    @DisplayName("an integral value of any boxed type decodes to the counter")
    void integralValuesDecode() {
        assertThat(PayloadValues.asCounterOrNull(5)).isEqualTo(5);
        assertThat(PayloadValues.asCounterOrNull(5L)).isEqualTo(5);
        assertThat(PayloadValues.asCounterOrNull(5.0d)).isEqualTo(5);
        assertThat(PayloadValues.asCounterOrNull(0)).isZero();
        assertThat(PayloadValues.asCounterOrNull((long) Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("absent, negative, fractional, out-of-range and non-numeric values read as unmeasured")
    void everythingElseIsUnmeasured() {
        assertThat(PayloadValues.asCounterOrNull(null)).isNull();
        assertThat(PayloadValues.asCounterOrNull(-1)).isNull();
        assertThat(PayloadValues.asCounterOrNull(5.5d)).isNull();
        assertThat(PayloadValues.asCounterOrNull(Double.NaN)).isNull();
        assertThat(PayloadValues.asCounterOrNull(Integer.MAX_VALUE + 1L)).isNull();
        assertThat(PayloadValues.asCounterOrNull(Long.MAX_VALUE)).isNull();
        assertThat(PayloadValues.asCounterOrNull("5")).isNull();
    }
}
