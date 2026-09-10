package at.aimon.llm.capability.testkit;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import at.aimon.core.llm.capability.ModelCapabilityDeclaration;

/**
 * What a configuration surface owes {@link ModelCapabilityDeclaration}, executed against each surface: every
 * declarable key has a property on the surface, and a value written to that property reaches the declaration the
 * surface's own forwarding produces.
 *
 * <h2>The two checks</h2>
 *
 * <ol>
 * <li><b>The surface carries the key</b> — #69. {@code thinkingDialect} was a fully built declaration key, advertised
 * by the declaration's own refusal message, that no surface bound.
 * <li><b>The value reaches the declaration</b> — #82. #69's guards collected the builder's setters and the surface's
 * properties and asserted the second contained the first, one step short of the only code written by hand for every
 * key: {@code LlmClientFactory.declarationOf} on the CLI and {@code ModelCapabilityProperties.toDeclaration()} in the
 * starter. A ninth key with a getter and a setter on both surfaces and no forwarding call would bind, produce no
 * message, and never reach the declaration — with both guards green.
 * </ol>
 *
 * <p>
 * They stay two cases although the second subsumes the first — a write fails where there is no setter — because the
 * two failures are different jobs: "this surface has no key" means add a property, "this surface drops the key" means
 * add a line to the forwarding. The first also demands a getter, which the second alone would not: a write-only
 * property binds and leaves the forwarding nothing to read.
 *
 * <p>
 * Keys come from {@link DeclarableKeys} and values from {@link ProbeValues}, both read off the builder, so a new key is
 * covered with no edit to this module or to either subclass. Values are obtained inside the test method rather than in
 * the {@code @MethodSource}, so {@link #valuesFor(String)} is honoured and a key whose type cannot be synthesised fails
 * its own case and no other.
 *
 * <h2>One contract, two subjects — and not two guards</h2>
 *
 * <p>
 * #69 put one guard in each surface module and wrote down why: the check cannot live in {@code aimon-core}, which sees
 * neither surface. That is still true. It does not follow that the check needs two copies — both surface modules can
 * see a third, which is what the filesystem, session and memory testkits already are for their contracts.
 *
 * <p>
 * The reason given for keeping two — the CLI binds enums, the starter strings — is not true at the surface. Measured
 * when this module was written, both surfaces declare the same type for every key; the difference is real only in the
 * binders in front of them (Jackson converts a yaml scalar, Boot a property string), and this contract does not touch
 * the binders. Should a surface bind a string again, {@link SurfaceWriter} already converts to one.
 *
 * <p>
 * And two copies of a guard drift the way two copies of a rule do, only more quietly: a weakened copy still reports
 * success, which is the exact failure the second check exists for. What differs per surface is the four hooks below;
 * everything else is here. There is still one test class per surface module, so a failure still names its module.
 *
 * <h2>What it does not reach</h2>
 *
 * <p>
 * The probe writes through bean setters, so neither binder runs. That a yaml key or a property string arrives on the
 * surface at all is covered key by key, by hand, in {@code LlmClientFactoryTest} and
 * {@code AimonPropertiesValidationTest} — and that it is by hand is open, as {@code L-14} in
 * {@code docs/backlog/llm-config-surface-open-items.md}. Design: {@code docs/design/llm/}
 * {@code model-capability-binding-round-trip.md}.
 *
 * @param <S>
 *            the configuration surface's type
 */
public abstract class AbstractModelCapabilityBindingContractTest<S> {

    /** Where both checks draw their keys from. */
    private static final String KEYS = "at.aimon.llm.capability.testkit.DeclarableKeys#names";

    /**
     * @return a fresh, empty instance of the configuration surface under test
     */
    protected abstract S newSurface();

    /**
     * Runs this surface's own hand-written forwarding — the step #82 is about.
     *
     * @param surface
     *            a surface with exactly one key written
     * @return the declaration the forwarding produces
     */
    protected abstract ModelCapabilityDeclaration forward(S surface);

    /**
     * @param key
     *            a declarable key
     * @return the key as an operator writes it on this surface, for failure messages —
     *         {@code llm.modelCapabilities.<model>.thinkingDialect}
     */
    protected abstract String operatorKeyPath(String key);

    /**
     * @return where the forwarding lives, for failure messages — {@code "LlmClientFactory.declarationOf"}
     */
    protected abstract String forwardingLocation();

    /**
     * A hand-picked pair for a key whose synthesised values this surface legitimately refuses — a future string key
     * with a format, say. There is no answer that means "skip": the pair is held to every rule a synthesised one is
     * (see {@link ModelCapabilityBindingProbe}), so an empty or one-element list fails.
     *
     * @param key
     *            a declarable key
     * @return the two values to probe {@code key} with, or empty to use {@link ProbeValues}
     */
    protected Optional<List<Object>> valuesFor(String key) {
        return Optional.empty();
    }

    private ModelCapabilityBindingProbe<S> probe() {
        return ModelCapabilityBindingProbe.forSurface(this::newSurface).forwarding(this::forward)
                .operatorKeyPath(this::operatorKeyPath).forwardingLocation(forwardingLocation()).build();
    }

    @Nested
    @DisplayName("every declarable key has a property on this surface (#69)")
    class EveryKeyIsBound {

        @ParameterizedTest(name = "{0}")
        @MethodSource(KEYS)
        @DisplayName("the surface carries a readable and writable property for the key")
        void theSurfaceCarriesTheKey(String key) {
            probe().assertSurfaceCarries(key);
        }
    }

    @Nested
    @DisplayName("every declarable key's value reaches the declaration (#82)")
    class EveryValueIsForwarded {

        @ParameterizedTest(name = "{0}")
        @MethodSource(KEYS)
        @DisplayName("the value written for the key reaches the declaration")
        void theValueReachesTheDeclaration(String key) {
            final List<Object> values = valuesFor(key).orElseGet(() -> ProbeValues.distinctPairFor(key));
            probe().assertValuesReachTheDeclaration(key, values);
        }
    }
}
