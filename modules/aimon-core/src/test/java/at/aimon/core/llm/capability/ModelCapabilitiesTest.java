package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ModelCapabilities - the fail-open descriptor")
class ModelCapabilitiesTest {

    @Test
    @DisplayName("unknown() is today's request shape, flag by flag")
    void unknownIsTodaysRequestShape() {
        // This is the fail-open contract, and it is asserted one flag at a time on purpose: "fail open" is not "all
        // true", it is "the request this framework produced before capabilities existed" -- which sends sampling
        // parameters, never sends a reasoning effort, and does not treat tools as conflicting with reasoning.
        // Changing any of these silently changes the wire for every deployment whose model no registry describes.
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
    @DisplayName("equals and hashCode cover all three flags")
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
        assertThat(a).isNotEqualTo(null).isNotEqualTo("not a descriptor");
    }

    @Test
    @DisplayName("toString names every flag")
    void toStringNamesEveryFlag() {
        assertThat(ModelCapabilities.unknown().toString()).contains("supportsSamplingParameters=true")
                .contains("supportsReasoningEffort=false").contains("supportsToolsWithReasoning=true");
    }
}
