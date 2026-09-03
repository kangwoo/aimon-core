package at.aimon.bootstrap.assemble;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.bootstrap.RuntimeDegradations;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.core.agent.impl.orca.tool.OrcaMemoryToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.ExecutionMemorySink;
import at.aimon.core.memory.IngestingExecutionMemorySink;
import at.aimon.core.memory.MemoryCapabilities;
import at.aimon.core.memory.MemoryCapability;
import at.aimon.core.memory.MemoryContextProvider;
import at.aimon.core.memory.MemoryIngestMode;
import at.aimon.core.memory.MemoryIngestor;
import at.aimon.core.memory.MemoryPeerResolver;
import at.aimon.core.memory.PeerMemory;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.RedactingPeerMemory;
import at.aimon.core.memory.SnapshotMemoryContextProvider;
import at.aimon.core.memory.StoreBackedPeerMemory;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.tools.memory.MemoryToolContextEnricher;

/**
 * Turns a {@link MemorySpec} into what the stack installs: the backend itself, the provider that injects a memory part
 * into every execution's system prompt, the enricher that puts the workspace and observer into every tool's context,
 * and the provider that registers the memory tools.
 *
 * <h2>The tool set follows from what the backend can do</h2>
 *
 * <p>
 * The decision comes from one place — {@link MemoryCapabilities#of(PeerMemory)}, which is <em>computed</em> from the
 * backend's tier accessors. A backend cannot claim a capability it does not implement, so a tool cannot be registered
 * over a tier that turns out to be absent. Whatever is missing is reported through {@link RuntimeDegradations}, one
 * key per capability, rather than left for an operator to infer from a memory that never answers.
 *
 * <p>
 * Capabilities and tools are not one-to-one, which is why the mapping is written out rather than looped over blindly:
 * SNAPSHOT feeds two consumers (the recall tool <em>and</em> the injected prompt part), INGEST feeds no tool at all
 * (its consumer is the executor seam), and {@code memory-tools} is not about a capability but about the assembly's
 * shape — whether there is a fixed observer to put in the tool context.
 *
 * <h2>Nothing unwrapped reaches the stack</h2>
 *
 * <p>
 * When a {@link RedactionPolicy} is configured, the backend is wrapped in {@link RedactingPeerMemory} and only the
 * wrapper is handed on. That is the whole guarantee: with no path to an unwrapped backend, the redaction gate is one
 * gate, and an adapter never has to remember to have one.
 *
 * <h2>Closing</h2>
 *
 * <p>
 * Nothing here is closed by the stack. Stores and a supplied backend are the caller's and outlive the assembly
 * ("만든 쪽이 닫는다"). When that changes, the object to test for {@link AutoCloseable} is
 * {@link #getPeerMemoryDelegate()} — the wrapper holds no resources, so testing it would leave an adapter's HTTP
 * client open forever.
 */
public final class MemoryAssembly {

    /**
     * Degradation key: nothing feeds conversation into memory, so it never fills.
     *
     * <p>
     * Replaces the former {@code CAPABILITY_WRITE_PATH} (value {@code "memory-write-path"}), which named a direction
     * rather than the capability whose absence it now reports.
     */
    public static final String CAPABILITY_INGEST = "memory-ingest";

    /** Degradation key: no snapshot, so no injected memory part and no {@code MemoryRecall}. */
    public static final String CAPABILITY_SNAPSHOT = "memory-snapshot";

    /** Degradation key: no search, so {@code MemorySearch} is not registered. */
    public static final String CAPABILITY_SEARCH = "memory-search";

    /** Degradation key: no dialectic, so {@code MemoryChat} is not registered. */
    public static final String CAPABILITY_CHAT = "memory-chat";

    /** Degradation key: no direct recording, so {@code Observe} is not registered. */
    public static final String CAPABILITY_OBSERVE = "memory-observe";

    /** Degradation key: memory is injected into the prompt but the memory tools are not registered. */
    public static final String CAPABILITY_TOOLS = "memory-tools";

    /** Degradation key: the memory write path runs without a redaction policy. */
    public static final String CAPABILITY_REDACTION = "memory-redaction";

    private static final MemoryAssembly DISABLED = new MemoryAssembly(null, null, null, null, null);

    private final PeerMemory peerMemory;
    private final MemoryContextProvider contextProvider;
    private final ExecutionMemorySink executionMemorySink;
    private final ToolContextEnricher contextEnricher;
    private final OrcaToolProvider toolProvider;

    private MemoryAssembly(PeerMemory peerMemory, MemoryContextProvider contextProvider,
            ExecutionMemorySink executionMemorySink, ToolContextEnricher contextEnricher,
            OrcaToolProvider toolProvider) {
        this.peerMemory = peerMemory;
        this.contextProvider = contextProvider;
        this.executionMemorySink = executionMemorySink;
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

        final PeerMemory backend = wrap(resolveBackend(spec), spec.getRedactionPolicy().orElse(null));
        final Set<MemoryCapability> capabilities = MemoryCapabilities.of(backend);

        // A fixed peer answers every execution; per-caller reads the principal off the execution and returns nothing
        // for the ones that arrive without one. The spec makes this an either/or so no deployment can end up with
        // half of each — see MemorySpec and MemoryPeerResolver.
        final Optional<Principal> fixedPeer = spec.getFixedPeer();
        final MemoryPeerResolver peerResolver = fixedPeer.map(MemoryPeerResolver::fixed)
                .orElseGet(MemoryPeerResolver::caller);

        // SNAPSHOT has two consumers: the injected prompt part here, and MemoryRecall inside the tool provider.
        final MemoryContextProvider contextProvider = backend.snapshotReader()
                .map(reader -> (MemoryContextProvider) new SnapshotMemoryContextProvider(reader, spec.getWorkspace(),
                        peerResolver, spec.getInjectionMode(), spec.getMaxTokens()))
                .orElse(null);

        // The write seam, and only in EXECUTION_END mode. SESSION_END is not assembled here at all: it is the front
        // end feeding the whole transcript once at close, which needs no delta and no executor seam.
        final ExecutionMemorySink executionMemorySink = spec.getIngestMode() == MemoryIngestMode.EXECUTION_END
                ? backend.ingestor()
                        .map(ingestor -> (ExecutionMemorySink) new IngestingExecutionMemorySink(ingestor,
                                spec.getWorkspace(), peerResolver))
                        .orElse(null)
                : null;

        // One observer for the whole runtime, so it exists only in fixed-peer mode. The enrichment info a
        // ToolContextEnricher receives carries a session and an execution but no principal, so there is nothing to
        // resolve a per-call observer from — this is the seam that would have to widen before the memory tools can
        // serve a multi-caller deployment.
        final ToolContextEnricher contextEnricher = fixedPeer
                .map(peer -> (ToolContextEnricher) new MemoryToolContextEnricher(spec.getWorkspace(),
                        PeerView.of(spec.getWorkspace(), peer)))
                .orElse(null);

        final OrcaToolProvider toolProvider = contextEnricher == null || !servesAnyTool(capabilities)
                ? null
                : new OrcaMemoryToolProvider(backend, spec.getRedactionPolicy().orElse(null));

        recordDegradations(spec, backend, capabilities, toolProvider, degradations);
        return new MemoryAssembly(backend, contextProvider, executionMemorySink, contextEnricher, toolProvider);
    }

    /** A supplied backend is used as given; stores are folded into the default one. */
    private static PeerMemory resolveBackend(MemorySpec spec) {
        return spec.getPeerMemory()
                .orElseGet(() -> StoreBackedPeerMemory.builder()
                        .representationStore(spec.getRepresentationStore().orElse(null))
                        .observationStore(spec.getObservationStore().orElse(null)).build());
    }

    private static PeerMemory wrap(PeerMemory backend, RedactionPolicy redactionPolicy) {
        return redactionPolicy == null ? backend : new RedactingPeerMemory(backend, redactionPolicy);
    }

    private static boolean servesAnyTool(Set<MemoryCapability> capabilities) {
        return capabilities.contains(MemoryCapability.SNAPSHOT) || capabilities.contains(MemoryCapability.SEARCH)
                || capabilities.contains(MemoryCapability.OBSERVE) || capabilities.contains(MemoryCapability.CHAT);
    }

    private static void recordDegradations(MemorySpec spec, PeerMemory backend, Set<MemoryCapability> capabilities,
            OrcaToolProvider toolProvider, RuntimeDegradations.Collector degradations) {
        final Map<MemoryCapability, String> keys = degradationKeys();

        for (MemoryCapability missing : MemoryCapabilities.missingFrom(backend)) {
            degradations.add(keys.get(missing), consequence(missing, backend.backendId()));
        }
        if (capabilities.contains(MemoryCapability.INGEST) && spec.getIngestMode() == MemoryIngestMode.OFF) {
            degradations.add(keys.get(MemoryCapability.INGEST),
                    "The '" + backend.backendId() + "' memory backend can ingest conversation, but ingest is off, so"
                            + " nothing is fed in. Memory fills only through explicit Observe calls or another"
                            + " process against the same backend.");
        } else if (capabilities.contains(MemoryCapability.INGEST) && spec.isPerCaller()) {
            // The backend can ingest and the assembly still cannot: the execution-end seam has no principal to resolve
            // an observer from, exactly as the tool context enricher has none. Saying so here is what keeps "memory is
            // configured and nothing ever accumulates" diagnosable.
            degradations.add(keys.get(MemoryCapability.INGEST),
                    "The '" + backend.backendId() + "' memory backend can ingest conversation, but per-caller mode has"
                            + " no fixed observer to attribute it to, so nothing is fed in. Memory fills only through"
                            + " explicit Observe calls or another process against the same backend.");
        }
        if (toolProvider == null && capabilities.contains(MemoryCapability.SNAPSHOT)) {
            degradations.add(CAPABILITY_TOOLS,
                    "Per-caller memory injects a memory part into the prompt but registers no memory tools: they read"
                            + " their observer from the tool context, and the enricher that supplies it binds one"
                            + " fixed peer. The model can be told what memory says and cannot query or add to it.");
        }
        // Its own condition, not an else-branch of the one above: whether the tools were registered has nothing to do
        // with whether text can reach the backend unmasked. Chained to it, a backend serving INGEST and no tool
        // capability fell through both — the tools branch wants SNAPSHOT and this one wanted a tool provider — and a
        // whole conversation could flow out verbatim with nothing said about it. §5.2's condition is the capability
        // alone, and the sentence below is written to the same width: it reports that nothing masks these tiers, not
        // that any particular caller is using them. Naming the tools would be wrong wherever they are not registered
        // and the tiers are still reachable through PeerMemory's public accessors, which is the case §6.2 wraps
        // SEARCH and CHAT for.
        if (spec.getRedactionPolicy().isEmpty() && (capabilities.contains(MemoryCapability.OBSERVE)
                || capabilities.contains(MemoryCapability.INGEST))) {
            degradations.add(CAPABILITY_REDACTION,
                    "Nothing masks what is written to the '" + backend.backendId() + "' memory backend: it serves a"
                            + " write tier and no redaction policy is configured, so any text reaching it — through"
                            + " the memory tools where they are registered, or through the tier accessors directly —"
                            + " is persisted as it arrived, including anything secret that reached the conversation.");
        }
    }

    private static Map<MemoryCapability, String> degradationKeys() {
        Map<MemoryCapability, String> keys = new EnumMap<>(MemoryCapability.class);
        keys.put(MemoryCapability.SNAPSHOT, CAPABILITY_SNAPSHOT);
        keys.put(MemoryCapability.SEARCH, CAPABILITY_SEARCH);
        keys.put(MemoryCapability.CHAT, CAPABILITY_CHAT);
        keys.put(MemoryCapability.OBSERVE, CAPABILITY_OBSERVE);
        keys.put(MemoryCapability.INGEST, CAPABILITY_INGEST);
        return keys;
    }

    private static String consequence(MemoryCapability missing, String backendId) {
        final String prefix = "The '" + backendId + "' memory backend does not serve ";
        return switch (missing) {
            case SNAPSHOT -> prefix + "SNAPSHOT: no memory part is injected into the system prompt and MemoryRecall is"
                    + " not registered. The model is never told what is already known about the peer.";
            case SEARCH -> prefix + "SEARCH: MemorySearch is not registered, so stored observations can only be"
                    + " reached through the snapshot.";
            case CHAT -> prefix + "CHAT: MemoryChat is not registered, so questions about a peer cannot be answered"
                    + " from memory directly.";
            case OBSERVE -> prefix + "OBSERVE: the Observe tool is not registered, so the model has no way to record a"
                    + " fact it has just learned.";
            case INGEST -> prefix + "INGEST: nothing takes conversation in, so observations must be written by"
                    + " something else against the same backend, or memory stays empty for the life of the process.";
        };
    }

    /**
     * Returns the backend the stack reads and writes memory through — already wrapped for redaction when a policy was
     * configured.
     *
     * @return the backend, or empty when the stack has no memory
     */
    public Optional<PeerMemory> getPeerMemory() {
        return Optional.ofNullable(peerMemory);
    }

    /**
     * Returns the object that actually owns any native resources behind the backend — the delegate, when the backend
     * was wrapped for redaction.
     *
     * <p>
     * Teardown decisions belong here rather than on {@link #getPeerMemory()}: the redaction wrapper holds nothing, so
     * an {@code instanceof AutoCloseable} check against it answers {@code false} for an adapter that does.
     *
     * @return the unwrapped backend, or empty when the stack has no memory
     */
    public Optional<PeerMemory> getPeerMemoryDelegate() {
        return getPeerMemory()
                .map(memory -> memory instanceof RedactingPeerMemory redacting ? redacting.getDelegate() : memory);
    }

    /**
     * Returns the tier conversation is fed into at the end of an execution.
     *
     * @return the ingestor, or empty when the backend cannot ingest
     */
    public Optional<MemoryIngestor> getIngestor() {
        return getPeerMemory().flatMap(PeerMemory::ingestor);
    }

    /**
     * Returns the seam each execution's new messages are offered to.
     *
     * @return the sink, or empty unless the backend can ingest and the spec asked for
     *         {@link MemoryIngestMode#EXECUTION_END}
     */
    public Optional<ExecutionMemorySink> getExecutionMemorySink() {
        return Optional.ofNullable(executionMemorySink);
    }

    /**
     * Returns the provider that contributes a memory part to each execution's system prompt.
     *
     * @return the provider, or empty when the backend serves no snapshot
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
     * @return the provider, or empty when the tools cannot be given an observer, or the backend serves none of them
     */
    public Optional<OrcaToolProvider> getToolProvider() {
        return Optional.ofNullable(toolProvider);
    }

    @Override
    public String toString() {
        return "MemoryAssembly[backend=" + (peerMemory == null ? "none" : peerMemory.backendId()) + ", capabilities="
                + (peerMemory == null ? "[]" : MemoryCapabilities.of(peerMemory)) + ", injection="
                + (contextProvider != null) + ", enricher=" + (contextEnricher != null) + ", tools="
                + (toolProvider != null) + "]";
    }
}
