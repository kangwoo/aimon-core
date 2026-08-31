package at.aimon.core.tools.wiki;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.knowledge.wiki.LintReport;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBaseAdmin;
import at.aimon.core.knowledge.wiki.WikiLog;
import at.aimon.core.knowledge.wiki.WikiScope;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("WikiLintTool Tests")
class WikiLintToolTest {

    private WikiLintTool tool;
    private StubWikiAdmin admin;
    private ToolContext context;

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");

    @BeforeEach
    void setUp() {
        tool = new WikiLintTool();
        admin = new StubWikiAdmin();
        context = ToolContext.builder().put(ToolContextKeys.WIKI_KNOWLEDGE_BASE_ADMIN, admin)
                .put(ToolContextKeys.WIKI_SCOPE, SCOPE).build();
    }

    @Nested
    @DisplayName("Successful Lint")
    class SuccessfulLint {

        @Test
        @DisplayName("Healthy wiki returns success with HEALTHY message")
        void healthyWikiReturnsSuccessWithHealthyMessage() {
            admin.setReport(LintReport.builder().issues(Collections.emptyList()).checkedPageCount(5)
                    .checkedAt(Instant.now()).build());

            final ToolResult result = tool.execute(ToolInput.of(), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("HEALTHY");
        }

        @Test
        @DisplayName("Wiki with issues returns success with formatted issues")
        void wikiWithIssuesReturnsFormattedIssues() {
            admin.setReport(LintReport.builder()
                    .issues(List.of(
                            new LintReport.Issue(LintReport.Severity.WARNING, "/wiki/page.md",
                                    "Broken link to: /missing.md"),
                            new LintReport.Issue(LintReport.Severity.INFO, "/wiki/page2.md", "Page has no tags")))
                    .checkedPageCount(2).checkedAt(Instant.now()).build());

            final ToolResult result = tool.execute(ToolInput.of(), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("WARNING");
            assertThat(result.getContent()).contains("Broken link");
            assertThat(result.getContent()).contains("INFO");
            assertThat(result.getContent()).contains("Page has no tags");
        }

        @Test
        @DisplayName("Report includes checked page count")
        void reportIncludesCheckedPageCount() {
            admin.setReport(LintReport.builder().issues(Collections.emptyList()).checkedPageCount(7)
                    .checkedAt(Instant.now()).build());

            final ToolResult result = tool.execute(ToolInput.of(), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("7");
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("No wiki admin in context returns error")
        void noWikiAdminInContextReturnsError() {
            final ToolContext noAdminContext = ToolContext.builder().put(ToolContextKeys.WIKI_SCOPE, SCOPE).build();

            final ToolResult result = tool.execute(ToolInput.of(), noAdminContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No wiki knowledge base admin configured");
        }

        @Test
        @DisplayName("No wiki scope in context returns error")
        void noWikiScopeInContextReturnsError() {
            final ToolContext noScopeContext = ToolContext.builder()
                    .put(ToolContextKeys.WIKI_KNOWLEDGE_BASE_ADMIN, admin).build();

            final ToolResult result = tool.execute(ToolInput.of(), noScopeContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No wiki scope configured");
        }
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinition {

        @Test
        @DisplayName("Tool has correct name")
        void toolHasCorrectName() {
            assertThat(tool.getDefinition().getName()).isEqualTo("WikiLint");
        }

        @Test
        @DisplayName("Tool has non-empty description")
        void toolHasNonEmptyDescription() {
            assertThat(tool.getDefinition().getDescription()).isNotEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Stub WikiKnowledgeBaseAdmin
    // -------------------------------------------------------------------------

    static class StubWikiAdmin implements WikiKnowledgeBaseAdmin {

        private LintReport report = LintReport.builder().issues(Collections.emptyList()).checkedPageCount(0)
                .checkedAt(Instant.now()).build();

        void setReport(LintReport report) {
            this.report = report;
        }

        @Override
        public LintReport lint(WikiScope scope) {
            return report;
        }

        @Override
        public WikiLog getLog(WikiScope scope, int limit) {
            return WikiLog.builder().entries(Collections.emptyList()).totalEntryCount(0).build();
        }
    }
}
