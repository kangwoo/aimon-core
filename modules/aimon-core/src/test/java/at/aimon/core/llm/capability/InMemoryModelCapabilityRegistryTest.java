package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryModelCapabilityRegistry - lookup and the built-in table")
class InMemoryModelCapabilityRegistryTest {

    private static final ModelCapabilities SAMPLING_REJECTED = ModelCapabilities.builder()
            .supportsSamplingParameters(false).build();
    private static final ModelCapabilities EVERYTHING_ALLOWED = ModelCapabilities.builder()
            .supportsSamplingParameters(true).supportsReasoningEffort(true).supportsToolsWithReasoning(true).build();

    @Test
    @DisplayName("an exact entry beats a prefix that would also match")
    void exactBeatsPrefix() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builder()
                .registerPrefix("gpt-5", SAMPLING_REJECTED).register("gpt-5-special", EVERYTHING_ALLOWED).build();

        assertThat(registry.capabilitiesOf("gpt-5-special")).contains(EVERYTHING_ALLOWED);
        assertThat(registry.capabilitiesOf("gpt-5-other")).contains(SAMPLING_REJECTED);
    }

    @Test
    @DisplayName("prefixes are evaluated in registration order and the first match wins")
    void prefixFirstMatchWins() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builder()
                .registerPrefix("gpt-5-chat", EVERYTHING_ALLOWED).registerPrefix("gpt-5", SAMPLING_REJECTED).build();

        assertThat(registry.capabilitiesOf("gpt-5-chat-latest")).contains(EVERYTHING_ALLOWED);
    }

    @Test
    @DisplayName("prefix matching is case-insensitive")
    void prefixMatchingIsCaseInsensitive() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builder()
                .registerPrefix("GPT-5", SAMPLING_REJECTED).build();

        assertThat(registry.capabilitiesOf("gpt-5.6-terra")).contains(SAMPLING_REJECTED);
        assertThat(registry.capabilitiesOf("GPT-5.6-TERRA")).contains(SAMPLING_REJECTED);
    }

    @Test
    @DisplayName("a null or empty model name is unknown")
    void nullOrEmptyNameIsUnknown() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.capabilitiesOf(null)).isEmpty();
        assertThat(registry.capabilitiesOf("")).isEmpty();
        assertThat(registry.resolve(null)).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("registering a null name or null capabilities is rejected")
    void nullRegistrationsRejected() {
        final InMemoryModelCapabilityRegistry.Builder builder = InMemoryModelCapabilityRegistry.builder();

        assertThatThrownBy(() -> builder.register(null, EVERYTHING_ALLOWED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.register("gpt-5", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.registerPrefix(null, EVERYTHING_ALLOWED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.registerPrefix("gpt-5", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the built-in table suppresses sampling and enables reasoning for the gpt-5 family")
    void defaultsDescribeGpt5() {
        final ModelCapabilities gpt5 = InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra");

        assertThat(gpt5.supportsSamplingParameters()).isFalse();
        assertThat(gpt5.supportsReasoningEffort()).isTrue();
        assertThat(gpt5.supportsToolsWithReasoning()).isFalse();
    }

    @Test
    @DisplayName("gpt-5-chat does not inherit the gpt-5 family's suppression")
    void defaultsKeepGpt5ChatSampling() {
        // Ordering inside withDefaults() is load-bearing: gpt-5-chat is the non-reasoning variant and is registered
        // first, exactly as InMemoryModelPriceTable registers gpt-4o-mini before gpt-4o.
        final ModelCapabilities chat = InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-chat-latest");

        assertThat(chat.supportsSamplingParameters()).isTrue();
        assertThat(chat.supportsReasoningEffort()).isFalse();
        assertThat(chat).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("models the table does not name stay unknown - gpt-4o and the o-series included")
    void defaultsLeaveEverythingElseUnknown() {
        // The o-series is deliberately absent: it takes a reasoning effort but rejects the value "none", so a wrong
        // entry would be a silent sampling change for its users. Re-adding it has to change this test.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.capabilitiesOf("gpt-4o")).isEmpty();
        assertThat(registry.capabilitiesOf("gpt-4-turbo")).isEmpty();
        assertThat(registry.capabilitiesOf("o3")).isEmpty();
        assertThat(registry.capabilitiesOf("o1-mini")).isEmpty();
        assertThat(registry.resolve("o3")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("builderWithDefaults lets a caller name a renamed gateway deployment")
    void builderWithDefaultsAddsADeployment() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .register("prod-assistant", ModelCapabilities.builder().supportsSamplingParameters(false)
                        .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build())
                .build();

        assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
        // ... without losing the built-in entries.
        assertThat(registry.resolve("gpt-5.6-terra").supportsReasoningEffort()).isTrue();
        assertThat(registry.resolve("gpt-4o")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("builderWithDefaults can override a built-in prefix without reordering it")
    void builderWithDefaultsOverridesAPrefixInPlace() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .registerPrefix("gpt-5", EVERYTHING_ALLOWED).build();

        assertThat(registry.resolve("gpt-5.6-terra")).isEqualTo(EVERYTHING_ALLOWED);
        // Re-putting an existing key keeps its position in the LinkedHashMap, so gpt-5 cannot jump ahead of the more
        // specific gpt-5-chat and swallow it.
        assertThat(registry.resolve("gpt-5-chat-latest")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("only the reasoning gpt-5 family round-trips reasoning traces")
    void reasoningTraceRoundTripIsSetOnlyWhereItIsTrue() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.resolve("gpt-5.6-terra").supportsReasoningTraceRoundTrip()).isTrue();
        // gpt-5-chat is the non-reasoning variant, and this false is what keeps the existing assertion that it
        // resolves equal to unknown() green -- i.e. it is the line that fails if the chat variant is ever routed to
        // the Responses API.
        assertThat(registry.resolve("gpt-5-chat-latest").supportsReasoningTraceRoundTrip()).isFalse();
        assertThat(registry.resolve("gpt-4o").supportsReasoningTraceRoundTrip()).isFalse();
        assertThat(registry.resolve("o3").supportsReasoningTraceRoundTrip()).isFalse();
    }

}
