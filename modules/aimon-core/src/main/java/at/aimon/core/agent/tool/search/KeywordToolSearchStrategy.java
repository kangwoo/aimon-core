package at.aimon.core.agent.tool.search;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keyword-based tool search strategy.
 *
 * <p>
 * Searches tool names, descriptions, and parameter names/descriptions using case-insensitive substring matching. Each
 * match target is weighted differently:
 *
 * <ul>
 * <li>Name: 0.5
 * <li>Description: 0.35
 * <li>Parameters: 0.15
 * </ul>
 *
 * <p>
 * This class is stateless and thread-safe.
 */
public final class KeywordToolSearchStrategy implements ToolSearchStrategy {

    private static final double NAME_WEIGHT = 0.5;
    private static final double DESCRIPTION_WEIGHT = 0.35;
    private static final double PARAMETER_WEIGHT = 0.15;

    @Override
    public List<SearchableTool> search(String query, List<SearchableTool> candidates, int maxResults) {
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(candidates, "Candidates cannot be null");

        final String normalizedQuery = normalize(query.trim());
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }

        final String[] keywords = normalizedQuery.split("\\s+");

        return candidates.stream().map(tool -> new ScoredTool(tool, computeScore(tool, keywords)))
                .filter(st -> st.score > 0).sorted(Comparator.comparingDouble(ScoredTool::score).reversed())
                .limit(maxResults).map(ScoredTool::tool).toList();
    }

    /**
     * Computes the relevance score for a tool against the given keywords.
     *
     * <p>
     * Package-private intentionally — used by {@link RequiredKeywordToolSearchStrategy} for ranking after filtering.
     *
     * @param tool
     *            the tool to score
     * @param keywords
     *            the search keywords (must be lowercase and normalized)
     * @return the aggregate score (0.0 if no match)
     */
    double computeScore(SearchableTool tool, String[] keywords) {
        final String name = normalize(tool.getName());
        final String description = normalize(tool.getTool().getDefinition().getDescription());
        final String parameterText = normalize(extractParameterText(tool));

        double nameScore = 0;
        double descriptionScore = 0;
        double parameterScore = 0;

        for (String keyword : keywords) {
            if (name.contains(keyword)) {
                nameScore += 1.0;
            }
            if (description.contains(keyword)) {
                descriptionScore += 1.0;
            }
            if (!parameterText.isEmpty() && parameterText.contains(keyword)) {
                parameterScore += 1.0;
            }
        }

        // Normalize by keyword count
        final double keywordCount = keywords.length;
        nameScore /= keywordCount;
        descriptionScore /= keywordCount;
        parameterScore /= keywordCount;

        return (NAME_WEIGHT * nameScore) + (DESCRIPTION_WEIGHT * descriptionScore)
                + (PARAMETER_WEIGHT * parameterScore);
    }

    /**
     * Extracts searchable text from a tool's input schema parameters.
     *
     * @param tool
     *            the tool
     * @return concatenated parameter names and descriptions
     */
    @SuppressWarnings("unchecked")
    private static String extractParameterText(SearchableTool tool) {
        final Map<String, Object> inputSchema = tool.getTool().getDefinition().getInputSchema();
        if (inputSchema == null) {
            return "";
        }

        final Object propertiesObj = inputSchema.get("properties");
        if (!(propertiesObj instanceof Map<?, ?>)) {
            return "";
        }

        final Map<String, Object> properties = (Map<String, Object>) propertiesObj;
        final StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            sb.append(entry.getKey()).append(' ');
            if (entry.getValue() instanceof Map<?, ?> propMap) {
                final Object desc = propMap.get("description");
                if (desc instanceof String descStr) {
                    sb.append(descStr).append(' ');
                }
            }
        }

        return sb.toString().trim();
    }

    /**
     * Normalizes text for matching: lowercase, underscores and hyphens treated as spaces.
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase().replace('_', ' ').replace('-', ' ');
    }

    private record ScoredTool(SearchableTool tool, double score) {
    }

}
