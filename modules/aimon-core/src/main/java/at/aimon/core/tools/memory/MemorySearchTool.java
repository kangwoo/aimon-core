package at.aimon.core.tools.memory;

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
import at.aimon.core.memory.MemoryHit;
import at.aimon.core.memory.MemorySearchQuery;
import at.aimon.core.memory.MemorySearcher;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.StoreBackedPeerMemory;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.memory.redaction.RedactionResult;

/**
 * Searches a peer's stored {@link Observation observations} by keyword or
 * semantic similarity. AIMON analogue of Honcho {@code peer.search()}.
 *
 * <p>
 * The tool reads the {@link Workspace}, {@link PeerView observer}, and
 * {@link PeerView subject} from {@link ToolContext} via the typed keys exposed
 * on this class. The agent that wires the tool into a session is responsible
 * for populating those keys; the tool itself is stateless.
 *
 * <p>
 * Inputs:
 * <ul>
 * <li>{@code query} (string, required) — search query.</li>
 * <li>{@code top_k} (number, optional) — maximum hits to return; defaults to
 * {@value #DEFAULT_TOP_K}, clamped to {@value #MAX_TOP_K}.</li>
 * </ul>
 *
 * <p>
 * When a {@link RedactionPolicy} is configured, the query is redacted before
 * being passed to the searcher so secrets typed by a caller never reach the
 * embedding backend (design doc §6.5).
 *
 * <p>
 * The tool stands on the SEARCH tier rather than on a store. Two things follow.
 * Hits are rendered <b>in the order the tier returned them</b>, because that
 * order is the ranking — a backend that cannot score leaves every score at zero,
 * and printing those zeroes would read as "nothing is relevant". And a
 * confidence the backend did not store is not printed at all, for the same
 * reason a fabricated one would be worse than a missing one.
 */
public final class MemorySearchTool extends AbstractTool {

    public static final String TOOL_NAME = "MemorySearch";

    /** Workspace the search runs in. */
    public static final ToolContextKey<Workspace> WORKSPACE_KEY = MemoryToolContextKeys.WORKSPACE;

    /** Peer running the search (typically the agent itself). */
    public static final ToolContextKey<PeerView> OBSERVER_KEY = MemoryToolContextKeys.OBSERVER;

    /** Peer the search is about; defaults to {@link #OBSERVER_KEY} when missing. */
    public static final ToolContextKey<PeerView> SUBJECT_KEY = MemoryToolContextKeys.SUBJECT;

    static final int DEFAULT_TOP_K = 10;
    static final int MAX_TOP_K = 50;

    private static final Logger log = LoggerFactory.getLogger(MemorySearchTool.class);

    private final MemorySearcher searcher;
    private final RedactionPolicy redactionPolicy;

    public MemorySearchTool(ObservationStore observationStore) {
        this(observationStore, null);
    }

    /**
     * Creates a search tool on an {@link ObservationStore}, for callers assembling the default backend by hand.
     *
     * @param observationStore
     *            backing store (must not be null)
     * @param redactionPolicy
     *            optional policy applied to the query before searching; {@code null} to disable
     */
    public MemorySearchTool(ObservationStore observationStore, RedactionPolicy redactionPolicy) {
        this(searcherFor(observationStore), redactionPolicy);
    }

    /**
     * Creates a search tool on the SEARCH tier.
     *
     * @param searcher
     *            the tier searches run against (must not be null)
     */
    public MemorySearchTool(MemorySearcher searcher) {
        this(searcher, null);
    }

    /**
     * Creates a search tool on the SEARCH tier.
     *
     * @param searcher
     *            the tier searches run against (must not be null)
     * @param redactionPolicy
     *            optional policy applied to the query before searching; {@code null} to disable
     */
    public MemorySearchTool(MemorySearcher searcher, RedactionPolicy redactionPolicy) {
        super(TOOL_NAME,
                "Search a peer's stored observations by keyword or semantic similarity. "
                        + "Use this when you need raw observation snippets (with confidence scores) "
                        + "rather than a synthesized answer. Returns up to top_k matching observations.",
                createInputSchema());
        this.searcher = Objects.requireNonNull(searcher, "searcher cannot be null");
        this.redactionPolicy = redactionPolicy;
    }

    private static MemorySearcher searcherFor(ObservationStore observationStore) {
        Objects.requireNonNull(observationStore, "observationStore cannot be null");
        return StoreBackedPeerMemory.builder().observationStore(observationStore).build().searcher().orElseThrow();
    }

    private static Map<String, Object> createInputSchema() {
        return Map
                .of("type", "object", "additionalProperties", false, "properties",
                        Map.of("query", Map
                                .of("type", "string", "description", "The keyword or natural-language search phrase."),
                                "top_k",
                                Map.of("type", "number", "description",
                                        "Maximum number of matching observations to return. Defaults to "
                                                + DEFAULT_TOP_K + ", capped at " + MAX_TOP_K + ".")),
                        "required", List.of("query"));
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        // Searches the memory store; writing memories is a separate tool.
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
                return ToolResult.error("MemorySearch requires '" + WORKSPACE_KEY.name() + "' in ToolContext");
            }
            PeerView observer = context.get(OBSERVER_KEY).orElse(null);
            if (observer == null) {
                return ToolResult.error("MemorySearch requires '" + OBSERVER_KEY.name() + "' in ToolContext");
            }
            PeerView subject = context.get(SUBJECT_KEY).orElse(observer);
            if (!subject.getWorkspace().equals(workspace)) {
                return ToolResult.error("subject workspace (" + subject.getWorkspace().getId()
                        + ") does not match search workspace (" + workspace.getId() + ")");
            }

            String effectiveQuery = applyRedaction(query);
            List<MemoryHit> hits = searcher.search(MemorySearchQuery.builder().subject(subject).observer(observer)
                    .query(effectiveQuery).topK(topK).build());
            log.debug("MemorySearch subject={} query='{}' hits={}", subject.key(), effectiveQuery, hits.size());
            return ToolResult.success(render(subject, effectiveQuery, hits));

        } catch (IllegalArgumentException e) {
            log.warn("MemorySearch invalid input: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("MemorySearch unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private String applyRedaction(String query) {
        if (redactionPolicy == null) {
            return query;
        }
        RedactionResult result = redactionPolicy.redact(query);
        if (result.isModified()) {
            log.debug("MemorySearch query redacted: categories={}", result.getCategories());
        }
        return result.getRedactedContent();
    }

    private static String render(PeerView subject, String query, List<MemoryHit> hits) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("MemorySearch results for ").append(subject.key()).append('\n');
        sb.append("query: ").append(query).append('\n');
        sb.append("hits: ").append(hits.size()).append('\n');

        if (hits.isEmpty()) {
            sb.append('\n').append("(no matching observations)").append('\n');
            return sb.toString();
        }

        sb.append('\n');
        for (MemoryHit hit : hits) {
            Observation obs = hit.getObservation();
            sb.append("- [").append(obs.getId().getLocalId()).append("] ").append(obs.getContent()).append(" (type=")
                    .append(obs.getType());
            if (hit.isConfidenceAvailable()) {
                sb.append(", confidence=").append(String.format(Locale.ROOT, "%.2f", obs.getConfidence()));
            }
            sb.append(")\n");
        }
        return sb.toString();
    }
}
