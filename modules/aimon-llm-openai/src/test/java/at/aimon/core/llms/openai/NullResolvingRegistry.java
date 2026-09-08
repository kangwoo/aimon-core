package at.aimon.core.llms.openai;

import java.util.Optional;

import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;

/**
 * A registry that overrides {@code resolve()} to break its own never-null contract.
 *
 * <p>
 * A named class rather than a lambda because {@code resolve} is a {@code default} method: a lambda can only supply
 * {@code capabilitiesOf}, whose null the default implementation already absorbs. It is a shared fixture rather than a
 * nested one because two tests in this package need the same misbehaviour — {@code OpenAILlmClientModelCapabilityTest}
 * asserts the request keeps its shape, {@code OpenAILlmClientParameterDivergenceTest} asserts the degradation is
 * reported — and a second hand-written copy is a second thing to keep in step with the contract it breaks.
 */
final class NullResolvingRegistry implements ModelCapabilityRegistry {

    @Override
    public Optional<ModelCapabilities> capabilitiesOf(String modelName) {
        return Optional.empty();
    }

    @Override
    public ModelCapabilities resolve(String modelName) {
        return null;
    }
}
