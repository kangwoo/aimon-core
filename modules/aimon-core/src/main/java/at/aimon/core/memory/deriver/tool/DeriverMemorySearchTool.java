package at.aimon.core.memory.deriver.tool;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.memory.redaction.RedactionResult;

/**
 * Internal deriver tool: surface existing observations that overlap with a
 * candidate before persistence so the ReAct loop can avoid duplicates and
 * spot conflicts. Honcho analogue of {@code search_memory} (design doc §6.1.4).
 *
 * <p>
 * The tool is deliberately a thin wrapper around
 * {@link ObservationStore#semanticSearch}; reconciliation logic lives in the
 * deriver, not here. The render format mirrors {@code MemorySearchTool}'s
 * verbose listing so the deriver model can reason about ids/confidences.
 *
 * <p>
 * Inputs:
 * <ul>
 * <li>{@code query} (string, required) — usually the candidate observation's content.</li>
 * <li>{@code top_k} (number, optional) — defaults to {@value #DEFAULT_TOP_K}, clamped to {@value #MAX_TOP_K}.</li>
 * </ul>
 */
public class DeriverMemorySearchTool extends AbstractTool {

    public static final String TOOL_NAME = "deriver.memory.search";

    /** Workspace the search runs in. */
    public static final ToolContextKey<Workspace> WORKSPACE_KEY = ToolContextKey.of("memory.workspace",
            Workspace.class);

    /** Peer running the search (typically the deriver agent). */
    public static final ToolContextKey<PeerView> OBSERVER_KEY = ToolContextKey.of("memory.observer", PeerView.class);

    /** Peer the search is about; defaults to {@link #OBSERVER_KEY} when missing. */
    public static final ToolContextKey<PeerView> SUBJECT_KEY = ToolContextKey.of("memory.subject", PeerView.class);

    static final int DEFAULT_TOP_K = 10;
    static final int MAX_TOP_K = 50;

    private static final Logger log = LoggerFactory.getLogger(DeriverMemorySearchTool.class);

    private final ObservationStore observationStore;
    private final RedactionPolicy redactionPolicy;

    public DeriverMemorySearchTool(ObservationStore observationStore) {
        this(observationStore, null);
    }

    /**
     * @param observationStore
     *            backing store (must not be null)
     * @param redactionPolicy
     *            optional policy applied to the query; {@code null} disables redaction
     */
    public DeriverMemorySearchTool(ObservationStore observationStore, RedactionPolicy redactionPolicy) {
        super(TOOL_NAME,
                "Search the subject peer's existing observations during the deriver ReAct loop. "
                        + "Use to spot duplicates or conflicts before persisting a new candidate.",
                createInputSchema());
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore cannot be null");
        this.redactionPolicy = redactionPolicy;
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("query",
                        Map.of("type", "string", "description", "Natural-language search phrase or candidate content."),
                        "top_k", Map
                                .of("type", "number", "description",
                                        "Maximum number of matching observations to return. Defaults to "
                                                + DEFAULT_TOP_K + ", capped at " + MAX_TOP_K + ".")),
                "required", List.of("query"));
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        // Searches existing memories so the deriver can avoid duplicates; it writes none itself.
        return SideEffectLevel.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(context, "context cannot be null");

        try {
            String query = input.getRequiredString("query");
            if (query.isBlank()) {
                return ToolResult.error("query cannot be blank");
            }
            int topK = input.getInteger("top_k", DEFAULT_TOP_K);
            if (topK < 1) {
                return ToolResult.error("top_k must be >= 1, got " + topK);
            }
            if (topK > MAX_TOP_K) {
                topK = MAX_TOP_K;
            }

            Workspace workspace = context.get(WORKSPACE_KEY).orElse(null);
            if (workspace == null) {
                return ToolResult.error(TOOL_NAME + " requires '" + WORKSPACE_KEY.name() + "' in ToolContext");
            }
            PeerView observer = context.get(OBSERVER_KEY).orElse(null);
            if (observer == null) {
                return ToolResult.error(TOOL_NAME + " requires '" + OBSERVER_KEY.name() + "' in ToolContext");
            }
            PeerView subject = context.get(SUBJECT_KEY).orElse(observer);
            if (!subject.getWorkspace().equals(workspace)) {
                return ToolResult.error("subject workspace (" + subject.getWorkspace().getId()
                        + ") does not match deriver workspace (" + workspace.getId() + ")");
            }

            String effectiveQuery = applyRedaction(query);
            List<Observation> hits = observationStore.semanticSearch(subject, effectiveQuery, topK);
            log.debug("DeriverMemorySearch subject={} query='{}' hits={}", subject.key(), effectiveQuery, hits.size());
            return ToolResult.success(render(subject, effectiveQuery, hits));

        } catch (IllegalArgumentException e) {
            log.warn("DeriverMemorySearch invalid input: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("DeriverMemorySearch unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private String applyRedaction(String query) {
        if (redactionPolicy == null) {
            return query;
        }
        RedactionResult result = redactionPolicy.redact(query);
        if (result.isModified()) {
            log.debug("DeriverMemorySearch query redacted: categories={}", result.getCategories());
        }
        return result.getRedactedContent();
    }

    private static String render(PeerView subject, String query, List<Observation> hits) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("deriver.memory.search results for ").append(subject.key()).append('\n');
        sb.append("query: ").append(query).append('\n');
        sb.append("hits: ").append(hits.size()).append('\n');

        if (hits.isEmpty()) {
            sb.append('\n').append("(no matching observations)").append('\n');
            return sb.toString();
        }

        sb.append('\n');
        for (Observation obs : hits) {
            sb.append("- [").append(obs.getId().getLocalId()).append("] ").append(obs.getContent()).append(" (type=")
                    .append(obs.getType()).append(", confidence=")
                    .append(String.format(Locale.ROOT, "%.2f", obs.getConfidence())).append(")\n");
        }
        return sb.toString();
    }
}
