package at.aimon.cli.factory;

import org.junit.jupiter.api.DisplayName;

import at.aimon.cli.config.ModelCapabilityConfig;
import at.aimon.core.llm.capability.ModelCapabilityDeclaration;
import at.aimon.llm.capability.testkit.AbstractModelCapabilityBindingContractTest;

/**
 * The CLI's subject for {@link AbstractModelCapabilityBindingContractTest}: every key
 * {@link ModelCapabilityDeclaration} accepts has a yaml key on {@link ModelCapabilityConfig}, and a value written there
 * reaches the declaration {@link LlmClientFactory} builds from it.
 *
 * <p>
 * #69 was the absence of the first half — {@code thinkingDialect} was a fully built declaration key that no surface
 * bound, so the refusal message advertising it sent a CLI operator into a boot failure. #82 was the absence of the
 * second: a key can bind here and still be dropped by {@code declarationOf}, the one line per key written by hand.
 * What is asserted, and why one contract serves this surface and the starter's instead of a copy in each module, is
 * written on the base class.
 *
 * <p>
 * This class sits in {@code at.aimon.cli.factory} rather than beside {@link ModelCapabilityConfig} because the
 * forwarding does: {@code declarationOf} is package-private for the reason {@code openAiConfig} is. The factory
 * rethrows the core's refusal of an empty declaration inside a {@code ConfigurationException} carrying the yaml key;
 * the probe looks through that wrapper rather than relying on the exception's type.
 */
@DisplayName("ModelCapabilityConfig - every declarable key is bound, and its value reaches the declaration")
class ModelCapabilityConfigBindingTest extends AbstractModelCapabilityBindingContractTest<ModelCapabilityConfig> {

    /** Any name will do: on this surface the model name is the map key, and the forwarding uses it only in messages. */
    private static final String MODEL = "probe-model";

    private final LlmClientFactory factory = new LlmClientFactory();

    @Override
    protected ModelCapabilityConfig newSurface() {
        return new ModelCapabilityConfig();
    }

    @Override
    protected ModelCapabilityDeclaration forward(ModelCapabilityConfig surface) {
        return factory.declarationOf(MODEL, surface);
    }

    @Override
    protected String operatorKeyPath(String key) {
        return "llm.modelCapabilities.<model>." + key;
    }

    @Override
    protected String forwardingLocation() {
        return "LlmClientFactory.declarationOf";
    }
}
