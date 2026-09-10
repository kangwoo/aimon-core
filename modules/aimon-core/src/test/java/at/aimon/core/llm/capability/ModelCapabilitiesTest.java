package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningEffort;

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
        // MINIMAL rather than NONE: NONE is the rung most likely to be absent -- every OpenAI o-series name probed
        // on 2026-09-09 rejects it, and gpt-5-nano rejected it in round 6 -- so treating it as acceptable for a
        // model nobody has described would usually invent a wire value the vendor rejects. Round 8 narrowed
        // "always" to "usually": gpt-5.6-terra accepts it. The default stands on the asymmetry of the two mistakes
        // rather than on absence -- withholding a rung a model has costs a reported omission, sending one it lacks
        // costs a 400. Every other rung a caller can name stays sendable, which is the fail-open half.
        assertThat(unknown.lowestReasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
        // The sixth field is the only one whose fail-open value is not a permission. Both real dialects are a 400 on
        // the model that speaks the other, so there is no safe two-valued default and UNKNOWN is the absence of the
        // fact -- which is what makes a client leave the request exactly as the caller configured it.
        assertThat(unknown.thinkingDialect()).isEqualTo(ThinkingDialect.UNKNOWN);
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
        assertThat(partial.lowestReasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
        assertThat(partial.thinkingDialect()).isEqualTo(ThinkingDialect.UNKNOWN);
    }

    @Test
    @DisplayName("every flag round-trips through the builder")
    void flagsRoundTrip() {
        final ModelCapabilities capabilities = ModelCapabilities.builder().supportsSamplingParameters(false)
                .supportsReasoningEffort(true).supportsToolsWithReasoning(false)
                .lowestReasoningEffort(ReasoningEffort.LOW).thinkingDialect(ThinkingDialect.BUDGETED).build();

        assertThat(capabilities.supportsSamplingParameters()).isFalse();
        assertThat(capabilities.supportsReasoningEffort()).isTrue();
        assertThat(capabilities.supportsToolsWithReasoning()).isFalse();
        assertThat(capabilities.lowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW);
        assertThat(capabilities.thinkingDialect()).isEqualTo(ThinkingDialect.BUDGETED);
    }

    @Test
    @DisplayName("a null thinking dialect is rejected — UNKNOWN is how a table says it cannot answer")
    void nullThinkingDialectRejected() {
        // The absence of the fact already has a name, and it is a constant rather than null. Accepting null would
        // give the same state two spellings, one of which every reader has to remember to handle.
        assertThatThrownBy(() -> ModelCapabilities.builder().thinkingDialect(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("thinkingDialect");
    }

    @Test
    @DisplayName("a null lowest reasoning effort is rejected rather than silently reopening the ladder")
    void nullLowestReasoningEffortRejected() {
        assertThatThrownBy(() -> ModelCapabilities.builder().lowestReasoningEffort(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("lowestReasoningEffort");
    }

    @Test
    @DisplayName("equals and hashCode cover all six fields")
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
        assertThat(a).isNotEqualTo(
                ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                        .supportsToolsWithReasoning(false).lowestReasoningEffort(ReasoningEffort.LOW).build());
        assertThat(a).isNotEqualTo(
                ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                        .supportsToolsWithReasoning(false).thinkingDialect(ThinkingDialect.ADAPTIVE).build());
        assertThat(a).isNotEqualTo(null).isNotEqualTo("not a descriptor");
    }

    @Test
    @DisplayName("toString names every field")
    void toStringNamesEveryFlag() {
        assertThat(ModelCapabilities.unknown().toString()).contains("supportsSamplingParameters=true")
                .contains("supportsReasoningEffort=false").contains("supportsToolsWithReasoning=true")
                .contains("supportsReasoningTraceRoundTrip=false").contains("lowestReasoningEffort=MINIMAL")
                .contains("thinkingDialect=UNKNOWN");
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
