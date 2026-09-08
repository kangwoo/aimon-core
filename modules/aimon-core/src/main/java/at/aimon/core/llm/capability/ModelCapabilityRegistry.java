package at.aimon.core.llm.capability;

import java.util.Optional;

/**
 * Look-up of a model's {@link ModelCapabilities} by model name.
 *
 * <p>
 * A capability registry is a stateless, thread-safe strategy that maps a model identifier (e.g. {@code "gpt-4o"},
 * {@code "gpt-5.6-terra"}) to what a client may do when building a request for it, or reports that the model is
 * unknown. It is the single seam through which model knowledge reaches a provider: no provider may branch on a model
 * name of its own.
 *
 * <p>
 * <strong>Two methods, because they answer to two different people.</strong> {@link #capabilitiesOf} is what an
 * implementer writes — a look-up that can miss, so the miss case is type-directed rather than remembered.
 * {@link #resolve} is what a caller reads — one non-null answer, and the single place the fail-open rule lives, so it
 * is also the single place a test can pin it. An implementation must never answer a miss with a restrictive
 * descriptor; that turns fail-open into fail-closed for every model it has not heard of.
 *
 * <p>
 * The default implementation is {@link InMemoryModelCapabilityRegistry}. Alternative implementations (an Azure
 * deployment look-up, a config-backed table, a remote catalogue, ...) can be swapped in without touching callers —
 * which is the point, because gateways and Azure deployments rename models freely and no built-in table can know those
 * names.
 *
 * @see InMemoryModelCapabilityRegistry
 * @see ModelCapabilities
 */
public interface ModelCapabilityRegistry {

    /**
     * A registry that knows nothing. Every model resolves to {@link ModelCapabilities#unknown()}, i.e. every request
     * keeps the shape it had before this SPI existed.
     */
    ModelCapabilityRegistry EMPTY = modelName -> Optional.empty();

    /**
     * Resolves what this registry knows about the given model.
     *
     * @param modelName
     *            the model identifier (may be null or empty, in which case the result is empty)
     * @return the capabilities if known, otherwise {@link Optional#empty()}
     */
    Optional<ModelCapabilities> capabilitiesOf(String modelName);

    /**
     * Total, fail-open view of {@link #capabilitiesOf}: a model this registry does not know resolves to
     * {@link ModelCapabilities#unknown()} rather than to a restriction.
     *
     * <p>
     * A {@code null} return from {@link #capabilitiesOf} is treated as a miss, so a misbehaving implementation
     * degrades to today's behaviour instead of an NPE inside a provider's request builder.
     *
     * @param modelName
     *            the model identifier (may be null or empty)
     * @return the resolved capabilities (never null)
     */
    default ModelCapabilities resolve(String modelName) {
        final Optional<ModelCapabilities> found = capabilitiesOf(modelName);
        return found == null || found.isEmpty() ? ModelCapabilities.unknown() : found.get();
    }
}
