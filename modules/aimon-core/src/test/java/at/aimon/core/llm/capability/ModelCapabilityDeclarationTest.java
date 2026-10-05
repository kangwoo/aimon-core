package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import at.aimon.core.llm.ReasoningEffort;
import at.aimon.llm.capability.testkit.DeclarableKeys;
import at.aimon.llm.capability.testkit.ProbeValues;

/**
 * What a configured capability entry means, in the one type both configuration surfaces translate into.
 *
 * <p>
 * The property under test throughout is that an omitted flag is <em>not</em> {@code false}. That is the whole reason
 * this type is boxed where {@link ModelCapabilities} is not, and it is what makes the one-line form of the entry — the
 * form the issue's own reproduction uses — a safe thing to write.
 */
@DisplayName("ModelCapabilityDeclaration - partial declarations")
class ModelCapabilityDeclarationTest {

    @Test
    @DisplayName("a declaration that names one flag changes that one flag and nothing else")
    void onePartialFlagLeavesTheRestFailOpen() {
        final ModelCapabilityDeclaration declaration = ModelCapabilityDeclaration.builder()
                .supportsSamplingParameters(false).build();

        // Asserted as equality against a hand-built ModelCapabilities rather than flag by flag, because the claim is
        // exactly "this equals calling only the setter that was declared" -- and stated that way it also fails if a
        // sixth flag is added and defaulted differently here than in the builder.
        assertThat(declaration.capabilities())
                .isEqualTo(ModelCapabilities.builder().supportsSamplingParameters(false).build());
        assertThat(declaration.capabilities().supportsReasoningEffort())
                .isEqualTo(ModelCapabilities.unknown().supportsReasoningEffort());
        assertThat(declaration.capabilities().supportsToolsWithReasoning())
                .isEqualTo(ModelCapabilities.unknown().supportsToolsWithReasoning());
        assertThat(declaration.capabilities().supportsReasoningTraceRoundTrip())
                .isEqualTo(ModelCapabilities.unknown().supportsReasoningTraceRoundTrip());
        assertThat(declaration.capabilities().acceptedReasoningEfforts())
                .isEqualTo(ModelCapabilities.unknown().acceptedReasoningEfforts());
        assertThat(declaration.capabilities().thinkingDialect())
                .isEqualTo(ModelCapabilities.unknown().thinkingDialect());
        assertThat(declaration.capabilities().supportsReasoningSummary())
                .isEqualTo(ModelCapabilities.unknown().supportsReasoningSummary());
    }

    @Test
    @DisplayName("the minimal declaration keeps a deployment on Chat Completions")
    void theMinimalDeclarationDoesNotRouteAnywhereNew() {
        // The reason all five are not required. An operator who knows only that temperature earns a 400 writes one
        // line; if that line silently implied a reasoning-trace round trip, the model would be routed at an endpoint a
        // Chat-only gateway does not have and the 400 would become a 404.
        assertThat(ModelCapabilityDeclaration.builder().supportsSamplingParameters(false).build().capabilities()
                .supportsReasoningTraceRoundTrip()).isFalse();
    }

    @Test
    @DisplayName("each of the eight keys round-trips")
    void everyFlagRoundTrips() {
        final ModelCapabilityDeclaration declaration = ModelCapabilityDeclaration.builder()
                .supportsSamplingParameters(false).supportsReasoningEffort(true).supportsToolsWithReasoning(false)
                .supportsReasoningTraceRoundTrip(true).lowestReasoningEffort(ReasoningEffort.LOW)
                .thinkingDialect(ThinkingDialect.ADAPTIVE).supportsReasoningSummary(false).build();

        assertThat(declaration.supportsSamplingParameters()).contains(false);
        assertThat(declaration.supportsReasoningEffort()).contains(true);
        assertThat(declaration.supportsToolsWithReasoning()).contains(false);
        assertThat(declaration.supportsReasoningTraceRoundTrip()).contains(true);
        assertThat(declaration.lowestReasoningEffort()).contains(ReasoningEffort.LOW);
        assertThat(declaration.thinkingDialect()).contains(ThinkingDialect.ADAPTIVE);
        assertThat(declaration.supportsReasoningSummary()).contains(false);
        assertThat(declaration.capabilities()).isEqualTo(ModelCapabilities.builder().supportsSamplingParameters(false)
                .supportsReasoningEffort(true).supportsToolsWithReasoning(false).supportsReasoningTraceRoundTrip(true)
                .lowestReasoningEffort(ReasoningEffort.LOW).thinkingDialect(ThinkingDialect.ADAPTIVE)
                .supportsReasoningSummary(false).build());
    }

    @Test
    @DisplayName("declaring the reasoning summary alone is a whole declaration, and it gates only that parameter")
    void theReasoningSummaryIsDeclarableOnItsOwn() {
        // The gateway case #72 exists for: an endpoint that takes reasoning.effort and 400s on reasoning.summary.
        // One key describes it, and everything else about the model stays where it was -- in particular the trace
        // round trip, which is what routed the model to the endpoint that has the parameter in the first place.
        final ModelCapabilityDeclaration declaration = ModelCapabilityDeclaration.builder()
                .supportsReasoningSummary(false).build();

        assertThat(declaration.capabilities())
                .isEqualTo(ModelCapabilities.builder().supportsReasoningSummary(false).build());
        assertThat(declaration.capabilities().supportsReasoningSummary()).isFalse();
        assertThat(declaration.capabilities().supportsReasoningTraceRoundTrip())
                .isEqualTo(ModelCapabilities.unknown().supportsReasoningTraceRoundTrip());
    }

    @Test
    @DisplayName("the refusal message names every key the declaration type accepts")
    void theRefusalMessageNamesEveryDeclarableKey() {
        // #69 is the shape where a message advertises a key nothing else in the system supplies. This test cannot
        // see either configuration surface -- aimon-core does not depend on aimon-cli or the starter -- so it is a
        // message-vs-declaration consistency check and nothing more: it fails when a ninth key is added to the
        // builder and its name never reaches the sentence an operator reads. The surface half of that guard is one
        // test per surface module (ModelCapabilityConfigBindingTest, AimonPropertiesBindingCoverageTest).
        final String message = catchThrowableOfType(() -> ModelCapabilityDeclaration.builder().build(),
                IllegalArgumentException.class).getMessage();

        final List<String> setters = Arrays.stream(ModelCapabilityDeclaration.Builder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getReturnType() == ModelCapabilityDeclaration.Builder.class)
                .map(Method::getName).sorted().toList();

        assertThat(setters).hasSize(8);
        assertThat(message).contains(setters);
    }

    /**
     * Every declarable key reaches the descriptor a client reads — the third hand-written hand-off, and the one the
     * two surface contracts deliberately stop short of (backlog {@code L-13}).
     *
     * <p>
     * {@code resolve(Builder)} copies one key per line. A ninth key added to the builder and forgotten there leaves
     * the declaration right, both surface guards green, and the descriptor silently without it. Nothing here is
     * written per key: the keys come from the declaration builder itself and the values are synthesised from each
     * setter's parameter type, by the same two helpers the surface contracts use, so "every declarable key" means
     * one thing across all three links.
     *
     * <p>
     * The expectation is what {@link ModelCapabilities#builder()} yields when <em>its</em> setter of the same name
     * is called with the same value. That same-name rule is checked rather than assumed: a key with no counterpart
     * fails by name instead of being skipped. Two values per key, and they must produce two different descriptors —
     * which is what keeps the equality from being vacuous. With one value, a dropped {@code Boolean} key would pass
     * whenever the probe happened to pick the fail-open default, and a field left out of
     * {@code ModelCapabilities.equals} would pass always.
     *
     * <p>
     * <strong>The ladder pair is not special-cased.</strong> {@code lowestReasoningEffort} and
     * {@code acceptedReasoningEfforts} fold into one field of the descriptor, but both have a same-named setter
     * there that performs the fold, so each goes through the general rule with the fold on the expected side too.
     * What that leaves unchecked is the two written <em>together</em>, which is not a hole: {@code build()} refuses
     * the pair, and {@code bothLadderKeysAreRefused} pins that.
     */
    @TestFactory
    @DisplayName("every declarable key reaches the descriptor, with no key named in this test")
    Stream<DynamicTest> everyDeclarableKeyReachesTheDescriptor() {
        return DeclarableKeys.names().stream().map(key -> DynamicTest.dynamicTest(key, () -> {
            final List<ModelCapabilities> resolved = new ArrayList<>();
            for (Object value : ProbeValues.distinctPairFor(key)) {
                final ModelCapabilities actual = DeclarableKeys.expectedDeclaration(key, value).capabilities();

                assertThat(actual)
                        .as("`%s` was declared as %s and nothing else, but capabilities() is not what ModelCapabilities"
                                + ".builder().%s(%s).build() yields. ModelCapabilityDeclaration.resolve(Builder)"
                                + " copies the keys one line each: the line for `%s` is missing or forwards something"
                                + " other than the declared value. An operator who writes this key gets a request"
                                + " built as if they had not.", key, value, key, value, key)
                        .isEqualTo(descriptorWithOnly(key, value));
                resolved.add(actual);
            }

            assertThat(resolved.get(0))
                    .as("`%s` declared as two different values gave two equal descriptors, so the equality above proves"
                            + " nothing for this key: either ModelCapabilities.Builder#%s ignores its argument, or"
                            + " ModelCapabilities.equals does not compare what it sets.", key, key)
                    .isNotEqualTo(resolved.get(1));
        }));
    }

    /**
     * The descriptor {@link ModelCapabilities#builder()} yields when only the setter named {@code key} is called.
     *
     * <p>
     * Looked up by name alone, so a boxed declaration value meets the descriptor's primitive parameter through
     * reflection's own unboxing and nothing here knows which keys are booleans.
     */
    private static ModelCapabilities descriptorWithOnly(String key, Object value) {
        final List<Method> setters = Arrays.stream(ModelCapabilities.Builder.class.getMethods())
                .filter(method -> method.getName().equals(key)).filter(method -> method.getParameterCount() == 1)
                .toList();
        assertThat(setters).as(
                "ModelCapabilityDeclaration.Builder declares the key `%s`, and this guard expects ModelCapabilities"
                        + ".Builder to have exactly one single-argument setter of that name to compare against. If the"
                        + " key is deliberately spelled differently on the descriptor, say how it maps here -- it is"
                        + " not skipped, because a key this guard cannot compare is a key resolve(Builder) can drop.",
                key).hasSize(1);
        final ModelCapabilities.Builder builder = ModelCapabilities.builder();
        try {
            setters.get(0).invoke(builder, value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("ModelCapabilities.Builder#" + key + " refused the probe value " + value, e);
        }
        return builder.build();
    }

    @Test
    @DisplayName("the ladder can be declared as a set, and that is the form with a gap in it")
    void theLadderIsDeclarableAsASet() {
        // The invariant that made this key necessary: the built-in gpt-5.6-terra row states a ladder no floor
        // describes, and a surface that cannot express a shipped row cannot describe a deployment that renamed it.
        final ModelCapabilityDeclaration declaration = ModelCapabilityDeclaration.builder().acceptedReasoningEfforts(
                EnumSet.of(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH))
                .build();

        assertThat(declaration.acceptedReasoningEfforts()).contains(
                EnumSet.of(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH));
        assertThat(declaration.lowestReasoningEffort()).isEmpty();
        assertThat(declaration.capabilities().acceptedReasoningEfforts()).containsExactly(ReasoningEffort.NONE,
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH);
    }

    @Test
    @DisplayName("declaring both ladder keys is refused, with a message naming both and which to keep")
    void bothLadderKeysAreRefused() {
        // Refused rather than resolved by precedence. A yaml file presents both keys at once with no order at all,
        // so any winner this type picked would be one the operator could not have predicted -- unlike the Java
        // builder's own last-call-wins, which reads off a sequence.
        assertThatThrownBy(() -> ModelCapabilityDeclaration.builder().lowestReasoningEffort(ReasoningEffort.LOW)
                .acceptedReasoningEfforts(EnumSet.of(ReasoningEffort.NONE, ReasoningEffort.HIGH)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lowestReasoningEffort")
                .hasMessageContaining("acceptedReasoningEfforts").hasMessageContaining("Keep acceptedReasoningEfforts");
    }

    @Test
    @DisplayName("an empty declared ladder is refused by name rather than treated as undeclared")
    void anEmptyDeclaredLadderIsRefused() {
        // What an operator writing `acceptedReasoningEfforts: []` gets. Folding it into "not declared" would make
        // something they wrote do nothing, which is the failure this whole surface removes.
        assertThatThrownBy(() -> ModelCapabilityDeclaration.builder().acceptedReasoningEfforts(Set.of()).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("acceptedReasoningEfforts");
    }

    @Test
    @DisplayName("declaring the dialect alone is a whole declaration, and declaring UNKNOWN is a real statement")
    void theDialectIsDeclarableOnItsOwn() {
        // The one flag whose declared value can equal the fail-open one and still mean something: an operator whose
        // gateway serves a budgeted model behind a claude-* name that the built-in table calls adaptive needs a way
        // to say "do not act on that row". UNKNOWN is that, and it is not the same as omitting the flag -- omission
        // would leave a built-in prefix row in force, since a declaration is registered as an exact entry.
        final ModelCapabilityDeclaration declaration = ModelCapabilityDeclaration.builder()
                .thinkingDialect(ThinkingDialect.UNKNOWN).build();

        assertThat(declaration.thinkingDialect()).contains(ThinkingDialect.UNKNOWN);
        assertThat(declaration.capabilities()).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("an undeclared flag reads as absent rather than as false")
    void undeclaredFlagsAreEmpty() {
        final ModelCapabilityDeclaration declaration = ModelCapabilityDeclaration.builder()
                .supportsReasoningEffort(true).build();

        assertThat(declaration.supportsSamplingParameters()).isEmpty();
        assertThat(declaration.lowestReasoningEffort()).isEmpty();
        assertThat(declaration.acceptedReasoningEfforts()).isEmpty();
        assertThat(declaration.thinkingDialect()).isEmpty();
        assertThat(declaration.supportsReasoningSummary()).isEmpty();
        assertThat(declaration.supportsReasoningEffort()).contains(true);
    }

    @Test
    @DisplayName("a declaration that states nothing is refused")
    void aDeclarationMustStateSomething() {
        // Such an entry registers the capabilities the model already had, so it is indistinguishable from not writing
        // it -- while the operator who wrote it believes it does something. This surface exists because a silent
        // no-op produced an HTTP 400.
        assertThatThrownBy(() -> ModelCapabilityDeclaration.builder().build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one")
                .hasMessageContaining("supportsSamplingParameters").hasMessageContaining("acceptedReasoningEfforts")
                .hasMessageContaining("thinkingDialect").hasMessageContaining("check the spelling");
    }

    @Test
    @DisplayName("explicitly clearing a flag back to null is still nothing declared")
    void nullClearsAFlag() {
        // The setters accept null because null is this type's word for "omitted"; a surface that copies five nullable
        // fields across calls all five with whatever it has.
        assertThatThrownBy(() -> ModelCapabilityDeclaration.builder().supportsSamplingParameters(false)
                .supportsSamplingParameters(null).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("equal declarations are equal")
    void equality() {
        final ModelCapabilityDeclaration one = ModelCapabilityDeclaration.builder().supportsSamplingParameters(false)
                .lowestReasoningEffort(ReasoningEffort.LOW).build();
        final ModelCapabilityDeclaration same = ModelCapabilityDeclaration.builder().supportsSamplingParameters(false)
                .lowestReasoningEffort(ReasoningEffort.LOW).build();
        final ModelCapabilityDeclaration other = ModelCapabilityDeclaration.builder().supportsSamplingParameters(false)
                .build();

        assertThat(one).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(other);
        assertThat(one.toString()).contains("supportsSamplingParameters=false").contains("lowestReasoningEffort=LOW");
    }
}
