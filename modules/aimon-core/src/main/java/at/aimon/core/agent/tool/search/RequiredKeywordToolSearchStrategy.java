package at.aimon.core.agent.tool.search;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Search strategy that filters candidates by a required keyword in the tool name, then ranks by additional keywords.
 *
 * <p>
 * The required keyword must appear as a substring of the tool name (case-insensitive). After filtering, remaining
 * candidates are ranked using the same weighted scoring algorithm as {@link KeywordToolSearchStrategy}.
 *
 * <p>
 * This class is stateless and thread-safe.
 */
public final class RequiredKeywordToolSearchStrategy implements ToolSearchStrategy {

    private final KeywordToolSearchStrategy keywordStrategy;

    /**
     * Creates a new strategy that delegates ranking to the given keyword strategy.
     *
     * @param keywordStrategy
     *            the keyword strategy used for scoring after filtering (must not be null)
     */
    public RequiredKeywordToolSearchStrategy(KeywordToolSearchStrategy keywordStrategy) {
        this.keywordStrategy = Objects.requireNonNull(keywordStrategy, "Keyword strategy cannot be null");
    }

    /**
     * Searches by filtering on required keyword, then ranking by query keywords.
     *
     * <p>
     * The {@code query} parameter is expected to already have the required keyword removed; it contains only the
     * ranking keywords. Use {@link #search(String, List, String, int)} for the full required-keyword workflow.
     */
    @Override
    public List<SearchableTool> search(String query, List<SearchableTool> candidates, int maxResults) {
        Objects.requireNonNull(query, "Query cannot be null");
        Objects.requireNonNull(candidates, "Candidates cannot be null");
        // When called via the generic interface, treat the entire query as required keyword with no ranking
        return search("", candidates, query, maxResults);
    }

    /**
     * Searches candidates by filtering on the required keyword in tool names, then ranking by ranking keywords.
     *
     * @param rankingQuery
     *            space-separated ranking keywords (may be empty)
     * @param candidates
     *            the candidate tools
     * @param requiredKeyword
     *            the keyword that must appear in tool names (may be empty, in which case all candidates are used)
     * @param maxResults
     *            maximum results to return
     * @return matching tools ordered by relevance
     */
    public List<SearchableTool> search(String rankingQuery, List<SearchableTool> candidates, String requiredKeyword,
            int maxResults) {
        Objects.requireNonNull(rankingQuery, "Ranking query cannot be null");
        Objects.requireNonNull(candidates, "Candidates cannot be null");
        Objects.requireNonNull(requiredKeyword, "Required keyword cannot be null");

        final String normalizedRequired = requiredKeyword.trim().toLowerCase().replace('_', ' ').replace('-', ' ');

        // Filter by required keyword in tool name
        List<SearchableTool> filtered;
        if (normalizedRequired.isEmpty()) {
            filtered = candidates;
        } else {
            filtered = candidates.stream().filter(tool -> {
                final String name = tool.getName().toLowerCase().replace('_', ' ').replace('-', ' ');
                return name.contains(normalizedRequired);
            }).toList();
        }

        if (filtered.isEmpty()) {
            return List.of();
        }

        // If no ranking keywords, return filtered results sorted by name
        final String trimmedRanking = rankingQuery.trim();
        if (trimmedRanking.isEmpty()) {
            return filtered.stream().sorted(Comparator.comparing(SearchableTool::getName)).limit(maxResults).toList();
        }

        // Rank by keyword relevance
        final String[] keywords = trimmedRanking.toLowerCase().replace('_', ' ').replace('-', ' ').split("\\s+");

        return filtered.stream().map(tool -> new ScoredTool(tool, keywordStrategy.computeScore(tool, keywords))).sorted(
                Comparator.comparingDouble(ScoredTool::score).reversed().thenComparing(st -> st.tool().getName()))
                .limit(maxResults).map(ScoredTool::tool).toList();
    }

    private record ScoredTool(SearchableTool tool, double score) {
    }

}
