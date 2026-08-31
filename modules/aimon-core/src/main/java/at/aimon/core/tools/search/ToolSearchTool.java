package at.aimon.core.tools.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.search.SearchableTool;
import at.aimon.core.agent.tool.search.ToolSearchRegistry;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.tools.ToolContextKeys;

/**
 * LLM-facing tool that searches and activates deferred tools.
 *
 * <p>
 * Supports three query modes:
 *
 * <ul>
 * <li><b>Keyword search</b> — free-text query matched against tool names, descriptions, and parameters
 * <li><b>Direct selection</b> — {@code "select:Read,Edit,Grep"} for exact name lookup
 * <li><b>Required keyword</b> — {@code "+slack send"} filters by name then ranks
 * </ul>
 *
 * <p>
 * Search results are automatically activated in the current session and returned as full function schemas so the
 * LLM can immediately use them.
 *
 * @see ToolSearchRegistry
 */
public class ToolSearchTool extends AbstractTool {

    public static final String TOOL_NAME = "ToolSearch";

    static final int MAX_RESULTS_LIMIT = 20;
    static final int DEFAULT_MAX_RESULTS = 5;

    private static final Logger log = LoggerFactory.getLogger(ToolSearchTool.class);

    public ToolSearchTool() {
        super(TOOL_NAME,
                "Search for or select deferred tools to make them available for use.\n\n"
                        + "**MANDATORY PREREQUISITE - THIS IS A HARD REQUIREMENT**\n\n"
                        + "You MUST use this tool to load deferred tools BEFORE calling them directly.\n\n"
                        + "**Query modes:**\n\n"
                        + "1. **Keyword search** - Use keywords when you're unsure which tool to use:\n"
                        + "   - \"list directory\" - find tools for listing directories\n"
                        + "   - Returns up to 5 matching tools ranked by relevance\n\n"
                        + "2. **Direct selection** - Use `select:<tool_name>` when you know the exact tool name:\n"
                        + "   - \"select:Read,Edit,Grep\" - load multiple tools at once\n\n"
                        + "3. **Required keyword** - Prefix with `+` to require a match:\n"
                        + "   - \"+slack send\" - only tools from \"slack\", ranked by \"send\"",
                ToolCategories.SEARCH, createInputSchema());
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "required", List.of("query"), "additionalProperties", false, "properties",
                Map.of("query",
                        Map.of("type", "string", "description",
                                "Query to find deferred tools. Use 'select:<tool_name>' for direct selection, "
                                        + "or keywords to search."),
                        "max_results", Map.of("type", "number", "default", DEFAULT_MAX_RESULTS, "description",
                                "Maximum number of results to return (default: " + DEFAULT_MAX_RESULTS + ")")));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // Retrieve the per-session registry from context
            final ToolSearchRegistry registry = context.get(ToolContextKeys.TOOL_SEARCH_REGISTRY).orElse(null);
            if (registry == null) {
                return ToolResult.error("ToolSearchRegistry not available in tool context");
            }

            // Extract and validate query
            final String query = input.getRequiredString("query");
            if (query.trim().isEmpty()) {
                return ToolResult.error("Query cannot be empty");
            }

            // Clamp max_results to [1, MAX_RESULTS_LIMIT]
            int maxResults = input.getInteger("max_results", DEFAULT_MAX_RESULTS);
            maxResults = Math.max(1, Math.min(maxResults, MAX_RESULTS_LIMIT));

            log.debug("Searching tools: query='{}', maxResults={}", query, maxResults);

            // Search and activate
            final List<SearchableTool> results = registry.searchAndActivate(query, maxResults);

            log.debug("Found {} tools for query '{}'", results.size(), query);

            // Format as function schemas
            return ToolResult.success(formatAsFunctionSchemas(results));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in ToolSearch: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Formats search results as a JSON-like string containing full function schemas.
     */
    private static String formatAsFunctionSchemas(List<SearchableTool> results) {
        final List<Map<String, Object>> functions = new ArrayList<>();
        for (SearchableTool st : results) {
            final ToolDefinition def = st.getTool().getDefinition();
            final Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", def.getName());
            fn.put("description", def.getDescription());
            fn.put("parameters", def.getInputSchema());
            functions.add(fn);
        }

        final Map<String, Object> response = Map.of("functions", functions);
        return toJsonString(response);
    }

    /**
     * Simple JSON serialization for the response. Avoids external JSON library dependency.
     */
    @SuppressWarnings("unchecked")
    private static String toJsonString(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        if (obj instanceof Map<?, ?> map) {
            final StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(escapeJson(entry.getKey().toString())).append("\":");
                sb.append(toJsonString(entry.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        if (obj instanceof List<?> list) {
            final StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(toJsonString(item));
                first = false;
            }
            return sb.append(']').toString();
        }
        return "\"" + escapeJson(obj.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

}
