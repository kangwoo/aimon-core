/**
 * Per-model capability descriptors: what a client may put on a request for a given model.
 *
 * <p>
 * This package answers one question no other type in the tree could — "what does <em>this</em> model accept?" — and it
 * exists because several vendors reject a parameter by its <em>presence</em>, so a provider that always sets
 * {@code temperature} cannot talk to them at all. The alternative, a {@code model.startsWith(...)} branch inside a
 * provider's request builder, puts vendor knowledge somewhere the other providers cannot reach and gets the answer
 * wrong for every gateway that renames a model.
 *
 * <p>
 * Two rules govern everything here.
 *
 * <ul>
 * <li><strong>Fail open.</strong> A model no registry describes resolves to
 * {@link at.aimon.core.llm.capability.ModelCapabilities#unknown()}, which is not "all permissions granted" but
 * "nothing the caller asked for is withheld, and nothing the caller did not ask for is invented". An unknown model is
 * never refused and never has a parameter withheld — nor does it receive one nobody set.
 * <li><strong>Overridable.</strong> The built-in table
 * ({@link at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry#withDefaults()}) knows models by their real
 * names. Azure deployments and OpenAI-compatible gateways rename models freely, so an operator on one of those must be
 * able to say what their deployment really is —
 * {@link at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry#builderWithDefaults()} extends the table in one
 * line, and a provider config takes the registry.
 * </ul>
 *
 * <p>
 * The consequence of the two together is worth stating plainly: a renamed deployment stays on the fail-open path, so
 * whatever the built-in entry would have fixed is <em>still broken</em> for it until someone registers its name. That
 * is the price of never guessing, and the one-line remedy above is the whole of it.
 *
 * <p>
 * {@link at.aimon.core.llm.capability.ModelCapabilityRegistry} sits beside
 * {@link at.aimon.core.llm.ModelContextWindowRegistry} and {@link at.aimon.core.llm.cost.ModelPriceTable} rather than
 * folding into either: three per-model facts, three sources of truth, three reasons to change.
 */
package at.aimon.core.llm.capability;
