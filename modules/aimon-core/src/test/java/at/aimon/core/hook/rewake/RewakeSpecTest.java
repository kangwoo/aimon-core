package at.aimon.core.hook.rewake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RewakeSpecTest {

    private static RewakeTrigger delay() {
        return new RewakeTriggerDelay(Duration.ofMinutes(1));
    }

    @Test
    void buildersDefaultsApply() {
        final RewakeSpec spec = RewakeSpec.builder().trigger(delay()).reason("retry").build();

        assertThat(spec.getTrigger()).isInstanceOf(RewakeTriggerDelay.class);
        assertThat(spec.getTimeout()).isEqualTo(RewakeSpec.DEFAULT_TIMEOUT);
        assertThat(spec.getMaxAttempts()).isEqualTo(RewakeSpec.DEFAULT_MAX_ATTEMPTS);
        assertThat(spec.getPayload()).isEmpty();
        assertThat(spec.getReason()).isEqualTo("retry");
    }

    @Test
    void buildRequiresTrigger() {
        assertThatNullPointerException().isThrownBy(() -> RewakeSpec.builder().reason("retry").build());
    }

    @Test
    void buildRequiresReason() {
        assertThatNullPointerException().isThrownBy(() -> RewakeSpec.builder().trigger(delay()).build());
    }

    @Test
    void buildRejectsBlankReason() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RewakeSpec.builder().trigger(delay()).reason("   ").build());
    }

    @Test
    void buildRejectsNonPositiveTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RewakeSpec.builder().trigger(delay()).reason("r").timeout(Duration.ZERO).build());
        assertThatIllegalArgumentException().isThrownBy(
                () -> RewakeSpec.builder().trigger(delay()).reason("r").timeout(Duration.ofSeconds(-1)).build());
    }

    @Test
    void buildRejectsZeroOrNegativeMaxAttempts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RewakeSpec.builder().trigger(delay()).reason("r").maxAttempts(0).build());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RewakeSpec.builder().trigger(delay()).reason("r").maxAttempts(-3).build());
    }

    @Test
    void payloadEntriesAreCopiedDefensively() {
        final Map<String, String> mutable = new HashMap<>();
        mutable.put("k1", "v1");
        final RewakeSpec spec = RewakeSpec.builder().trigger(delay()).reason("r").payload(mutable).build();

        mutable.put("k2", "v2");

        assertThat(spec.getPayload()).containsExactly(Map.entry("k1", "v1"));
    }

    @Test
    void payloadIsImmutable() {
        final RewakeSpec spec = RewakeSpec.builder().trigger(delay()).reason("r").payload("k", "v").build();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> spec.getPayload().put("k2", "v2"));
    }

    @Test
    void payloadRejectsNullKeyOrValue() {
        assertThatNullPointerException()
                .isThrownBy(() -> RewakeSpec.builder().trigger(delay()).reason("r").payload(null, "v"));
        assertThatNullPointerException()
                .isThrownBy(() -> RewakeSpec.builder().trigger(delay()).reason("r").payload("k", null));
    }

    @Test
    void payloadMapRejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> RewakeSpec.builder().trigger(delay()).reason("r").payload(null));
    }

    @Test
    void timeoutOverrideTakesEffect() {
        final RewakeSpec spec = RewakeSpec.builder().trigger(delay()).reason("r").timeout(Duration.ofMinutes(15))
                .maxAttempts(7).build();

        assertThat(spec.getTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(spec.getMaxAttempts()).isEqualTo(7);
    }

    @Test
    void equalsAndHashCodeIncludeAllFields() {
        final RewakeSpec a = RewakeSpec.builder().trigger(delay()).reason("r").payload("k", "v").build();
        final RewakeSpec b = RewakeSpec.builder().trigger(delay()).reason("r").payload("k", "v").build();
        final RewakeSpec diffReason = RewakeSpec.builder().trigger(delay()).reason("other").payload("k", "v").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(diffReason);
    }

    @Test
    void toStringMentionsTriggerAndReason() {
        final RewakeSpec spec = RewakeSpec.builder().trigger(delay()).reason("retry-after-rate-limit").build();
        assertThat(spec.toString()).contains("trigger").contains("retry-after-rate-limit");
    }
}
