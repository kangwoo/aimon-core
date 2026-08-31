package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.tools.memory.MemoryRecallTool;
import at.aimon.core.tools.memory.MemorySearchTool;
import at.aimon.core.tools.memory.ObserveTool;

/**
 * Provides the peer-memory tools to the Orca agent system.
 *
 * <p>
 * Which tools appear is decided by which stores were supplied, one store at a time:
 *
 * <ul>
 * <li>a {@link RepresentationStore} registers {@link MemoryRecallTool} — recall of the derived snapshot;
 * <li>an {@link ObservationStore} registers {@link MemorySearchTool} and {@link ObserveTool} — the raw read and write
 * of individual observations.
 * </ul>
 *
 * <p>
 * <b>Why the stores are held here and the knowledge store is not.</b> {@link OrcaKnowledgeToolProvider} registers a
 * tool that finds its store in the {@code ToolContext} at call time, so the provider itself carries nothing. The memory
 * tools take their store at construction, so this provider has to hold them. The consequence is that one provider
 * instance is bound to one pair of stores — which is correct here, because both stores are application-scoped and
 * shared by every runtime the stack builds.
 *
 * <p>
 * <b>What is <i>not</i> here.</b> The workspace and the observer peer the memory tools read out of the
 * {@code ToolContext} come from {@link at.aimon.core.tools.memory.MemoryToolContextEnricher}, which is registered
 * separately. Registering this provider without that enricher gives the model three tools that answer "no workspace in
 * context" to every call, so callers that cannot supply an observer should not register this provider at all.
 *
 * @see OrcaToolProvider
 * @see at.aimon.core.tools.memory.MemoryToolContextEnricher
 */
public class OrcaMemoryToolProvider implements OrcaToolProvider {

    private final RepresentationStore representationStore;
    private final ObservationStore observationStore;
    private final RedactionPolicy redactionPolicy;

    /**
     * Creates a provider over the stores a deployment actually has.
     *
     * @param representationStore
     *            the store the recall tool reads, or {@code null} to omit that tool
     * @param observationStore
     *            the store the search and observe tools use, or {@code null} to omit those tools
     * @param redactionPolicy
     *            applied at the tool boundary — to the query before searching and to the content before persisting —
     *            or {@code null} to pass both through unredacted
     * @throws IllegalArgumentException
     *             if both stores are {@code null}, which would register nothing at all
     */
    public OrcaMemoryToolProvider(RepresentationStore representationStore, ObservationStore observationStore,
            RedactionPolicy redactionPolicy) {
        if (representationStore == null && observationStore == null) {
            throw new IllegalArgumentException(
                    "At least one of representationStore or observationStore is required — a memory tool provider"
                            + " with neither registers no tools and looks configured");
        }
        this.representationStore = representationStore;
        this.observationStore = observationStore;
        this.redactionPolicy = redactionPolicy;
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        if (representationStore != null) {
            registry.register(new MemoryRecallTool(representationStore));
        }
        if (observationStore != null) {
            registry.register(new MemorySearchTool(observationStore, redactionPolicy));
            registry.register(new ObserveTool(observationStore, redactionPolicy));
        }
    }
}
