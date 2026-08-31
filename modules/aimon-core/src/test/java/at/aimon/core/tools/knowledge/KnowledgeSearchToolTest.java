package at.aimon.core.tools.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.knowledge.IndexOptions;
import at.aimon.core.knowledge.IndexResult;
import at.aimon.core.knowledge.IndexStatus;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeSource;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("KnowledgeSearchTool Tests")
class KnowledgeSearchToolTest {

    private KnowledgeSearchTool tool;
    private StubKnowledgeStore knowledgeStore;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeSearchTool();
        knowledgeStore = new StubKnowledgeStore();
        context = ToolContext.builder().put(ToolContextKeys.KNOWLEDGE_STORE, knowledgeStore)
                .put(ToolContextKeys.KNOWLEDGE_SCOPE, new KnowledgeScope("test-agent", "ctx-001")).build();
    }

    @Nested
    @DisplayName("Successful Search")
    class SuccessfulSearch {

        @Test
        @DisplayName("Should return formatted results")
        void testSearchWithResults() {
            knowledgeStore.setResults(List.of(SearchResult.builder().documentPath("/knowledge/runbook.md")
                    .chunkContent("## CrashLoopBackOff\nCheck pod logs.").score(0.92).chunkIndex(3).build()));

            final ToolResult result = tool.execute(ToolInput.of("query", "CrashLoopBackOff"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("runbook.md");
            assertThat(result.getContent()).contains("0.92");
            assertThat(result.getContent()).contains("CrashLoopBackOff");
        }

        @Test
        @DisplayName("Should pass max_results parameter")
        void testMaxResultsParameter() {
            knowledgeStore.setResults(List.of(SearchResult.builder().documentPath("/doc.md").chunkContent("content")
                    .score(0.5).chunkIndex(0).build()));

            final ToolResult result = tool.execute(ToolInput.of("query", "test", "max_results", 3), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(knowledgeStore.getLastQuery().getMaxResults()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should pass file_pattern parameter")
        void testFilePatternParameter() {
            knowledgeStore.setResults(Collections.emptyList());

            tool.execute(ToolInput.of("query", "test", "file_pattern", "*.md"), context);

            assertThat(knowledgeStore.getLastQuery().getFilePatterns()).containsExactly("*.md");
        }
    }

    @Nested
    @DisplayName("Empty Results")
    class EmptyResults {

        @Test
        @DisplayName("Should return message when no results found")
        void testNoResults() {
            knowledgeStore.setResults(Collections.emptyList());

            final ToolResult result = tool.execute(ToolInput.of("query", "nonexistent"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("No relevant documents found");
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should return error when no knowledge store configured")
        void testNoKnowledgeStore() {
            final ToolContext emptyContext = ToolContext.empty();

            final ToolResult result = tool.execute(ToolInput.of("query", "test"), emptyContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No knowledge store configured");
        }

        @Test
        @DisplayName("Should return error for missing query parameter")
        void testMissingQuery() {
            final ToolResult result = tool.execute(ToolInput.of(), context);

            assertThat(result.isError()).isTrue();
        }
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinition {

        @Test
        @DisplayName("Should have correct tool name")
        void testToolName() {
            assertThat(tool.getDefinition().getName()).isEqualTo("KnowledgeSearch");
        }

        @Test
        @DisplayName("Should have description")
        void testDescription() {
            assertThat(tool.getDefinition().getDescription()).isNotEmpty();
        }
    }

    /**
     * Stub KnowledgeStore for tool testing.
     */
    static class StubKnowledgeStore implements KnowledgeStore {

        private List<SearchResult> results = Collections.emptyList();
        private SearchQuery lastQuery;

        void setResults(List<SearchResult> results) {
            this.results = results;
        }

        SearchQuery getLastQuery() {
            return lastQuery;
        }

        @Override
        public IndexResult index(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
            return IndexResult.builder().build();
        }

        @Override
        public IndexResult reindex(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
            return IndexResult.builder().build();
        }

        @Override
        public List<SearchResult> search(KnowledgeScope scope, SearchQuery query) {
            this.lastQuery = query;
            return results;
        }

        @Override
        public IndexStatus getStatus() {
            return IndexStatus.builder().state(IndexStatus.State.READY).build();
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
