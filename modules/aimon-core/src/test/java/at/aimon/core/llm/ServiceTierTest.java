package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceTierTest {

    @Test
    @DisplayName("the four neutral names resolve to their constants, case- and space-insensitively")
    void neutralNames() {
        assertThat(ServiceTier.of("standard")).contains(ServiceTier.STANDARD);
        assertThat(ServiceTier.of(" FAST ")).contains(ServiceTier.FAST);
        assertThat(ServiceTier.of("Flex")).contains(ServiceTier.FLEX);
        assertThat(ServiceTier.of("batch")).contains(ServiceTier.BATCH);
        assertThat(ServiceTier.FAST.isNormalized()).isTrue();
    }

    @Test
    @DisplayName("any other value is preserved, lower-cased, and not normalized")
    void unknownValuesArePreserved() {
        final ServiceTier scale = ServiceTier.of("Scale").orElseThrow();

        assertThat(scale.getValue()).isEqualTo("scale");
        assertThat(scale.isNormalized()).isFalse();
        assertThat(scale).isEqualTo(ServiceTier.of("scale").orElseThrow());
    }

    @Test
    @DisplayName("null or blank is not a tier")
    void blankIsEmpty() {
        assertThat(ServiceTier.of(null)).isEmpty();
        assertThat(ServiceTier.of("  ")).isEmpty();
    }
}
