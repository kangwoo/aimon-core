package at.aimon.spring.boot.autoconfigure;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;

import at.aimon.core.llm.capability.ModelCapabilityDeclaration;
import at.aimon.llm.capability.testkit.AbstractModelCapabilityBindingContractTest;

/**
 * The starter's subject for {@link AbstractModelCapabilityBindingContractTest}: every key
 * {@link ModelCapabilityDeclaration} accepts has a property on {@link AimonProperties.ModelCapabilityProperties}, and a
 * value written there reaches the declaration {@code toDeclaration()} builds from it.
 *
 * <p>
 * #69 was the absence of the first half — {@code thinkingDialect} was a fully built declaration key with no property
 * here, so the refusal message advertising it was advice an operator could not act on, and silently so on this
 * surface, because Boot ignores an unknown property (backlog {@code L-1}). #82 was the absence of the second: a key can
 * bind here and still be dropped by {@code toDeclaration()}, the one line per key written by hand. What is asserted,
 * and why one contract serves this surface and the CLI's instead of a copy in each module, is written on the base
 * class.
 *
 * <p>
 * Written as its own class rather than folded into {@code AimonAutoConfigurationTest} so that a failure says which key
 * is unbound or dropped rather than which context failed to start. The operator spelling is derived here, not in the
 * contract: kebab-case is Boot's naming rule, and the contract should not learn one surface's binder conventions.
 */
@DisplayName("AimonProperties.ModelCapabilityProperties - every declarable key is bound, and its value reaches the declaration")
class AimonPropertiesBindingCoverageTest
        extends
            AbstractModelCapabilityBindingContractTest<AimonProperties.ModelCapabilityProperties> {

    @Override
    protected AimonProperties.ModelCapabilityProperties newSurface() {
        return new AimonProperties.ModelCapabilityProperties();
    }

    @Override
    protected ModelCapabilityDeclaration forward(AimonProperties.ModelCapabilityProperties surface) {
        return surface.toDeclaration();
    }

    @Override
    protected String operatorKeyPath(String key) {
        return "aimon.llm.model-capabilities.<model>." + kebab(key);
    }

    @Override
    protected String forwardingLocation() {
        return "AimonProperties.ModelCapabilityProperties.toDeclaration";
    }

    private static String kebab(String camelCase) {
        return camelCase.replaceAll("([A-Z])", "-$1").toLowerCase(Locale.ROOT);
    }
}
