package at.aimon.core.tools.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.tools.web.cache.WebToolCacheRepository;
import at.aimon.core.tools.web.model.WebSearchResponse;
import at.aimon.core.tools.web.model.WebSearchResult;
import at.aimon.core.tools.web.provider.WebSearchProvider;
import at.aimon.core.tools.web.util.WebToolUtils;

/**
 * Web search tool for LLM agents.
 *
 * <p>
 * Thin orchestrator that delegates search execution to a {@link WebSearchProvider}
 * and caches results via {@link WebToolCacheRepository}.
 *
 * <p>
 * Flow: parameter extraction → cache check → provider call → format → cache store.
 */
public class WebSearchTool extends AbstractTool {

    public static final String TOOL_NAME = "WebSearch";

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final int DEFAULT_COUNT = 5;

    private final WebSearchProvider provider;
    private final WebToolCacheRepository cache;
    private final Duration cacheTtl;

    /**
     * Creates a new WebSearchTool.
     *
     * @param provider
     *            the search provider (holds search-specific settings)
     * @param cache
     *            the cache repository
     * @param cacheTtl
     *            the cache TTL
     */
    public WebSearchTool(WebSearchProvider provider, WebToolCacheRepository cache, Duration cacheTtl) {
        super(TOOL_NAME,
                "Search the web using a search engine. "
                        + "Returns a list of search results with title, URL, and snippet. "
                        + "Use this when you need up-to-date information from the internet.",
                ToolCategories.SEARCH, createInputSchema());
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("query",
                Map.of("type", "string", "description", "The search query string"), "count",
                Map.of("type", "number", "description", "Number of results to return (1-10, default: 5)"), "country",
                Map.of("type", "string", "description", "Country code for search results (e.g. \"US\", \"KR\")"),
                "search_lang", Map.of("type", "string", "description", "Search language code (e.g. \"en\", \"ko\")"),
                "freshness",
                Map.of("type", "string", "description",
                        "Filter by freshness: \"pd\" (past day), \"pw\" (past week), "
                                + "\"pm\" (past month), \"py\" (past year), " + "or \"YYYY-MM-DDtoYYYY-MM-DD\"")),
                "required", List.of("query"));
    }

    /**
     * Declares {@link SideEffectLevel#READ_ONLY}. The search itself is a query, and the only write is the result
     * cache — a transparent read-through cache, exempt for the reason given on {@link SideEffectLevel#READ_ONLY}.
     */
    @Override
    public SideEffectLevel getSideEffectLevel() {
        return SideEffectLevel.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // 1. Parameter extraction and validation
            final String query = input.getRequiredString("query");
            if (query.isBlank()) {
                return ToolResult.error("query cannot be empty");
            }

            final int count = WebToolUtils.clamp(input.getInteger("count", DEFAULT_COUNT), 1, 10);
            final String country = input.getStringOrNull("country");
            final String searchLang = input.getStringOrNull("search_lang");
            final String freshness = input.getStringOrNull("freshness");

            // 2. Cache lookup
            final String cacheKey = WebToolUtils.buildCacheKey(query, String.valueOf(count), country, searchLang,
                    freshness);
            final Optional<String> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                log.debug("Cache hit for query: {}", query);
                return ToolResult.success(cached.get());
            }

            // 3. Delegate to provider
            final WebSearchResponse response = provider.search(query, count, country, searchLang, freshness);

            // 4. Format results
            final String formatted = formatResults(response);

            // 5. Store in cache
            cache.put(cacheKey, formatted, cacheTtl);

            log.debug("Search completed: query={}, results={}", query, response.getResults().size());
            return ToolResult.success(formatted);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            log.error("Out of memory during search: {}", e.getMessage(), e);
            return ToolResult.error("Out of memory: search response too large to process");
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage(), e);
            return ToolResult.error("Search failed: " + e.getMessage());
        }
    }

    private String formatResults(WebSearchResponse response) {
        if (response.getResults().isEmpty()) {
            return "No results found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Provider: ").append(response.getProvider()).append('\n').append('\n');
        List<WebSearchResult> results = response.getResults();
        for (int i = 0; i < results.size(); i++) {
            WebSearchResult r = results.get(i);
            sb.append('[').append(i + 1).append("] ").append(r.getTitle()).append('\n');
            if (r.getUrl() != null && !r.getUrl().isBlank()) {
                sb.append("    URL: ").append(r.getUrl()).append('\n');
            }
            if (r.getSnippet() != null && !r.getSnippet().isBlank()) {
                sb.append("    ").append(r.getSnippet()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
