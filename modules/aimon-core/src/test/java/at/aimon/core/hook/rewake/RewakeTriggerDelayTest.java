package at.aimon.core.hook.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RewakeTriggerDelayTest {

    @Test
    void exposesProvidedDelay() {
        final Duration delay = Duration.ofMinutes(5);
        final RewakeTriggerDelay trigger = new RewakeTriggerDelay(delay);

        assertThat(trigger.getDelay()).isEqualTo(delay);
    }

    @Test
    void rejectsNullDelay() {
        assertThatNullPointerException().isThrownBy(() -> new RewakeTriggerDelay(null));
    }

    @Test
    void rejectsZeroDelay() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RewakeTriggerDelay(Duration.ZERO));
    }

    @Test
    void rejectsNegativeDelay() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RewakeTriggerDelay(Duration.ofSeconds(-1)));
    }

    @Test
    void equalsAndHashCodeBasedOnDelay() {
        final RewakeTriggerDelay a = new RewakeTriggerDelay(Duration.ofSeconds(10));
        final RewakeTriggerDelay b = new RewakeTriggerDelay(Duration.ofSeconds(10));
        final RewakeTriggerDelay c = new RewakeTriggerDelay(Duration.ofSeconds(20));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toStringIncludesDelay() {
        assertThat(new RewakeTriggerDelay(Duration.ofSeconds(7)).toString()).contains("delay");
    }
}
