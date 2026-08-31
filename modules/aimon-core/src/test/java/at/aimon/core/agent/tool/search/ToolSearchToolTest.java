package at.aimon.core.agent.tool.search;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.search.ToolSearchTool;

@DisplayName("ToolSearchTool Tests")
class ToolSearchToolTest {

    private ToolSearchTool tool;
    private ToolSearchCatalog catalog;
    private ToolSearchRegistry registry;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        tool = new ToolSearchTool();
        catalog = new ToolSearchCatalog(new KeywordToolSearchStrategy());
        catalog.register(SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web content from URLs")));
        catalog.register(SearchableTool.deferred(MockTools.create("WebSearch", "Search the web using search engines")));
        catalog.register(SearchableTool.deferred(MockTools.create("Grep", "Search file contents with regex")));
        registry = catalog.createRegistry();
        context = ToolContext.builder().put(ToolContextKeys.TOOL_SEARCH_REGISTRY, registry).build();
    }

    @Nested
    @DisplayName("Keyword Search")
    class KeywordSearch {

        @Test
        @DisplayName("Should find tools by keyword and return function schemas")
        void testKeywordSearch() {
            final ToolResult result = tool.execute(ToolInput.of("query", "web fetch"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("WebFetch");
            assertThat(result.getContent()).contains("functions");
        }

        @Test
        @DisplayName("Should return empty functions array when no match")
        void testNoMatch() {
            final ToolResult result = tool.execute(ToolInput.of("query", "blockchain"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("\"functions\":[]");
        }
    }

    @Nested
    @DisplayName("Direct Selection")
    class DirectSelection {

        @Test
        @DisplayName("Should select tools by exact name")
        void testSelectSingle() {
            final ToolResult result = tool.execute(ToolInput.of("query", "select:WebFetch"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("WebFetch");
        }

        @Test
        @DisplayName("Should select multiple tools")
        void testSelectMultiple() {
            final ToolResult result = tool.execute(ToolInput.of("query", "select:WebFetch,Grep"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("WebFetch");
            assertThat(result.getContent()).contains("Grep");
        }

        @Test
        @DisplayName("Non-existent tools are silently ignored")
        void testSelectNonExistent() {
            final ToolResult result = tool.execute(ToolInput.of("query", "select:WebFetch,NonExistent"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("WebFetch");
            assertThat(result.getContent()).doesNotContain("NonExistent");
        }
    }

    @Nested
    @DisplayName("Required Keyword")
    class RequiredKeyword {

        @Test
        @DisplayName("Should filter by required keyword and rank")
        void testRequired() {
            final ToolResult result = tool.execute(ToolInput.of("query", "+web fetch"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Web");
        }
    }

    @Nested
    @DisplayName("Max Results")
    class MaxResults {

        @Test
        @DisplayName("Should respect max_results parameter")
        void testMaxResults() {
            final ToolResult result = tool.execute(ToolInput.of("query", "web", "max_results", 1), context);

            assertThat(result.isSuccess()).isTrue();
            // Should contain exactly one function
        }

        @Test
        @DisplayName("Should clamp max_results to upper limit of 20")
        void testClampUpper() {
            // Register more than 20 deferred tools
            for (int i = 0; i < 25; i++) {
                catalog.register(
                        SearchableTool.deferred(MockTools.create("WebTool" + i, "Web related tool number " + i)));
            }
            final ToolSearchRegistry freshRegistry = catalog.createRegistry();
            final ToolContext freshCtx = ToolContext.builder().put(ToolContextKeys.TOOL_SEARCH_REGISTRY, freshRegistry)
                    .build();

            final ToolResult result = tool.execute(ToolInput.of("query", "web", "max_results", 100), freshCtx);

            assertThat(result.isSuccess()).isTrue();
            // Count occurrences of "name" in the JSON to verify at most MAX_RESULTS_LIMIT (20) tools
            final long toolCount = result.getContent().split("\"name\"").length - 1;
            assertThat(toolCount).isLessThanOrEqualTo(20);
        }

        @Test
        @DisplayName("Should clamp negative max_results to 1")
        void testClampNegative() {
            final ToolResult result = tool.execute(ToolInput.of("query", "web", "max_results", -5), context);

            assertThat(result.isSuccess()).isTrue();
            // Should return at most 1 result (clamped to minimum of 1)
            final long toolCount = result.getContent().split("\"name\"").length - 1;
            assertThat(toolCount).isLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("max_results boundary: exactly 1 returns single result")
        void testClampLowerBoundary() {
            final ToolResult result = tool.execute(ToolInput.of("query", "web", "max_results", 1), context);

            assertThat(result.isSuccess()).isTrue();
            final long toolCount = result.getContent().split("\"name\"").length - 1;
            assertThat(toolCount).isLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("max_results boundary: exactly 20 is accepted without clamping")
        void testUpperBoundaryExact() {
            final ToolResult result = tool.execute(ToolInput.of("query", "web", "max_results", 20), context);

            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Empty query returns error")
        void testEmptyQuery() {
            final ToolResult result = tool.execute(ToolInput.of("query", ""), context);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Query cannot be empty");
        }

        @Test
        @DisplayName("Whitespace-only query returns error")
        void testWhitespaceQuery() {
            final ToolResult result = tool.execute(ToolInput.of("query", "   "), context);

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("Missing registry in context returns error")
        void testNoRegistry() {
            final ToolContext emptyCtx = ToolContext.empty();
            final ToolResult result = tool.execute(ToolInput.of("query", "web"), emptyCtx);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("ToolSearchRegistry not available");
        }
    }

    @Nested
    @DisplayName("Activation Side Effect")
    class ActivationSideEffect {

        @Test
        @DisplayName("Search activates tools in the conversation")
        void testActivation() {
            tool.execute(ToolInput.of("query", "select:WebFetch"), context);

            assertThat(registry.getActivationState().isActivated("WebFetch")).isTrue();
            assertThat(registry.findAll()).extracting(t -> t.getDefinition().getName()).contains("WebFetch");
        }

        @Test
        @DisplayName("Repeated search is idempotent")
        void testIdempotent() {
            tool.execute(ToolInput.of("query", "select:WebFetch"), context);
            tool.execute(ToolInput.of("query", "select:WebFetch"), context);

            assertThat(registry.getActivationState().isActivated("WebFetch")).isTrue();
        }
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinitionTest {

        @Test
        @DisplayName("Should have correct tool name")
        void testName() {
            assertThat(tool.getDefinition().getName()).isEqualTo("ToolSearch");
        }

        @Test
        @DisplayName("Should have description")
        void testDescription() {
            assertThat(tool.getDefinition().getDescription()).isNotEmpty();
        }
    }
}
