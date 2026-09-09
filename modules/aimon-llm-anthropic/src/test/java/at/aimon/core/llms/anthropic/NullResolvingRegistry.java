package at.aimon.core.llms.anthropic;

import java.util.Optional;

import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;

/**
 * A registry that overrides {@code resolve()} to break its own never-null contract.
 *
 * <p>
 * A named class rather than a lambda because {@code resolve} is a {@code default} method: a lambda can only supply
 * {@code capabilitiesOf}, whose null the default implementation already absorbs. The OpenAI module carries an
 * identical fixture for the identical reason — the two are not shared because a test fixture crossing a module
 * boundary would put one module's test source on the other's compile classpath.
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
