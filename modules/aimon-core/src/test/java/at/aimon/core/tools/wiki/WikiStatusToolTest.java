package at.aimon.core.tools.wiki;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
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
import at.aimon.core.knowledge.wiki.LintReport;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBase;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBaseAdmin;
import at.aimon.core.knowledge.wiki.WikiLog;
import at.aimon.core.knowledge.wiki.WikiLogEntry;
import at.aimon.core.knowledge.wiki.WikiPage;
import at.aimon.core.knowledge.wiki.WikiScope;
import at.aimon.core.knowledge.wiki.WikiSearchQuery;
import at.aimon.core.knowledge.wiki.WikiSource;
import at.aimon.core.knowledge.wiki.WikiStatus;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("WikiStatusTool Tests")
class WikiStatusToolTest {

    private WikiStatusTool tool;
    private StubWikiKnowledgeBase wikiKnowledgeBase;
    private StubWikiAdmin wikiAdmin;
    private ToolContext context;

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");

    @BeforeEach
    void setUp() {
        tool = new WikiStatusTool();
        wikiKnowledgeBase = new StubWikiKnowledgeBase();
        wikiAdmin = new StubWikiAdmin();
        context = ToolContext.builder().put(ToolContextKeys.WIKI_KNOWLEDGE_BASE, wikiKnowledgeBase)
                .put(ToolContextKeys.WIKI_SCOPE, SCOPE).put(ToolContextKeys.WIKI_KNOWLEDGE_BASE_ADMIN, wikiAdmin)
                .build();
    }

    @Nested
    @DisplayName("Successful Status")
    class SuccessfulStatus {

        @Test
        @DisplayName("Returns formatted status with scope, state, and counts")
        void returnsFormattedStatus() {
            wikiKnowledgeBase.setStatus(WikiStatus.builder().state(WikiStatus.State.READY).pageCount(12).sourceCount(5)
                    .wikiDirectory("/wiki/ops-agent/runbook").build());

            final ToolResult result = tool.execute(ToolInput.of(), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("ops-agent");
            assertThat(result.getContent()).contains("runbook");
            assertThat(result.getContent()).contains("READY");
            assertThat(result.getContent()).contains("12");
            assertThat(result.getContent()).contains("5");
        }

        @Test
        @DisplayName("include_log=true includes log entries in output")
        void includeLogTrueIncludesLogEntries() {
            wikiKnowledgeBase.setStatus(WikiStatus.builder().state(WikiStatus.State.READY).build());
            wikiAdmin.setLog(WikiLog.builder()
                    .entries(List.of(WikiLogEntry.builder().timestamp(Instant.now())
                            .operation(WikiLogEntry.Operation.SOURCE_INGESTED).pagePath("/wiki/page.md")
                            .summary("Created from source").build()))
                    .totalEntryCount(1).build());

            final ToolResult result = tool.execute(ToolInput.of("include_log", true), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Recent Log");
            assertThat(result.getContent()).contains("SOURCE_INGESTED");
        }

        @Test
        @DisplayName("include_log=false does not include log section")
        void includeLogFalseDoesNotIncludeLog() {
            wikiKnowledgeBase.setStatus(WikiStatus.builder().state(WikiStatus.State.EMPTY).build());

            final ToolResult result = tool.execute(ToolInput.of("include_log", false), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).doesNotContain("Recent Log");
        }

        @Test
        @DisplayName("EMPTY state wiki shows never for last ingested")
        void emptyStateShowsNeverForLastIngested() {
            wikiKnowledgeBase.setStatus(WikiStatus.builder().state(WikiStatus.State.EMPTY).pageCount(0).build());

            final ToolResult result = tool.execute(ToolInput.of(), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("never");
        }

        @Test
        @DisplayName("include_log=true with no admin configured omits log without error")
        void includeLogTrueWithNoAdminOmitsLog() {
            final ToolContext noAdminContext = ToolContext.builder()
                    .put(ToolContextKeys.WIKI_KNOWLEDGE_BASE, wikiKnowledgeBase).put(ToolContextKeys.WIKI_SCOPE, SCOPE)
                    .build();
            wikiKnowledgeBase.setStatus(WikiStatus.builder().state(WikiStatus.State.READY).build());

            final ToolResult result = tool.execute(ToolInput.of("include_log", true), noAdminContext);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).doesNotContain("Recent Log");
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("No wiki knowledge base in context returns error")
        void noWikiKnowledgeBaseReturnsError() {
            final ToolContext noWikiContext = ToolContext.builder().put(ToolContextKeys.WIKI_SCOPE, SCOPE).build();

            final ToolResult result = tool.execute(ToolInput.of(), noWikiContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No wiki knowledge base configured");
        }

        @Test
        @DisplayName("No wiki scope in context returns error")
        void noWikiScopeReturnsError() {
            final ToolContext noScopeContext = ToolContext.builder()
                    .put(ToolContextKeys.WIKI_KNOWLEDGE_BASE, wikiKnowledgeBase).build();

            final ToolResult result = tool.execute(ToolInput.of(), noScopeContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No wiki scope configured");
        }

        @Test
        @DisplayName("log_limit of 0 returns error")
        void logLimitZeroReturnsError() {
            wikiKnowledgeBase.setStatus(WikiStatus.builder().state(WikiStatus.State.READY).build());

            final ToolResult result = tool.execute(ToolInput.of("log_limit", 0), context);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("log_limit must be >= 1");
        }
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinition {

        @Test
        @DisplayName("Tool has correct name")
        void toolHasCorrectName() {
            assertThat(tool.getDefinition().getName()).isEqualTo("WikiStatus");
        }

        @Test
        @DisplayName("Tool has non-empty description")
        void toolHasNonEmptyDescription() {
            assertThat(tool.getDefinition().getDescription()).isNotEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    static class StubWikiKnowledgeBase implements WikiKnowledgeBase {

        private WikiStatus status = WikiStatus.builder().state(WikiStatus.State.EMPTY).build();

        void setStatus(WikiStatus status) {
            this.status = status;
        }

        @Override
        public IngestResult ingest(WikiScope scope, WikiSource source, IngestOptions options) {
            return IngestResult.builder().build();
        }

        @Override
        public List<WikiPage> search(WikiScope scope, WikiSearchQuery query) {
            return Collections.emptyList();
        }

        @Override
        public Optional<WikiPage> getPage(WikiScope scope, String pagePath) {
            return Optional.empty();
        }

        @Override
        public WikiStatus getStatus(WikiScope scope) {
            return status;
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

    static class StubWikiAdmin implements WikiKnowledgeBaseAdmin {

        private WikiLog wikiLog = WikiLog.builder().entries(Collections.emptyList()).totalEntryCount(0).build();

        void setLog(WikiLog wikiLog) {
            this.wikiLog = wikiLog;
        }

        @Override
        public LintReport lint(WikiScope scope) {
            return LintReport.builder().issues(Collections.emptyList()).checkedPageCount(0).checkedAt(Instant.now())
                    .build();
        }

        @Override
        public WikiLog getLog(WikiScope scope, int limit) {
            return wikiLog;
        }
    }
}
