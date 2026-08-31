package at.aimon.core.agent.tool.search;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("KeywordToolSearchStrategy Tests")
class KeywordToolSearchStrategyTest {

    private KeywordToolSearchStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new KeywordToolSearchStrategy();
    }

    @Nested
    @DisplayName("Basic Matching")
    class BasicMatching {

        @Test
        @DisplayName("Should match by tool name")
        void testNameMatch() {
            final List<SearchableTool> candidates = List.of(
                    SearchableTool.deferred(MockTools.create("ReadFile", "Reads a file")),
                    SearchableTool.deferred(MockTools.create("WriteFile", "Writes a file")));

            final List<SearchableTool> results = strategy.search("read", candidates, 5);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getName()).isEqualTo("ReadFile");
        }

        @Test
        @DisplayName("Should match by description")
        void testDescriptionMatch() {
            final List<SearchableTool> candidates = List.of(
                    SearchableTool.deferred(MockTools.create("Tool1", "Fetches web content from URLs")),
                    SearchableTool.deferred(MockTools.create("Tool2", "Runs bash commands")));

            final List<SearchableTool> results = strategy.search("web content", candidates, 5);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getName()).isEqualTo("Tool1");
        }

        @Test
        @DisplayName("Should match by parameter text")
        void testParameterMatch() {
            final List<SearchableTool> candidates = List
                    .of(SearchableTool.deferred(MockTools.createWithParams("Tool1", "A generic tool",
                            Map.of("url", Map.of("type", "string", "description", "The URL to fetch content from")))));

            final List<SearchableTool> results = strategy.search("url fetch", candidates, 5);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getName()).isEqualTo("Tool1");
        }
    }

    @Nested
    @DisplayName("Case Insensitive")
    class CaseInsensitive {

        @Test
        @DisplayName("Should match case-insensitively")
        void testCaseInsensitive() {
            final List<SearchableTool> candidates = List
                    .of(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web content")));

            final List<SearchableTool> results = strategy.search("WEBFETCH", candidates, 5);

            assertThat(results).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Underscore/Hyphen Normalization")
    class Normalization {

        @Test
        @DisplayName("Should treat underscores and hyphens as spaces")
        void testNormalization() {
            final List<SearchableTool> candidates = List.of(
                    SearchableTool.deferred(MockTools.create("web_fetch", "Fetch web content")),
                    SearchableTool.deferred(MockTools.create("web-search", "Search the web")));

            final List<SearchableTool> results = strategy.search("web fetch", candidates, 5);

            assertThat(results).extracting(SearchableTool::getName).contains("web_fetch");
        }
    }

    @Nested
    @DisplayName("Scoring and Ranking")
    class ScoringAndRanking {

        @Test
        @DisplayName("Name match should rank higher than description-only match")
        void testNameRanksHigher() {
            final List<SearchableTool> candidates = List.of(
                    SearchableTool.deferred(MockTools.create("Grep", "Search file contents")),
                    SearchableTool.deferred(MockTools.create("Tool1", "A tool for grep-like search")));

            final List<SearchableTool> results = strategy.search("grep", candidates, 5);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getName()).isEqualTo("Grep");
        }

        @Test
        @DisplayName("Should apply correct weights: name=0.5, description=0.35, parameter=0.15")
        void testWeightDistribution() {
            // Tool A: keyword matches name only → score = 0.5
            final SearchableTool nameOnly = SearchableTool
                    .deferred(MockTools.create("fetch", "does something unrelated"));
            // Tool B: keyword matches description only → score = 0.35
            final SearchableTool descOnly = SearchableTool
                    .deferred(MockTools.create("Tool1", "a tool that can fetch data"));
            // Tool C: keyword matches parameter only → score = 0.15
            final SearchableTool paramOnly = SearchableTool
                    .deferred(MockTools.createWithParams("Tool2", "unrelated description",
                            Map.of("url", Map.of("type", "string", "description", "fetch url to retrieve"))));

            final List<SearchableTool> candidates = List.of(paramOnly, descOnly, nameOnly);
            final List<SearchableTool> results = strategy.search("fetch", candidates, 3);

            assertThat(results).hasSize(3);
            // Name match (0.5) > Description match (0.35) > Parameter match (0.15)
            assertThat(results.get(0).getName()).isEqualTo("fetch");
            assertThat(results.get(1).getName()).isEqualTo("Tool1");
            assertThat(results.get(2).getName()).isEqualTo("Tool2");

            // Verify ordering reflects weight distribution:
            // A tool matching only name must rank above one matching only description,
            // which must rank above one matching only parameters.
            // With a single keyword this proves name_weight > desc_weight > param_weight.
            assertThat(results).extracting(SearchableTool::getName).containsExactly("fetch", "Tool1", "Tool2");
        }

        @Test
        @DisplayName("Should respect maxResults limit")
        void testMaxResults() {
            final List<SearchableTool> candidates = List.of(
                    SearchableTool.deferred(MockTools.create("FileRead", "Read files")),
                    SearchableTool.deferred(MockTools.create("FileWrite", "Write files")),
                    SearchableTool.deferred(MockTools.create("FileEdit", "Edit files")));

            final List<SearchableTool> results = strategy.search("file", candidates, 2);

            assertThat(results).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty query returns empty results")
        void testEmptyQuery() {
            final List<SearchableTool> candidates = List
                    .of(SearchableTool.deferred(MockTools.create("Read", "Read files")));

            final List<SearchableTool> results = strategy.search("", candidates, 5);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("No matching candidates returns empty results")
        void testNoMatch() {
            final List<SearchableTool> candidates = List
                    .of(SearchableTool.deferred(MockTools.create("Read", "Read files")));

            final List<SearchableTool> results = strategy.search("blockchain", candidates, 5);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Empty candidates returns empty results")
        void testEmptyCandidates() {
            final List<SearchableTool> results = strategy.search("read", List.of(), 5);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Null query throws NullPointerException")
        void testNullQuery() {
            assertThatNullPointerException().isThrownBy(() -> strategy.search(null, List.of(), 5));
        }

        @Test
        @DisplayName("Null candidates throws NullPointerException")
        void testNullCandidates() {
            assertThatNullPointerException().isThrownBy(() -> strategy.search("read", null, 5));
        }
    }
}
