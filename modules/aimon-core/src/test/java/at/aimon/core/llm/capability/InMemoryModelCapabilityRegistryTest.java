package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningEffort;

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
    @DisplayName("exact matching is case-insensitive too, so a portal-cased deployment name resolves")
    void exactMatchingIsCaseInsensitive() {
        // The whole point of the exact map is that an operator can name a deployment their gateway renamed, and the
        // name they have is the one their portal shows. While this half was case-sensitive, registering
        // "prod-assistant" for a deployment configured as "Prod-Assistant" missed, no prefix caught it, the model
        // resolved to unknown() -- and the one line that closes the fail-open gap did nothing, silently.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builder()
                .register("prod-assistant", SAMPLING_REJECTED).register("OTHER-Deployment", EVERYTHING_ALLOWED).build();

        assertThat(registry.capabilitiesOf("Prod-Assistant")).contains(SAMPLING_REJECTED);
        assertThat(registry.capabilitiesOf("prod-assistant")).contains(SAMPLING_REJECTED);
        assertThat(registry.capabilitiesOf("other-deployment")).contains(EVERYTHING_ALLOWED);
    }

    @Test
    @DisplayName("the o-series rows start their reasoning ladder at LOW, and gpt-5.x keeps the default MINIMAL")
    void oSeriesLadderStartsAtLow() {
        // Measured 2026-09-09: o4-mini answers "Supported values are: 'low', 'medium', 'high', and 'xhigh'" while
        // gpt-5-nano answers "'minimal', 'low', 'medium', and 'high'". Without the row, the neutral MINIMAL would
        // translate to the wire value 'minimal' for the o-series and earn a 400.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.resolve("o4-mini").lowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW);
        assertThat(registry.resolve("o3-mini").lowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW);
        assertThat(registry.resolve("o1").lowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW);
        assertThat(registry.resolve("gpt-5.6-terra").lowestReasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
        // A model nobody describes keeps the fail-open floor, so nothing it was ever sent starts being withheld.
        assertThat(registry.resolve("gpt-4o").lowestReasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
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
        // Reversal, measured 2026-09-09: this asserted isFalse() until live probes showed gpt-5-nano answers 200 to
        // tools with no reasoning_effort, and rejects the "none" that false made the client send. Do not flip it back
        // without a probe -- false is not the cautious choice here, it is the one that produces a 400.
        assertThat(gpt5.supportsToolsWithReasoning()).isTrue();
        assertThat(gpt5.supportsReasoningTraceRoundTrip()).isTrue();
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
    @DisplayName("models the table does not name stay unknown - gpt-4o and gpt-4-turbo")
    void defaultsLeaveEverythingElseUnknown() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.capabilitiesOf("gpt-4o")).isEmpty();
        assertThat(registry.capabilitiesOf("gpt-4-turbo")).isEmpty();
        assertThat(registry.resolve("gpt-4o")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("the o-series is in the table, suppresses sampling, and stays off the Responses path")
    void oSeriesRowsAreMeasuredAndPresent() {
        // Reversal, measured 2026-09-09. This test used to assert the OPPOSITE -- that o1/o3/o4 stayed unknown --
        // because the belief that they reject sampling was unverified and a wrong row is a *silent* sampling change
        // while no row leaves those users exactly where they are. Live probes closed that: o3-mini and o4-mini answer
        // 400 to temperature 0.0 and 200 to 1.0, accept tools with no reasoning_effort, and reject effort "none".
        //
        // supportsToolsWithReasoning MUST stay true -- false makes the client omit an effort it need not omit, and it
        // is what the class javadoc's own example has warned about all along. supportsReasoningTraceRoundTrip stays
        // false because reasoning-item replay was never measured for these models, and asserting a round trip nobody
        // has seen is how the gpt-5 row came out wrong the first time.
        // See docs/design/llm/openai-model-capabilities.md section 11.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        for (String model : new String[]{"o1", "o1-pro", "o3", "o3-mini", "o4-mini"}) {
            final ModelCapabilities caps = registry.resolve(model);
            assertThat(caps.supportsSamplingParameters()).as("%s sampling", model).isFalse();
            assertThat(caps.supportsReasoningEffort()).as("%s effort", model).isTrue();
            assertThat(caps.supportsToolsWithReasoning()).as("%s tools+reasoning", model).isTrue();
            assertThat(caps.supportsReasoningTraceRoundTrip()).as("%s replay", model).isFalse();
        }
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
