package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ModelCapabilities - the fail-open descriptor")
class ModelCapabilitiesTest {

    @Test
    @DisplayName("unknown() withholds nothing and invents nothing, flag by flag")
    void unknownWithholdsNothingAndInventsNothing() {
        // This is the fail-open contract, and it is asserted one flag at a time on purpose: "fail open" is not "all
        // true", it is "nothing the caller asked for is withheld, and nothing the caller did not ask for is
        // invented" -- which permits sampling parameters somebody set, never invents a reasoning effort, and does
        // not clamp tools against reasoning without evidence. Changing any of these silently changes the wire for
        // every deployment whose model no registry describes.
        //
        // Round 2 re-grounded this sentence: it used to say "the request this framework produced before
        // capabilities existed", which stopped being true when the client's DEFAULT_TEMPERATURE fallback was
        // removed by maintainer ruling (issue #43). The three values did not change, and the new grounding
        // re-derives all three -- see docs/design/llm/openai-model-capabilities.md section 9.1.
        final ModelCapabilities unknown = ModelCapabilities.unknown();

        assertThat(unknown.supportsSamplingParameters()).isTrue();
        assertThat(unknown.supportsReasoningEffort()).isFalse();
        assertThat(unknown.supportsToolsWithReasoning()).isTrue();
    }

    @Test
    @DisplayName("unknown() is a stable instance")
    void unknownIsStable() {
        assertThat(ModelCapabilities.unknown()).isSameAs(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("a builder that sets nothing equals unknown()")
    void emptyBuilderEqualsUnknown() {
        // The builder seeds itself from literals rather than from unknown() (unknown() is built from a builder, so
        // reading it there would hand back a half-built object). This assertion is what keeps the two in step.
        assertThat(ModelCapabilities.builder().build()).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("a partially specified entry leaves the other flags permissive")
    void partialEntryStaysPermissive() {
        final ModelCapabilities partial = ModelCapabilities.builder().supportsReasoningEffort(true).build();

        assertThat(partial.supportsReasoningEffort()).isTrue();
        assertThat(partial.supportsSamplingParameters()).isTrue();
        assertThat(partial.supportsToolsWithReasoning()).isTrue();
    }

    @Test
    @DisplayName("every flag round-trips through the builder")
    void flagsRoundTrip() {
        final ModelCapabilities capabilities = ModelCapabilities.builder().supportsSamplingParameters(false)
                .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build();

        assertThat(capabilities.supportsSamplingParameters()).isFalse();
        assertThat(capabilities.supportsReasoningEffort()).isTrue();
        assertThat(capabilities.supportsToolsWithReasoning()).isFalse();
    }

    @Test
    @DisplayName("equals and hashCode cover all four flags")
    void equalsAndHashCode() {
        final ModelCapabilities a = ModelCapabilities.builder().supportsSamplingParameters(false)
                .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build();
        final ModelCapabilities same = ModelCapabilities.builder().supportsSamplingParameters(false)
                .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build();

        assertThat(a).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(a).isNotEqualTo(ModelCapabilities.builder().supportsSamplingParameters(true)
                .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build());
        assertThat(a).isNotEqualTo(ModelCapabilities.builder().supportsSamplingParameters(false)
                .supportsReasoningEffort(false).supportsToolsWithReasoning(false).build());
        assertThat(a).isNotEqualTo(ModelCapabilities.builder().supportsSamplingParameters(false)
                .supportsReasoningEffort(true).supportsToolsWithReasoning(true).build());
        assertThat(a).isNotEqualTo(
                ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                        .supportsToolsWithReasoning(false).supportsReasoningTraceRoundTrip(true).build());
        assertThat(a).isNotEqualTo(null).isNotEqualTo("not a descriptor");
    }

    @Test
    @DisplayName("toString names every flag")
    void toStringNamesEveryFlag() {
        assertThat(ModelCapabilities.unknown().toString()).contains("supportsSamplingParameters=true")
                .contains("supportsReasoningEffort=false").contains("supportsToolsWithReasoning=true")
                .contains("supportsReasoningTraceRoundTrip=false");
    }

    @Test
    @DisplayName("an unknown model does not round-trip reasoning traces")
    void unknownDoesNotRoundTripReasoningTraces() {
        // Fail open, stated per flag as the other three are. False here is not a restriction: replaying an opaque
        // provider payload is something the framework would have to START doing to a model nobody has described, and
        // it is what routes such a model to the request surface it is on today.
        assertThat(ModelCapabilities.unknown().supportsReasoningTraceRoundTrip()).isFalse();
    }
}
