package at.aimon.core.agent.tool.search;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Parses a tool search query string into a structured {@link ParsedQuery}.
 *
 * <p>
 * Supports three query modes:
 *
 * <ul>
 * <li><b>SELECT</b> — {@code "select:Read,Edit,Grep"} — direct tool selection by name
 * <li><b>REQUIRED</b> — {@code "+slack send message"} — required keyword in name + ranking keywords
 * <li><b>KEYWORD</b> — free-text keyword search (default)
 * </ul>
 *
 * <p>
 * This class is a pure utility with static methods only. It performs no validation beyond parsing; empty-query
 * validation is the caller's responsibility.
 */
public final class ToolSearchQueryParser {

    private static final String SELECT_PREFIX = "select:";
    private static final String REQUIRED_PREFIX = "+";

    private ToolSearchQueryParser() {
        throw new AssertionError("Utility class");
    }

    /**
     * Parses a query string into a {@link ParsedQuery}.
     *
     * @param query
     *            the raw query string (must not be null)
     * @return the parsed query (never null)
     * @throws NullPointerException
     *             if query is null
     */
    public static ParsedQuery parse(String query) {
        Objects.requireNonNull(query, "Query cannot be null");

        final String trimmed = query.trim();

        // 1. Direct selection: "select:Read,Edit,Grep"
        if (trimmed.startsWith(SELECT_PREFIX)) {
            final String namesPart = trimmed.substring(SELECT_PREFIX.length());
            final List<String> names = Arrays.stream(namesPart.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .toList();
            return ParsedQuery.select(names);
        }

        // 2. Required keyword: "+slack send message"
        if (trimmed.startsWith(REQUIRED_PREFIX)) {
            final String afterPlus = trimmed.substring(REQUIRED_PREFIX.length());
            final String[] tokens = afterPlus.split("\\s+");
            final List<String> nonEmpty = Arrays.stream(tokens).filter(s -> !s.isEmpty()).toList();

            if (nonEmpty.isEmpty()) {
                return ParsedQuery.keyword("");
            }

            final String requiredKeyword = nonEmpty.get(0);
            final List<String> rankingKeywords = nonEmpty.size() > 1 ? nonEmpty.subList(1, nonEmpty.size()) : List.of();
            return ParsedQuery.required(requiredKeyword, rankingKeywords);
        }

        // 3. Keyword search (default)
        return ParsedQuery.keyword(trimmed);
    }

    /**
     * Represents a parsed tool search query.
     */
    public static final class ParsedQuery {
        private final QueryMode mode;
        private final String keywords;
        private final List<String> toolNames;
        private final String requiredKeyword;
        private final List<String> rankingKeywords;

        private ParsedQuery(QueryMode mode, String keywords, List<String> toolNames, String requiredKeyword,
                List<String> rankingKeywords) {
            this.mode = mode;
            this.keywords = keywords;
            this.toolNames = toolNames;
            this.requiredKeyword = requiredKeyword;
            this.rankingKeywords = rankingKeywords;
        }

        static ParsedQuery keyword(String keywords) {
            return new ParsedQuery(QueryMode.KEYWORD, keywords, List.of(), "", List.of());
        }

        static ParsedQuery select(List<String> toolNames) {
            return new ParsedQuery(QueryMode.SELECT, "", toolNames, "", List.of());
        }

        static ParsedQuery required(String requiredKeyword, List<String> rankingKeywords) {
            return new ParsedQuery(QueryMode.REQUIRED, "", List.of(), requiredKeyword, rankingKeywords);
        }

        public QueryMode getMode() {
            return mode;
        }

        public String getKeywords() {
            return keywords;
        }

        public List<String> getToolNames() {
            return toolNames;
        }

        public String getRequiredKeyword() {
            return requiredKeyword;
        }

        public List<String> getRankingKeywords() {
            return rankingKeywords;
        }

        @Override
        public String toString() {
            return "ParsedQuery{mode=" + mode + ", keywords='" + keywords + "', toolNames=" + toolNames
                    + ", requiredKeyword='" + requiredKeyword + "', rankingKeywords=" + rankingKeywords + '}';
        }
    }

    /**
     * The query mode determined by the query prefix.
     */
    public enum QueryMode {

        /** Free-text keyword search. */
        KEYWORD,

        /** Direct tool selection by name ({@code select:}). */
        SELECT,

        /** Required keyword in name with optional ranking keywords ({@code +}). */
        REQUIRED
    }

}
