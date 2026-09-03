package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.memory.MemoryCapabilities;
import at.aimon.core.memory.MemoryCapability;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerMemory;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.StoreBackedPeerMemory;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.tools.memory.MemoryChatTool;
import at.aimon.core.tools.memory.MemoryRecallTool;
import at.aimon.core.tools.memory.MemorySearchTool;
import at.aimon.core.tools.memory.ObserveTool;

/**
 * Provides the peer-memory tools to the Orca agent system.
 *
 * <p>
 * Which tools appear is decided by what the backend can actually do, one capability at a time:
 *
 * <ul>
 * <li>{@link MemoryCapability#SNAPSHOT} registers {@link MemoryRecallTool} — recall of the derived snapshot;
 * <li>{@link MemoryCapability#SEARCH} registers {@link MemorySearchTool} and {@link MemoryCapability#OBSERVE}
 * registers {@link ObserveTool} — the raw read and write of individual observations;
 * <li>{@link MemoryCapability#CHAT} registers {@link MemoryChatTool}.
 * </ul>
 *
 * <p>
 * <b>A tool the backend cannot serve is not registered at all.</b> Registering it and answering
 * "not supported" would put it in front of the model on every execution, and the model would keep calling it —
 * spending iterations and prompt budget on a failure that was decidable at assembly. That decision is made from
 * {@link MemoryCapabilities#of(PeerMemory)}, which is computed from the tier accessors rather than declared, so it
 * cannot disagree with what the backend implements.
 *
 * <p>
 * {@link MemoryChatTool} appears here for the first time. It used to be registered only by the CLI's hand-written
 * wiring, so a deployment assembled through the stack could not use it however its backend was configured; a
 * capability-driven loop registers it wherever the CHAT tier exists.
 *
 * <p>
 * <b>What is <i>not</i> here.</b> The workspace and the observer peer the memory tools read out of the
 * {@code ToolContext} come from {@link at.aimon.core.tools.memory.MemoryToolContextEnricher}, which is registered
 * separately. Registering this provider without that enricher gives the model tools that answer "no workspace in
 * context" to every call, so callers that cannot supply an observer should not register this provider at all.
 *
 * @see OrcaToolProvider
 * @see at.aimon.core.tools.memory.MemoryToolContextEnricher
 */
public class OrcaMemoryToolProvider implements OrcaToolProvider {

    private final PeerMemory backend;
    private final RedactionPolicy redactionPolicy;

    /**
     * Creates a provider over the backend a deployment actually has.
     *
     * @param backend
     *            the memory backend whose capabilities decide the tool set (must not be null)
     * @param redactionPolicy
     *            applied at the tool boundary — to the query before searching and to the content before persisting —
     *            or {@code null} to leave that to whatever wraps the backend
     * @throws NullPointerException
     *             if {@code backend} is null
     * @throws IllegalArgumentException
     *             if the backend serves none of the four tool capabilities, which would register nothing at all
     */
    public OrcaMemoryToolProvider(PeerMemory backend, RedactionPolicy redactionPolicy) {
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.redactionPolicy = redactionPolicy;
        if (MemoryCapabilities.of(backend).stream().noneMatch(OrcaMemoryToolProvider::hasTool)) {
            throw new IllegalArgumentException("The memory backend '" + backend.backendId() + "' serves none of"
                    + " SNAPSHOT, SEARCH, CHAT or OBSERVE — a memory tool provider over it registers no tools and"
                    + " looks configured");
        }
    }

    /**
     * Creates a provider over the stores a deployment actually has, folding them into the default backend.
     *
     * @param representationStore
     *            the store the recall tool reads, or {@code null} to omit that tool
     * @param observationStore
     *            the store the search and observe tools use, or {@code null} to omit those tools
     * @param redactionPolicy
     *            applied at the tool boundary, or {@code null} to pass through unredacted
     * @throws IllegalArgumentException
     *             if both stores are {@code null}, which would register nothing at all
     */
    public OrcaMemoryToolProvider(RepresentationStore representationStore, ObservationStore observationStore,
            RedactionPolicy redactionPolicy) {
        this(defaultBackend(representationStore, observationStore), redactionPolicy);
    }

    private static PeerMemory defaultBackend(RepresentationStore representationStore,
            ObservationStore observationStore) {
        if (representationStore == null && observationStore == null) {
            throw new IllegalArgumentException(
                    "At least one of representationStore or observationStore is required — a memory tool provider"
                            + " with neither registers no tools and looks configured");
        }
        return StoreBackedPeerMemory.builder().representationStore(representationStore)
                .observationStore(observationStore).build();
    }

    private static boolean hasTool(MemoryCapability capability) {
        return capability != MemoryCapability.INGEST;
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        backend.snapshotReader().ifPresent(reader -> registry.register(new MemoryRecallTool(reader)));
        backend.searcher().ifPresent(searcher -> registry.register(new MemorySearchTool(searcher, redactionPolicy)));
        backend.observationRecorder()
                .ifPresent(recorder -> registry.register(new ObserveTool(recorder, redactionPolicy)));
        backend.dialecticEngine().ifPresent(engine -> registry.register(new MemoryChatTool(engine)));
    }
}
