package at.aimon.bootstrap.assemble;

import java.util.Objects;
import java.util.Optional;

import at.aimon.bootstrap.RuntimeDegradations;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.core.agent.impl.orca.tool.OrcaMemoryToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.MemoryContextProvider;
import at.aimon.core.memory.MemoryPeerResolver;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.SnapshotMemoryContextProvider;
import at.aimon.core.tools.memory.MemoryToolContextEnricher;

/**
 * Turns a {@link MemorySpec} into the three things the stack actually installs: the provider that injects a memory
 * part into every execution's system prompt, the enricher that puts the workspace and observer into every tool's
 * context, and the provider that registers the memory tools.
 *
 * <p>
 * The three are produced together because they are decided together. Which of them exists follows from the spec —
 * a representation store produces the injected part, a fixed peer produces the enricher, and the tools need both a
 * store and that enricher — and getting one without the others is the failure mode worth preventing: tools with no
 * observer in context answer "no workspace in context" to every call, and an injected part with no writer is empty
 * forever. Whatever this class leaves out, it says so through {@link RuntimeDegradations}.
 *
 * <p>
 * Nothing here is closed by the stack. The stores are supplied by the caller and outlive the assembly ("만든 쪽이
 * 닫는다").
 */
public final class MemoryAssembly {

    /** Degradation key: the stack reads memory but nothing in it writes memory. */
    public static final String CAPABILITY_WRITE_PATH = "memory-write-path";

    /** Degradation key: memory is injected into the prompt but the memory tools are not registered. */
    public static final String CAPABILITY_TOOLS = "memory-tools";

    /** Degradation key: the observation tools run without a redaction policy. */
    public static final String CAPABILITY_REDACTION = "memory-redaction";

    private static final MemoryAssembly DISABLED = new MemoryAssembly(null, null, null);

    private final MemoryContextProvider contextProvider;
    private final ToolContextEnricher contextEnricher;
    private final OrcaToolProvider toolProvider;

    private MemoryAssembly(MemoryContextProvider contextProvider, ToolContextEnricher contextEnricher,
            OrcaToolProvider toolProvider) {
        this.contextProvider = contextProvider;
        this.contextEnricher = contextEnricher;
        this.toolProvider = toolProvider;
    }

    /**
     * Returns the assembly for a stack with no memory at all.
     *
     * @return a shared empty assembly
     */
    public static MemoryAssembly disabled() {
        return DISABLED;
    }

    /**
     * Assembles the memory components a spec calls for, recording what it does not get.
     *
     * @param spec
     *            the spec, or {@code null} when the stack has no memory
     * @param degradations
     *            the collector the builder is filling (must not be null)
     * @return the assembly; {@link #disabled()} when {@code spec} is null
     */
    public static MemoryAssembly from(MemorySpec spec, RuntimeDegradations.Collector degradations) {
        Objects.requireNonNull(degradations, "degradations must not be null");
        if (spec == null) {
            return DISABLED;
        }

        // A fixed peer answers every execution; per-caller reads the principal off the execution and returns nothing
        // for the ones that arrive without one. The spec makes this an either/or so no deployment can end up with
        // half of each — see MemorySpec and MemoryPeerResolver.
        final Optional<Principal> fixedPeer = spec.getFixedPeer();
        final MemoryPeerResolver peerResolver = fixedPeer.map(MemoryPeerResolver::fixed)
                .orElseGet(MemoryPeerResolver::caller);

        final MemoryContextProvider contextProvider = spec.getRepresentationStore()
                .map(store -> (MemoryContextProvider) new SnapshotMemoryContextProvider(
                        SnapshotMemoryContextProvider.readerOver(store), spec.getWorkspace(), peerResolver,
                        spec.getInjectionMode(), spec.getMaxTokens()))
                .orElse(null);

        // One observer for the whole runtime, so it exists only in fixed-peer mode. The enrichment info a
        // ToolContextEnricher receives carries a session and an execution but no principal, so there is nothing to
        // resolve a per-call observer from — this is the seam that would have to widen before the memory tools can
        // serve a multi-caller deployment.
        final ToolContextEnricher contextEnricher = fixedPeer
                .map(peer -> (ToolContextEnricher) new MemoryToolContextEnricher(spec.getWorkspace(),
                        PeerView.of(spec.getWorkspace(), peer)))
                .orElse(null);

        final OrcaToolProvider toolProvider = contextEnricher == null
                ? null
                : new OrcaMemoryToolProvider(spec.getRepresentationStore().orElse(null),
                        spec.getObservationStore().orElse(null), spec.getRedactionPolicy().orElse(null));

        recordDegradations(spec, toolProvider, degradations);
        return new MemoryAssembly(contextProvider, contextEnricher, toolProvider);
    }

    private static void recordDegradations(MemorySpec spec, OrcaToolProvider toolProvider,
            RuntimeDegradations.Collector degradations) {
        // The one every memory deployment hits, and the one whose symptom is silence: the read path is wired, so
        // everything looks configured, and the injected part stays empty until something else fills the store.
        degradations.add(CAPABILITY_WRITE_PATH,
                "The stack wires the memory read path only — no deriver, derivation queue or dreamer runs in it."
                        + " Representations and observations must be written by something else against the same"
                        + " store, or MemoryRecall and the injected memory part stay empty for the life of the"
                        + " process.");
        if (toolProvider == null) {
            degradations.add(CAPABILITY_TOOLS,
                    "Per-caller memory injects a memory part into the prompt but registers no memory tools: they read"
                            + " their observer from the tool context, and the enricher that supplies it binds one"
                            + " fixed peer. The model can be told what memory says and cannot query or add to it.");
        } else if (spec.getObservationStore().isPresent() && spec.getRedactionPolicy().isEmpty()) {
            degradations.add(CAPABILITY_REDACTION,
                    "The Observe and MemorySearch tools run without a redaction policy, so whatever the model is told"
                            + " to observe is persisted verbatim — including anything secret that reached the"
                            + " conversation.");
        }
    }

    /**
     * Returns the provider that contributes a memory part to each execution's system prompt.
     *
     * @return the provider, or empty when no representation store was configured
     */
    public Optional<MemoryContextProvider> getContextProvider() {
        return Optional.ofNullable(contextProvider);
    }

    /**
     * Returns the enricher that puts the workspace, observer and session into every tool's context.
     *
     * @return the enricher, or empty in per-caller mode
     */
    public Optional<ToolContextEnricher> getContextEnricher() {
        return Optional.ofNullable(contextEnricher);
    }

    /**
     * Returns the provider that registers the memory tools on every runtime.
     *
     * @return the provider, or empty when the tools cannot be given an observer
     */
    public Optional<OrcaToolProvider> getToolProvider() {
        return Optional.ofNullable(toolProvider);
    }

    @Override
    public String toString() {
        return "MemoryAssembly[injection=" + (contextProvider != null) + ", enricher=" + (contextEnricher != null)
                + ", tools=" + (toolProvider != null) + "]";
    }
}
