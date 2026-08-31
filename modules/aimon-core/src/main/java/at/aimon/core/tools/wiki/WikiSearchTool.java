package at.aimon.core.tools.wiki;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBase;
import at.aimon.core.knowledge.wiki.WikiPage;
import at.aimon.core.knowledge.wiki.WikiScope;
import at.aimon.core.knowledge.wiki.WikiSearchQuery;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tool that allows an LLM agent to search the agent's wiki knowledge base.
 *
 * <p>
 * Returns wiki pages ranked by relevance from the configured {@link WikiKnowledgeBase}. The wiki knowledge base and
 * scope are obtained from the {@link ToolContext} using {@link ToolContextKeys#WIKI_KNOWLEDGE_BASE} and
 * {@link ToolContextKeys#WIKI_SCOPE}.
 *
 * <p>
 * Usage by LLM:
 *
 * <pre>
 * WikiSearch(query: "kubernetes pod troubleshooting", max_results: 5)
 * WikiSearch(query: "deployment runbook", tags: "ops,kubernetes")
 * </pre>
 *
 * @see WikiKnowledgeBase
 * @see WikiSearchQuery
 */
public class WikiSearchTool extends AbstractTool {

    public static final String TOOL_NAME = "WikiSearch";

    private static final Logger log = LoggerFactory.getLogger(WikiSearchTool.class);

    private static final int SNIPPET_LENGTH = 200;

    /**
     * Creates a WikiSearchTool.
     */
    public WikiSearchTool() {
        super(TOOL_NAME,
                "Search the agent's wiki knowledge base for relevant pages. "
                        + "Returns wiki pages ranked by relevance. "
                        + "Use this tool to find structured knowledge articles from the agent's wiki. "
                        + "Optionally filter by comma-separated tags.",
                ToolCategories.SEARCH, createInputSchema());
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("query",
                Map.of("type", "string", "description", "The search query text"), "max_results",
                Map.of("type", "number", "description",
                        "Maximum number of results to return (default: " + WikiSearchQuery.DEFAULT_MAX_RESULTS + ")"),
                "tags",
                Map.of("type", "string", "description",
                        "Comma-separated list of tags to filter results (e.g., 'ops,kubernetes')")),
                "required", List.of("query"));
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        // Searches wiki pages; writing and deleting pages are separate tools.
        return SideEffectLevel.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            final WikiKnowledgeBase wiki = context.get(ToolContextKeys.WIKI_KNOWLEDGE_BASE).orElse(null);
            if (wiki == null) {
                return ToolResult.error("No wiki knowledge base configured for this agent");
            }

            final WikiScope scope = context.get(ToolContextKeys.WIKI_SCOPE).orElse(null);
            if (scope == null) {
                return ToolResult.error("No wiki scope configured for this agent");
            }

            final String queryText = input.getRequiredString("query");
            final int maxResults = input.getInteger("max_results", WikiSearchQuery.DEFAULT_MAX_RESULTS);
            final String tagsRaw = input.getStringOrNull("tags");

            log.debug("Searching wiki: query='{}', maxResults={}, scope={}", queryText, maxResults, scope);

            final WikiSearchQuery.Builder queryBuilder = WikiSearchQuery.builder().queryText(queryText)
                    .maxResults(maxResults);

            if (tagsRaw != null && !tagsRaw.isBlank()) {
                final List<String> tags = Arrays.stream(tagsRaw.split(",")).map(String::trim).filter(t -> !t.isEmpty())
                        .collect(Collectors.toList());
                if (!tags.isEmpty()) {
                    queryBuilder.tags(tags);
                }
            }

            final List<WikiPage> results = wiki.search(scope, queryBuilder.build());

            if (results.isEmpty()) {
                return ToolResult.success("No wiki pages found for: " + queryText);
            }

            final String formatted = formatResults(queryText, results);
            log.debug("Found {} wiki pages for query: '{}'", results.size(), queryText);
            return ToolResult.success(formatted);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to search wiki: {}", e.getMessage(), e);
            return ToolResult.error("Failed to search wiki: " + e.getMessage());
        }
    }

    private static String formatResults(String queryText, List<WikiPage> results) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" wiki page(s) for \"").append(queryText).append("\":\n");

        for (int i = 0; i < results.size(); i++) {
            final WikiPage page = results.get(i);
            sb.append("\n---\n");
            sb.append('[').append(i + 1).append("] ");
            sb.append(page.getPath()).append('\n');
            sb.append("Title: ").append(page.getTitle()).append('\n');

            if (!page.getTags().isEmpty()) {
                sb.append("Tags: ").append(String.join(", ", page.getTags())).append('\n');
            }

            final String content = page.getContent();
            if (!content.isEmpty()) {
                final String snippet = content.length() > SNIPPET_LENGTH
                        ? content.substring(0, SNIPPET_LENGTH) + "..."
                        : content;
                sb.append(snippet).append('\n');
            }
        }

        return sb.toString();
    }
}
