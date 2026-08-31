package at.aimon.core.agent.tool.search;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RequiredKeywordToolSearchStrategy Tests")
class RequiredKeywordToolSearchStrategyTest {

    private RequiredKeywordToolSearchStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new RequiredKeywordToolSearchStrategy(new KeywordToolSearchStrategy());
    }

    @Nested
    @DisplayName("Required Keyword Filtering")
    class RequiredFiltering {

        @Test
        @DisplayName("Should filter by required keyword in tool name")
        void testFilter() {
            final List<SearchableTool> candidates = List.of(
                    SearchableTool.deferred(MockTools.create("mcp__slack__send_message", "Send a Slack message")),
                    SearchableTool.deferred(MockTools.create("mcp__slack__send_dm", "Send a Slack DM")),
                    SearchableTool.deferred(MockTools.create("mcp__github__create_pr", "Create a GitHub PR")));

            final List<SearchableTool> results = strategy.search("send", candidates, "slack", 5);

            assertThat(results).hasSize(2);
            assertThat(results).extracting(SearchableTool::getName)
                    .allMatch(name -> name.toLowerCase().contains("slack"));
        }

        @Test
        @DisplayName("Should return empty when no names match required keyword")
        void testNoFilterMatch() {
            final List<SearchableTool> candidates = List
                    .of(SearchableTool.deferred(MockTools.create("Read", "Read files")));

            final List<SearchableTool> results = strategy.search("", candidates, "slack", 5);

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("Ranking")
    class Ranking {

        @Test
        @DisplayName("Should rank by ranking keywords after filtering")
        void testRanking() {
            final List<SearchableTool> candidates = List.of(
                    SearchableTool.deferred(MockTools.create("mcp__slack__send_message", "Send a Slack message")),
                    SearchableTool.deferred(MockTools.create("mcp__slack__list_channels", "List Slack channels")));

            final List<SearchableTool> results = strategy.search("send message", candidates, "slack", 5);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getName()).isEqualTo("mcp__slack__send_message");
        }

        @Test
        @DisplayName("Empty ranking keywords returns filtered results sorted by name")
        void testEmptyRanking() {
            final List<SearchableTool> candidates = List.of(
                    SearchableTool.deferred(MockTools.create("mcp__slack__b_tool", "B tool")),
                    SearchableTool.deferred(MockTools.create("mcp__slack__a_tool", "A tool")));

            final List<SearchableTool> results = strategy.search("", candidates, "slack", 5);

            assertThat(results).extracting(SearchableTool::getName).containsExactly("mcp__slack__a_tool",
                    "mcp__slack__b_tool");
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty required keyword uses all candidates")
        void testEmptyRequired() {
            final List<SearchableTool> candidates = List.of(
                    SearchableTool.deferred(MockTools.create("Read", "Read files")),
                    SearchableTool.deferred(MockTools.create("Write", "Write files")));

            final List<SearchableTool> results = strategy.search("", candidates, "", 5);

            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("Should respect maxResults limit")
        void testMaxResults() {
            final List<SearchableTool> candidates = List.of(SearchableTool.deferred(MockTools.create("slack_a", "a")),
                    SearchableTool.deferred(MockTools.create("slack_b", "b")),
                    SearchableTool.deferred(MockTools.create("slack_c", "c")));

            final List<SearchableTool> results = strategy.search("", candidates, "slack", 2);

            assertThat(results).hasSize(2);
        }
    }
}
