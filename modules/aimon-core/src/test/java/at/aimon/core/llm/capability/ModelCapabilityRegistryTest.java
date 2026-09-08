package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ModelCapabilityRegistry#resolve} as the one place the fail-open rule lives.
 *
 * <p>
 * Every way an implementation can fail to answer — the empty registry, a miss, a null return — has to land on
 * {@link ModelCapabilities#unknown()}, because an implementer who gets this wrong turns fail-open into fail-closed for
 * every model they have not heard of, and does it silently.
 */
@DisplayName("ModelCapabilityRegistry - the total, fail-open view")
class ModelCapabilityRegistryTest {

    private static final ModelCapabilities KNOWN = ModelCapabilities.builder().supportsSamplingParameters(false)
            .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build();

    @Test
    @DisplayName("EMPTY knows nothing and resolves everything to unknown()")
    void emptyResolvesToUnknown() {
        assertThat(ModelCapabilityRegistry.EMPTY.capabilitiesOf("gpt-5.6-terra")).isEmpty();
        assertThat(ModelCapabilityRegistry.EMPTY.resolve("gpt-5.6-terra")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("a miss resolves to unknown()")
    void missResolvesToUnknown() {
        final ModelCapabilityRegistry registry = modelName -> Optional.empty();

        assertThat(registry.resolve("anything")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("a null return resolves to unknown() rather than throwing")
    void nullReturnResolvesToUnknown() {
        // A misbehaving third-party registry must degrade to today's behaviour, not NPE inside a provider's request
        // builder -- which on the streaming path runs outside the try-with-resources that maps exceptions.
        final ModelCapabilityRegistry registry = modelName -> null;

        assertThat(registry.resolve("anything")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("a hit resolves to what the registry said")
    void hitResolvesToTheEntry() {
        final ModelCapabilityRegistry registry = modelName -> Optional.of(KNOWN);

        assertThat(registry.resolve("gpt-5.6-terra")).isSameAs(KNOWN);
    }
}
