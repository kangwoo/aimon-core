package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningEffort;

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
    @DisplayName("each of the seven keys round-trips")
    void everyFlagRoundTrips() {
        final ModelCapabilityDeclaration declaration = ModelCapabilityDeclaration.builder()
                .supportsSamplingParameters(false).supportsReasoningEffort(true).supportsToolsWithReasoning(false)
                .supportsReasoningTraceRoundTrip(true).lowestReasoningEffort(ReasoningEffort.LOW)
                .thinkingDialect(ThinkingDialect.ADAPTIVE).build();

        assertThat(declaration.supportsSamplingParameters()).contains(false);
        assertThat(declaration.supportsReasoningEffort()).contains(true);
        assertThat(declaration.supportsToolsWithReasoning()).contains(false);
        assertThat(declaration.supportsReasoningTraceRoundTrip()).contains(true);
        assertThat(declaration.lowestReasoningEffort()).contains(ReasoningEffort.LOW);
        assertThat(declaration.thinkingDialect()).contains(ThinkingDialect.ADAPTIVE);
        assertThat(declaration.capabilities()).isEqualTo(ModelCapabilities.builder().supportsSamplingParameters(false)
                .supportsReasoningEffort(true).supportsToolsWithReasoning(false).supportsReasoningTraceRoundTrip(true)
                .lowestReasoningEffort(ReasoningEffort.LOW).thinkingDialect(ThinkingDialect.ADAPTIVE).build());
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
