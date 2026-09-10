package at.aimon.llm.capability.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchIllegalArgumentException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ModelCapabilityDeclaration;
import at.aimon.core.llm.capability.ThinkingDialect;

/**
 * Whether the probe fails when it should — the part of the contract nothing else in the tree exercises once both real
 * surfaces forward every key.
 *
 * <p>
 * The fake surface carries four keys, and that list is <em>its own</em>. It is deliberately not driven from
 * {@link DeclarableKeys#names()}: these cases test the probe's teeth, not coverage, and a fake that had to carry every
 * key the builder declares would be a third hand-kept key list — one a new key would break here before either real
 * surface is even asked. The four cover the shapes that matter: two booleans (so one getter can feed two setters), an
 * enum whose first constant is a plausible hard-coded default, and the ladder, bound as a {@code List} against the
 * builder's {@code Set}.
 */
@DisplayName("ModelCapabilityBindingProbe - fails when a forwarding drops, rewires or invents a value")
class ModelCapabilityBindingProbeTest {

    private static final List<String> FAKE_KEYS = List.of("supportsSamplingParameters", "supportsReasoningEffort",
            "thinkingDialect", "acceptedReasoningEfforts");

    private static final String FORWARDING = "FakeSurface.toDeclaration";

    @Nested
    @DisplayName("check 1 — the surface carries the key")
    class SurfaceCarriesTheKey {

        @Test
        @DisplayName("passes for every key the surface has a getter and a setter for")
        void passesForBoundKeys() {
            final ModelCapabilityBindingProbe<FakeSurface> probe = probe(ModelCapabilityBindingProbeTest::copiesAll);

            FAKE_KEYS.forEach(
                    key -> assertThatCode(() -> probe.assertSurfaceCarries(key)).as(key).doesNotThrowAnyException());
        }

        @Test
        @DisplayName("fails for a key the surface has no property for, naming it as an operator writes it")
        void failsForAnUnboundKey() {
            assertThatThrownBy(() -> probe(ModelCapabilityBindingProbeTest::copiesAll)
                    .assertSurfaceCarries("supportsReasoningSummary")).isInstanceOf(AssertionError.class)
                    .hasMessageContaining(FakeSurface.class.getName())
                    .hasMessageContaining("`fake.supportsReasoningSummary`");
        }

        @Test
        @DisplayName("fails for a write-only property, which a binder fills and the forwarding cannot read")
        void failsForAWriteOnlyProperty() {
            final ModelCapabilityBindingProbe<WriteOnlySurface> probe = ModelCapabilityBindingProbe
                    .forSurface(WriteOnlySurface::new)
                    .forwarding(surface -> ModelCapabilityDeclaration.builder().build())
                    .operatorKeyPath(key -> "fake." + key).forwardingLocation(FORWARDING).build();

            assertThatThrownBy(() -> probe.assertSurfaceCarries("thinkingDialect")).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("no getter");
        }
    }

    @Nested
    @DisplayName("check 2 — the value written reaches the declaration")
    class ValueReachesTheDeclaration {

        @Test
        @DisplayName("passes, for both values of every key, when the forwarding copies every key")
        void passesWhenEveryKeyIsCopied() {
            final ModelCapabilityBindingProbe<FakeSurface> probe = probe(ModelCapabilityBindingProbeTest::copiesAll);

            FAKE_KEYS.forEach(key -> assertThatCode(
                    () -> probe.assertValuesReachTheDeclaration(key, ProbeValues.distinctPairFor(key))).as(key)
                    .doesNotThrowAnyException());
        }

        @Test
        @DisplayName("fails when the forwarding drops the key, naming the key, the surface and the method (#82)")
        void failsWhenTheForwardingDropsTheKey() {
            assertThatThrownBy(() -> probe(ModelCapabilityBindingProbeTest::dropsTheDialect)
                    .assertValuesReachTheDeclaration("thinkingDialect", ProbeValues.distinctPairFor("thinkingDialect")))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("`thinkingDialect` was written on " + FakeSurface.class.getName())
                    .hasMessageContaining(FORWARDING + " produced a declaration that states nothing at all")
                    .hasMessageContaining("`.thinkingDialect(...)`");
        }

        @Test
        @DisplayName("recognises the dropped key inside a wrapping exception, the way the CLI rethrows it")
        void recognisesTheRefusalInsideAWrapper() {
            assertThatThrownBy(() -> probe(ModelCapabilityBindingProbeTest::dropsTheDialectAndWrapsTheRefusal)
                    .assertValuesReachTheDeclaration("thinkingDialect", ProbeValues.distinctPairFor("thinkingDialect")))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("does not read this key");
        }

        @Test
        @DisplayName("does not call a different refusal a dropped key — the ladder-pair refusal here")
        void doesNotRelabelAnotherRefusal() {
            assertThatThrownBy(
                    () -> probe(ModelCapabilityBindingProbeTest::alwaysStatesAFloor).assertValuesReachTheDeclaration(
                            "acceptedReasoningEfforts", ProbeValues.distinctPairFor("acceptedReasoningEfforts")))
                    .isInstanceOf(AssertionError.class).hasMessageNotContaining("does not read this key")
                    .hasMessageContaining("`acceptedReasoningEfforts` = ").rootCause()
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("states both");
        }

        @Test
        @DisplayName("fails when one property feeds two setters, which only whole-declaration equality sees")
        void failsWhenOnePropertyFeedsTwoSetters() {
            assertThatThrownBy(() -> probe(ModelCapabilityBindingProbeTest::feedsOnePropertyToTwoSetters)
                    .assertValuesReachTheDeclaration("supportsSamplingParameters",
                            ProbeValues.distinctPairFor("supportsSamplingParameters")))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("supportsReasoningEffort=true");
        }

        @Test
        @DisplayName("fails on the second value when the forwarding writes a constant instead of reading the surface")
        void failsWhenTheForwardingHardCodesAValue() {
            assertThatThrownBy(() -> probe(ModelCapabilityBindingProbeTest::hardCodesTheDialect)
                    .assertValuesReachTheDeclaration("thinkingDialect", ProbeValues.distinctPairFor("thinkingDialect")))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("`thinkingDialect` = " + ThinkingDialect.values()[1] + " was written");
        }
    }

    @Nested
    @DisplayName("a hand-picked pair is held to the same rules as a synthesised one")
    class HandPickedPairs {

        private final ModelCapabilityBindingProbe<FakeSurface> probe = probe(
                ModelCapabilityBindingProbeTest::copiesAll);

        @Test
        @DisplayName("no values are refused — they would check nothing and pass")
        void refusesNoValues() {
            assertThatThrownBy(() -> probe.assertValuesReachTheDeclaration("thinkingDialect", List.of()))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("exactly two");
        }

        @Test
        @DisplayName("a single value is refused")
        void refusesASingleValue() {
            assertThatThrownBy(
                    () -> probe.assertValuesReachTheDeclaration("thinkingDialect", List.of(ThinkingDialect.EITHER)))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("exactly two");
        }

        @Test
        @DisplayName("the same value twice is refused")
        void refusesARepeatedValue() {
            assertThatThrownBy(() -> probe.assertValuesReachTheDeclaration("thinkingDialect",
                    List.of(ThinkingDialect.EITHER, ThinkingDialect.EITHER))).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("same value twice");
        }

        @Test
        @DisplayName("a null value is refused, because null is how a declaration says nothing")
        void refusesANullValue() {
            assertThatThrownBy(() -> probe.assertValuesReachTheDeclaration("thinkingDialect",
                    Arrays.asList(ThinkingDialect.EITHER, null))).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("null probe value");
        }

        @Test
        @DisplayName("a value of another type than the setter's is refused, naming both types")
        void refusesAValueOfTheWrongType() {
            assertThatThrownBy(
                    () -> probe.assertValuesReachTheDeclaration("thinkingDialect", List.of("EITHER", "ADAPTIVE")))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("java.lang.String")
                    .hasMessageContaining(ThinkingDialect.class.getName());
        }

        @Test
        @DisplayName("a value the declaration itself refuses is refused before any surface is involved")
        void refusesAValueTheDeclarationRefuses() {
            assertThatThrownBy(() -> probe.assertValuesReachTheDeclaration("acceptedReasoningEfforts",
                    List.of(EnumSet.noneOf(ReasoningEffort.class), EnumSet.of(ReasoningEffort.LOW))))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("refuses `acceptedReasoningEfforts`")
                    .hasMessageContaining("pick one the declaration accepts")
                    .hasMessageNotContaining("declaresAnything");
        }

        @Test
        @DisplayName("a value the builder calls empty is blamed on declaresAnything(), since no other value would pass")
        void blamesDeclaresAnythingWhenTheBuilderCallsTheValueEmpty() {
            // Unreachable through a real key while declaresAnything() checks all eight, so the refusal is taken from
            // an empty builder: it is the exception a key missing from that method produces whatever value is set.
            final IllegalArgumentException emptyRefusal = catchIllegalArgumentException(
                    () -> ModelCapabilityDeclaration.builder().build());

            assertThat(ModelCapabilityBindingProbe.refusedProbeValue("supportsReasoningSummary", true, emptyRefusal))
                    .hasMessageContaining("`supportsReasoningSummary` = true was the only key set")
                    .hasMessageContaining("ModelCapabilityDeclaration.Builder.declaresAnything() does not check"
                            + " the field `.supportsReasoningSummary(...)` writes")
                    .hasMessageNotContaining("pick one the declaration accepts").cause().isSameAs(emptyRefusal);
        }

        @Test
        @DisplayName("a pair whose declarations compare equal is refused, naming equals as the reason")
        void refusesAPairEqualsCannotTellApart() {
            final ModelCapabilityDeclaration same = ModelCapabilityDeclaration.builder().supportsReasoningSummary(false)
                    .build();

            assertThatThrownBy(() -> ModelCapabilityBindingProbe.requireDistinguishable("supportsReasoningSummary",
                    List.of(true, false), same, same)).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("equals does not compare `supportsReasoningSummary`");
        }

        @Test
        @DisplayName("a usable pair is probed like a synthesised one")
        void probesAUsablePair() {
            assertThatCode(() -> probe.assertValuesReachTheDeclaration("acceptedReasoningEfforts",
                    List.of(EnumSet.of(ReasoningEffort.LOW), EnumSet.of(ReasoningEffort.LOW, ReasoningEffort.HIGH))))
                    .doesNotThrowAnyException();
        }
    }

    private static ModelCapabilityBindingProbe<FakeSurface> probe(
            Function<FakeSurface, ModelCapabilityDeclaration> forwarding) {
        return ModelCapabilityBindingProbe.forSurface(FakeSurface::new).forwarding(forwarding)
                .operatorKeyPath(key -> "fake." + key).forwardingLocation(FORWARDING).build();
    }

    private static ModelCapabilityDeclaration copiesAll(FakeSurface surface) {
        return ModelCapabilityDeclaration.builder().supportsSamplingParameters(surface.getSupportsSamplingParameters())
                .supportsReasoningEffort(surface.getSupportsReasoningEffort())
                .thinkingDialect(surface.getThinkingDialect()).acceptedReasoningEfforts(rungs(surface)).build();
    }

    private static ModelCapabilityDeclaration dropsTheDialect(FakeSurface surface) {
        return ModelCapabilityDeclaration.builder().supportsSamplingParameters(surface.getSupportsSamplingParameters())
                .supportsReasoningEffort(surface.getSupportsReasoningEffort()).acceptedReasoningEfforts(rungs(surface))
                .build();
    }

    private static ModelCapabilityDeclaration dropsTheDialectAndWrapsTheRefusal(FakeSurface surface) {
        try {
            return dropsTheDialect(surface);
        } catch (IllegalArgumentException e) {
            // The CLI's shape: the core's refusal travels as the cause of an exception that names the yaml key.
            throw new IllegalStateException("Invalid `fake.probe-model` in the config: " + e.getMessage(), e);
        }
    }

    private static ModelCapabilityDeclaration feedsOnePropertyToTwoSetters(FakeSurface surface) {
        return ModelCapabilityDeclaration.builder().supportsSamplingParameters(surface.getSupportsSamplingParameters())
                .supportsReasoningEffort(surface.getSupportsSamplingParameters())
                .thinkingDialect(surface.getThinkingDialect()).acceptedReasoningEfforts(rungs(surface)).build();
    }

    private static ModelCapabilityDeclaration hardCodesTheDialect(FakeSurface surface) {
        return ModelCapabilityDeclaration.builder().supportsSamplingParameters(surface.getSupportsSamplingParameters())
                .supportsReasoningEffort(surface.getSupportsReasoningEffort())
                .thinkingDialect(ThinkingDialect.values()[0]).acceptedReasoningEfforts(rungs(surface)).build();
    }

    private static ModelCapabilityDeclaration alwaysStatesAFloor(FakeSurface surface) {
        return ModelCapabilityDeclaration.builder().supportsSamplingParameters(surface.getSupportsSamplingParameters())
                .supportsReasoningEffort(surface.getSupportsReasoningEffort())
                .thinkingDialect(surface.getThinkingDialect()).lowestReasoningEffort(ReasoningEffort.NONE)
                .acceptedReasoningEfforts(rungs(surface)).build();
    }

    private static Set<ReasoningEffort> rungs(FakeSurface surface) {
        return surface.getAcceptedReasoningEfforts() == null
                ? null
                : EnumSet.copyOf(surface.getAcceptedReasoningEfforts());
    }

    /** A configuration surface of four keys, typed the way the two real ones are. */
    static final class FakeSurface {
        private Boolean supportsSamplingParameters;
        private Boolean supportsReasoningEffort;
        private ThinkingDialect thinkingDialect;
        private List<ReasoningEffort> acceptedReasoningEfforts;

        public Boolean getSupportsSamplingParameters() {
            return supportsSamplingParameters;
        }

        public void setSupportsSamplingParameters(Boolean supportsSamplingParameters) {
            this.supportsSamplingParameters = supportsSamplingParameters;
        }

        public Boolean getSupportsReasoningEffort() {
            return supportsReasoningEffort;
        }

        public void setSupportsReasoningEffort(Boolean supportsReasoningEffort) {
            this.supportsReasoningEffort = supportsReasoningEffort;
        }

        public ThinkingDialect getThinkingDialect() {
            return thinkingDialect;
        }

        public void setThinkingDialect(ThinkingDialect thinkingDialect) {
            this.thinkingDialect = thinkingDialect;
        }

        public List<ReasoningEffort> getAcceptedReasoningEfforts() {
            return acceptedReasoningEfforts;
        }

        public void setAcceptedReasoningEfforts(List<ReasoningEffort> acceptedReasoningEfforts) {
            this.acceptedReasoningEfforts = acceptedReasoningEfforts;
        }
    }

    /** A property a binder can fill and nothing can read. */
    static final class WriteOnlySurface {
        private ThinkingDialect thinkingDialect;

        public void setThinkingDialect(ThinkingDialect thinkingDialect) {
            this.thinkingDialect = thinkingDialect;
        }
    }
}
