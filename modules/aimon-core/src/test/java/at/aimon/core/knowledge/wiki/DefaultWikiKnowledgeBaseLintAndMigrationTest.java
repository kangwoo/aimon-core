package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("DefaultWikiKnowledgeBase Lint + Migration Tests")
class DefaultWikiKnowledgeBaseLintAndMigrationTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");
    private static final String WIKI_ROOT = "/wiki";

    private DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs;
    private DefaultWikiKnowledgeBase wiki;

    @BeforeEach
    void setUp() {
        wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator generator = new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator();
        wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT), generator);
    }

    private static WikiStorageLocator locator(VirtualFileSystem fs, String root) {
        return new WikiStorageLocator() {
            @Override
            public VirtualFileSystem fileSystemFor(WikiScope scope) {
                return fs;
            }

            @Override
            public String directoryFor(WikiScope scope) {
                return root + "/" + scope.getAgentName() + "/" + scope.getContextId() + "/" + scope.getWikiName();
            }
        };
    }

    private String pagesDir() {
        return WIKI_ROOT + "/" + SCOPE.getAgentName() + "/" + SCOPE.getContextId() + "/" + SCOPE.getWikiName()
                + "/pages/";
    }

    private String logPath() {
        return WIKI_ROOT + "/" + SCOPE.getAgentName() + "/" + SCOPE.getContextId() + "/" + SCOPE.getWikiName()
                + "/log.md";
    }

    @Nested
    @DisplayName("Phase 5 — broken [[slug]] wiki-link detection")
    class BrokenSlugLinks {

        @Test
        @DisplayName("[[existing-slug]] is healthy when target page exists")
        void healthySlugLink() {
            wikiVfs.addFile(pagesDir() + "entity-pod.md",
                    "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod\n\nSee [[service]].");
            wikiVfs.addFile(pagesDir() + "entity-service.md",
                    "---\ntitle: Service\ntype: entity\ntags: [k8s]\n---\n\n# Service\n\nSee [[pod]].");

            LintReport report = wiki.lint(SCOPE);

            assertThat(report.getIssues()).noneMatch(i -> i.getMessage().contains("Broken wiki-link"));
        }

        @Test
        @DisplayName("[[missing-slug]] reports a WARNING")
        void brokenSlugLinkReported() {
            wikiVfs.addFile(pagesDir() + "entity-pod.md",
                    "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod\n\nSee [[nonexistent]].");

            LintReport report = wiki.lint(SCOPE);

            assertThat(report.getIssues()).anyMatch(
                    i -> i.getSeverity() == LintReport.Severity.WARNING && i.getMessage().contains("[[nonexistent]]"));
        }

        @Test
        @DisplayName("slug-linked page is not flagged as orphan")
        void slugLinkContributesToInbound() {
            wikiVfs.addFile(pagesDir() + "entity-pod.md",
                    "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod\n\nSee [[service]].");
            wikiVfs.addFile(pagesDir() + "entity-service.md",
                    "---\ntitle: Service\ntype: entity\ntags: [k8s]\n---\n\n# Service");

            LintReport report = wiki.lint(SCOPE);

            assertThat(report.getIssues())
                    .noneMatch(i -> "/wiki/ops-agent/ctx-1/runbook/pages/entity-service.md".equals(i.getPagePath())
                            && i.getMessage().contains("Orphan"));
        }
    }

    @Nested
    @DisplayName("Phase 5 — duplicate title detection")
    class DuplicateTitles {

        @Test
        @DisplayName("two pages with the same title produce an INFO issue")
        void duplicateTitleReported() {
            wikiVfs.addFile(pagesDir() + "entity-pod-v1.md",
                    "---\ntitle: Kubernetes Pod\ntype: entity\ntags: [k8s]\n---\n\n# Kubernetes Pod");
            wikiVfs.addFile(pagesDir() + "entity-pod-v2.md",
                    "---\ntitle: Kubernetes Pod\ntype: entity\ntags: [k8s]\n---\n\n# Kubernetes Pod");

            LintReport report = wiki.lint(SCOPE);

            assertThat(report.getIssues()).anyMatch(
                    i -> i.getSeverity() == LintReport.Severity.INFO && i.getMessage().contains("Duplicate title"));
        }

        @Test
        @DisplayName("case and whitespace differences are normalized")
        void caseAndWhitespaceNormalized() {
            wikiVfs.addFile(pagesDir() + "entity-a.md",
                    "---\ntitle: kubernetes  pod\ntype: entity\ntags: [k8s]\n---\n\n# kubernetes pod");
            wikiVfs.addFile(pagesDir() + "entity-b.md",
                    "---\ntitle: KUBERNETES POD\ntype: entity\ntags: [k8s]\n---\n\n# KUBERNETES POD");

            LintReport report = wiki.lint(SCOPE);

            assertThat(report.getIssues()).anyMatch(i -> i.getMessage().contains("Duplicate title"));
        }

        @Test
        @DisplayName("unique titles produce no duplicate-title issues")
        void uniqueTitlesNoIssue() {
            wikiVfs.addFile(pagesDir() + "entity-pod.md", "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod");
            wikiVfs.addFile(pagesDir() + "entity-service.md",
                    "---\ntitle: Service\ntype: entity\ntags: [k8s]\n---\n\n# Service");

            LintReport report = wiki.lint(SCOPE);

            assertThat(report.getIssues()).noneMatch(i -> i.getMessage().contains("Duplicate title"));
        }
    }

    @Nested
    @DisplayName("Gap #2 — semantic lint strategy wiring")
    class SemanticLintWiring {

        @Test
        @DisplayName("semantic lint strategy findings are merged with structural findings")
        void semanticLintMerged() {
            RecordingLintStrategy semantic = new RecordingLintStrategy();
            semantic.queue(new LintReport.Issue(LintReport.Severity.WARNING,
                    "/wiki/ops-agent/ctx-1/runbook/pages/entity-pod.md", "[CONTRADICTION] stub contradiction finding"));
            DefaultWikiKnowledgeBase wikiWithSemantic = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator(),
                    new at.aimon.core.knowledge.wiki.IndexFirstSearchStrategy(), null, null, null, semantic);

            wikiVfs.addFile(pagesDir() + "entity-pod.md", "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod");

            LintReport report = wikiWithSemantic.lint(SCOPE);

            assertThat(semantic.callCount.get()).isEqualTo(1);
            assertThat(semantic.lastPages).hasSize(1);
            assertThat(report.getIssues()).anyMatch(i -> i.getMessage().contains("[CONTRADICTION]"));
        }

        @Test
        @DisplayName("no semantic lint strategy wired → lint returns only structural findings")
        void noSemanticStrategyWired() {
            wikiVfs.addFile(pagesDir() + "entity-pod.md", "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod");

            LintReport report = wiki.lint(SCOPE);

            assertThat(report.getIssues()).noneMatch(i -> i.getMessage().contains("[CONTRADICTION]"));
        }

        @Test
        @DisplayName("semantic strategy throwing is converted to a single ERROR issue")
        void semanticStrategyThrows() {
            RecordingLintStrategy semantic = new RecordingLintStrategy();
            semantic.throwOnNext = new RuntimeException("lint blew up");
            DefaultWikiKnowledgeBase wikiWithSemantic = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator(),
                    new at.aimon.core.knowledge.wiki.IndexFirstSearchStrategy(), null, null, null, semantic);

            wikiVfs.addFile(pagesDir() + "entity-pod.md", "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod");

            LintReport report = wikiWithSemantic.lint(SCOPE);

            assertThat(report.getIssues()).anyMatch(i -> i.getSeverity() == LintReport.Severity.ERROR
                    && i.getMessage().contains("Semantic lint failed: lint blew up"));
        }

        @Test
        @DisplayName("empty wiki skips the semantic lint call entirely")
        void emptyWikiSkipsSemanticCall() {
            RecordingLintStrategy semantic = new RecordingLintStrategy();
            DefaultWikiKnowledgeBase wikiWithSemantic = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator(),
                    new at.aimon.core.knowledge.wiki.IndexFirstSearchStrategy(), null, null, null, semantic);

            wikiWithSemantic.lint(SCOPE);

            assertThat(semantic.callCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("Phase 6 — migrateFrontmatter end-to-end")
    class MigrationEndToEnd {

        @Test
        @DisplayName("rewrites pages without type, inferring from file name prefix")
        void rewritesUntypedPages() {
            wikiVfs.addFile(pagesDir() + "summary-foo.md", "---\ntitle: Foo\ntags: []\n---\n\n# Foo\nBody.");
            wikiVfs.addFile(pagesDir() + "entity-bar.md", "---\ntitle: Bar\ntags: [k8s]\n---\n\n# Bar");

            MigrationResult result = wiki.migrateFrontmatter(SCOPE);

            assertThat(result.getMigratedCount()).isEqualTo(2);
            assertThat(result.getSkippedCount()).isZero();
            assertThat(wikiVfs.getFileContent(pagesDir() + "summary-foo.md")).contains("type: summary")
                    .contains("title: Foo").contains("# Foo");
            assertThat(wikiVfs.getFileContent(pagesDir() + "entity-bar.md")).contains("type: entity");
        }

        @Test
        @DisplayName("leaves pages that already have a type field alone")
        void skipsAlreadyTypedPages() {
            wikiVfs.addFile(pagesDir() + "entity-bar.md", "---\ntitle: Bar\ntype: entity\ntags: []\n---\n\n# Bar");

            MigrationResult result = wiki.migrateFrontmatter(SCOPE);

            assertThat(result.getMigratedCount()).isZero();
            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(wikiVfs.getFileContent(pagesDir() + "entity-bar.md"))
                    .isEqualTo("---\ntitle: Bar\ntype: entity\ntags: []\n---\n\n# Bar");
        }

        @Test
        @DisplayName("migration is idempotent on a mixed wiki")
        void idempotent() {
            wikiVfs.addFile(pagesDir() + "summary-foo.md", "---\ntitle: Foo\ntags: []\n---\n\n# Foo");
            wikiVfs.addFile(pagesDir() + "entity-bar.md", "---\ntitle: Bar\ntype: entity\ntags: []\n---\n\n# Bar");

            MigrationResult first = wiki.migrateFrontmatter(SCOPE);
            MigrationResult second = wiki.migrateFrontmatter(SCOPE);

            assertThat(first.getMigratedCount()).isEqualTo(1);
            assertThat(first.getSkippedCount()).isEqualTo(1);
            assertThat(second.getMigratedCount()).isZero();
            assertThat(second.getSkippedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("unrecognized file name prefix defaults to summary type")
        void defaultsToSummaryForUnknownPrefix() {
            wikiVfs.addFile(pagesDir() + "random-thing.md", "---\ntitle: Foo\n---\n\n# Foo");

            MigrationResult result = wiki.migrateFrontmatter(SCOPE);

            assertThat(result.getMigratedCount()).isEqualTo(1);
            assertThat(wikiVfs.getFileContent(pagesDir() + "random-thing.md")).contains("type: summary");
        }

        @Test
        @DisplayName("page without frontmatter is skipped, not corrupted")
        void noFrontmatterSkipped() {
            String original = "# Foo\n\nBody only, no frontmatter.";
            wikiVfs.addFile(pagesDir() + "summary-foo.md", original);

            MigrationResult result = wiki.migrateFrontmatter(SCOPE);

            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getMigratedCount()).isZero();
            assertThat(wikiVfs.getFileContent(pagesDir() + "summary-foo.md")).isEqualTo(original);
        }

        @Test
        @DisplayName("returns empty result when pages directory does not exist")
        void emptyWhenNoPagesDir() {
            MigrationResult result = wiki.migrateFrontmatter(SCOPE);

            assertThat(result.getMigratedCount()).isZero();
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("successful migration appends a MIGRATION_PERFORMED entry to the log")
        void logsMigrationEntry() {
            wikiVfs.addFile(pagesDir() + "summary-foo.md", "---\ntitle: Foo\ntags: []\n---\n\n# Foo");

            wiki.migrateFrontmatter(SCOPE);

            String logContent = wikiVfs.getFileContent(logPath());
            assertThat(logContent).contains("MIGRATION_PERFORMED").contains("Migrated 1 pages");
        }

        @Test
        @DisplayName("no-op migration (nothing to do) does not append a log entry")
        void noOpMigrationDoesNotLog() {
            wikiVfs.addFile(pagesDir() + "entity-bar.md", "---\ntitle: Bar\ntype: entity\ntags: []\n---\n\n# Bar");

            wiki.migrateFrontmatter(SCOPE);

            assertThat(wikiVfs.getFileContent(logPath())).isNull();
        }
    }

    /**
     * Recording lint strategy that captures invocations and returns pre-queued findings. Lets the integration
     * tests verify the wiring from lint() → strategy without involving an LLM.
     */
    private static final class RecordingLintStrategy implements WikiLintStrategy {

        final AtomicInteger callCount = new AtomicInteger(0);
        List<WikiPage> lastPages = List.of();
        final List<LintReport.Issue> queued = new ArrayList<>();
        RuntimeException throwOnNext;

        void queue(LintReport.Issue issue) {
            queued.add(issue);
        }

        @Override
        public List<LintReport.Issue> lint(WikiScope scope, List<WikiPage> pages) {
            callCount.incrementAndGet();
            lastPages = List.copyOf(pages);
            if (throwOnNext != null) {
                final RuntimeException e = throwOnNext;
                throwOnNext = null;
                throw e;
            }
            return List.copyOf(queued);
        }
    }
}
