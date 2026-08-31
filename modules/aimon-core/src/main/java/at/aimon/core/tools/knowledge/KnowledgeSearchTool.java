package at.aimon.core.tools.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tool that allows an LLM agent to search the agent's knowledge base.
 *
 * <p>
 * Returns document chunks ranked by relevance from the configured {@link KnowledgeStore}. The knowledge store is
 * obtained from the {@link ToolContext} using {@link ToolContextKeys#KNOWLEDGE_STORE}.
 *
 * <p>
 * Usage by LLM:
 *
 * <pre>
 * KnowledgeSearch(query: "CrashLoopBackOff troubleshooting", max_results: 5)
 * </pre>
 *
 * @see KnowledgeStore
 */
public class KnowledgeSearchTool extends AbstractTool {

    public static final String TOOL_NAME = "KnowledgeSearch";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    /**
     * Creates a KnowledgeSearchTool.
     */
    public KnowledgeSearchTool() {
        super(TOOL_NAME,
                "Search agent's knowledge base for relevant documents. "
                        + "Returns document chunks ranked by relevance. "
                        + "Use this tool to find information from the agent's configured knowledge directory.",
                ToolCategories.SEARCH, createInputSchema());
    }

    private static Map<String, Object> createInputSchema() {
        return Map
                .of("type", "object", "additionalProperties", false, "properties",
                        Map.of("query", Map.of("type", "string", "description", "The search query text"), "max_results",
                                Map.of("type", "number", "description",
                                        "Maximum number of results to return (default: 5)"),
                                "file_pattern",
                                Map.of("type", "string", "description", "File filter glob pattern (e.g., '*.md')")),
                        "required", List.of("query"));
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        // Queries the KnowledgeStore; indexing and deletion are separate tools.
        return SideEffectLevel.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            final KnowledgeStore knowledgeStore = context.get(ToolContextKeys.KNOWLEDGE_STORE).orElse(null);
            if (knowledgeStore == null) {
                return ToolResult.error("No knowledge store configured for this agent");
            }

            final KnowledgeScope knowledgeScope = context.get(ToolContextKeys.KNOWLEDGE_SCOPE).orElse(null);
            if (knowledgeScope == null) {
                return ToolResult.error("No knowledge scope configured for this agent");
            }

            final String queryText = input.getRequiredString("query");
            final int maxResults = input.getInteger("max_results", SearchQuery.DEFAULT_MAX_RESULTS);
            final String filePattern = input.getStringOrNull("file_pattern");

            log.debug("Searching knowledge: query='{}', maxResults={}, scope={}", queryText, maxResults,
                    knowledgeScope);

            final SearchQuery.Builder queryBuilder = SearchQuery.builder().queryText(queryText).maxResults(maxResults);

            if (filePattern != null && !filePattern.isEmpty()) {
                queryBuilder.filePatterns(List.of(filePattern));
            }

            final List<SearchResult> results = knowledgeStore.search(knowledgeScope, queryBuilder.build());

            if (results.isEmpty()) {
                return ToolResult.success("No relevant documents found for: " + queryText);
            }

            final String formatted = formatResults(queryText, results);
            log.debug("Found {} results for query: '{}'", results.size(), queryText);
            return ToolResult.success(formatted);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to search knowledge: {}", e.getMessage(), e);
            return ToolResult.error("Failed to search knowledge: " + e.getMessage());
        }
    }

    private static String formatResults(String queryText, List<SearchResult> results) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" results for \"").append(queryText).append("\":\n");

        for (int i = 0; i < results.size(); i++) {
            final SearchResult result = results.get(i);
            sb.append("\n---\n");
            sb.append('[').append(i + 1).append("] ");
            sb.append(result.getDocumentPath());
            sb.append(" (score: ").append(String.format("%.2f", result.getScore()));
            sb.append(", chunk: ").append(result.getChunkIndex()).append(")\n");
            sb.append(result.getChunkContent());
            sb.append('\n');
        }

        return sb.toString();
    }
}
