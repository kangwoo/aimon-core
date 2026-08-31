package at.aimon.core.tools.wiki;

import static org.assertj.core.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.knowledge.wiki.FiledAnswer;
import at.aimon.core.knowledge.wiki.IngestOptions;
import at.aimon.core.knowledge.wiki.IngestResult;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBase;
import at.aimon.core.knowledge.wiki.WikiPage;
import at.aimon.core.knowledge.wiki.WikiScope;
import at.aimon.core.knowledge.wiki.WikiSearchQuery;
import at.aimon.core.knowledge.wiki.WikiSource;
import at.aimon.core.knowledge.wiki.WikiStatus;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("WikiSearchTool Tests")
class WikiSearchToolTest {

    private WikiSearchTool tool;
    private StubWikiKnowledgeBase wikiKnowledgeBase;
    private ToolContext context;

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");

    @BeforeEach
    void setUp() {
        tool = new WikiSearchTool();
        wikiKnowledgeBase = new StubWikiKnowledgeBase();
        context = ToolContext.builder().put(ToolContextKeys.WIKI_KNOWLEDGE_BASE, wikiKnowledgeBase)
                .put(ToolContextKeys.WIKI_SCOPE, SCOPE).build();
    }

    @Nested
    @DisplayName("Successful Search")
    class SuccessfulSearch {

        @Test
        @DisplayName("Returns formatted results when pages found")
        void returnsFormattedResultsWhenPagesFound() {
            wikiKnowledgeBase.setResults(List.of(
                    WikiPage.builder().path("/wiki/ops-agent/runbook/pages/summary-k8s.md").title("Kubernetes Basics")
                            .content("# Kubernetes Basics\nPods are the smallest deployable units.").build()));

            final ToolResult result = tool.execute(ToolInput.of("query", "kubernetes"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Kubernetes Basics");
            assertThat(result.getContent()).contains("summary-k8s.md");
            assertThat(result.getContent()).contains("kubernetes");
        }

        @Test
        @DisplayName("Returns no results message when empty")
        void returnsNoResultsMessageWhenEmpty() {
            wikiKnowledgeBase.setResults(Collections.emptyList());

            final ToolResult result = tool.execute(ToolInput.of("query", "blockchain"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("No wiki pages found");
            assertThat(result.getContent()).contains("blockchain");
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("No wiki knowledge base in context returns error")
        void noWikiKnowledgeBaseInContextReturnsError() {
            final ToolContext emptyContext = ToolContext.empty();

            final ToolResult result = tool.execute(ToolInput.of("query", "test"), emptyContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No wiki knowledge base configured");
        }

        @Test
        @DisplayName("No wiki scope in context returns error")
        void noWikiScopeInContextReturnsError() {
            final ToolContext noScopeContext = ToolContext.builder()
                    .put(ToolContextKeys.WIKI_KNOWLEDGE_BASE, wikiKnowledgeBase).build();

            final ToolResult result = tool.execute(ToolInput.of("query", "test"), noScopeContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No wiki scope configured");
        }

        @Test
        @DisplayName("Missing query parameter returns error")
        void missingQueryParameterReturnsError() {
            final ToolResult result = tool.execute(ToolInput.of(), context);

            assertThat(result.isError()).isTrue();
        }
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinition {

        @Test
        @DisplayName("Tool has correct name")
        void toolHasCorrectName() {
            assertThat(tool.getDefinition().getName()).isEqualTo("WikiSearch");
        }

        @Test
        @DisplayName("Tool has non-empty description")
        void toolHasNonEmptyDescription() {
            assertThat(tool.getDefinition().getDescription()).isNotEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Stub WikiKnowledgeBase for tool testing
    // -------------------------------------------------------------------------

    static class StubWikiKnowledgeBase implements WikiKnowledgeBase {

        private List<WikiPage> results = Collections.emptyList();

        void setResults(List<WikiPage> results) {
            this.results = results;
        }

        @Override
        public IngestResult ingest(WikiScope scope, WikiSource source, IngestOptions options) {
            return IngestResult.builder().build();
        }

        @Override
        public List<WikiPage> search(WikiScope scope, WikiSearchQuery query) {
            return results;
        }

        @Override
        public Optional<WikiPage> getPage(WikiScope scope, String pagePath) {
            return Optional.empty();
        }

        @Override
        public WikiStatus getStatus(WikiScope scope) {
            return WikiStatus.builder().state(WikiStatus.State.READY).build();
        }

        @Override
        public WikiPage fileAnswer(WikiScope scope, FiledAnswer answer) {
            return WikiPage.builder().path("/wiki/stub.md").title(answer.getTitle()).content(answer.getContent())
                    .build();
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
