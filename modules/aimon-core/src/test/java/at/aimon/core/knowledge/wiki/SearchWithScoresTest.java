package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("searchWithScores (Query Improvement 2)")
class SearchWithScoresTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");
    private static final String WIKI_ROOT = "/wiki";

    private DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs;

    @BeforeEach
    void setUp() {
        wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        seedPages();
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

    private void seedPages() {
        // Page A mentions kubernetes 5 times → highest score
        // Page B mentions kubernetes 2 times → middle score
        // Page C mentions kubernetes 1 time → lowest score
        wikiVfs.addFile(pagesDir() + "summary-a.md",
                "---\ntitle: A\ntype: summary\ntags: []\n---\n\n# A\n\nkubernetes kubernetes kubernetes kubernetes kubernetes");
        wikiVfs.addFile(pagesDir() + "summary-b.md",
                "---\ntitle: B\ntype: summary\ntags: []\n---\n\n# B\n\nkubernetes kubernetes");
        wikiVfs.addFile(pagesDir() + "summary-c.md",
                "---\ntitle: C\ntype: summary\ntags: []\n---\n\n# C\n\nkubernetes");
    }

    @Nested
    @DisplayName("FullScanSearchStrategy")
    class FullScan {

        @Test
        @DisplayName("returns results ordered by descending score with real values")
        void scoresAreReal() {
            WikiKnowledgeBase wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator(), new FullScanSearchStrategy());

            List<WikiSearchResult> results = wiki.searchWithScores(SCOPE,
                    WikiSearchQuery.builder().queryText("kubernetes").build());

            assertThat(results).hasSize(3);
            // Descending order
            assertThat(results.get(0).getScore()).isGreaterThan(results.get(1).getScore());
            assertThat(results.get(1).getScore()).isGreaterThan(results.get(2).getScore());
            // First result has the most occurrences
            assertThat(results.get(0).getPage().getTitle()).isEqualTo("A");
            assertThat(results.get(0).getScore()).isEqualTo(5.0);
            assertThat(results.get(2).getPage().getTitle()).isEqualTo("C");
            assertThat(results.get(2).getScore()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("plain search() returns the same pages without scores")
        void plainSearchReturnsPages() {
            WikiKnowledgeBase wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator(), new FullScanSearchStrategy());

            List<WikiPage> pages = wiki.search(SCOPE, WikiSearchQuery.builder().queryText("kubernetes").build());

            assertThat(pages).extracting(WikiPage::getTitle).containsExactly("A", "B", "C");
        }
    }

    @Nested
    @DisplayName("IndexFirstSearchStrategy")
    class IndexFirst {

        @BeforeEach
        void seedIndex() {
            wikiVfs.addFile(
                    WIKI_ROOT + "/" + SCOPE.getAgentName() + "/" + SCOPE.getContextId() + "/" + SCOPE.getWikiName()
                            + "/index.md",
                    "# Wiki Index\n\n" + "- [A](" + pagesDir() + "summary-a.md) — kubernetes overview\n" + "- [B]("
                            + pagesDir() + "summary-b.md) — kubernetes intro\n" + "- [C](" + pagesDir()
                            + "summary-c.md) — kubernetes\n");
        }

        @Test
        @DisplayName("returns results with non-zero scores in descending order")
        void scoresPresent() {
            WikiKnowledgeBase wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator(), new IndexFirstSearchStrategy());

            List<WikiSearchResult> results = wiki.searchWithScores(SCOPE,
                    WikiSearchQuery.builder().queryText("kubernetes").build());

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getScore()).isPositive();
            // Already-sorted property
            for (int i = 1; i < results.size(); i++) {
                assertThat(results.get(i).getScore()).isLessThanOrEqualTo(results.get(i - 1).getScore());
            }
        }
    }

    @Nested
    @DisplayName("Default WikiKnowledgeBase fallback")
    class DefaultFallback {

        /**
         * A minimal WikiKnowledgeBase that only overrides search() — verifies the default
         * searchWithScores() in the interface wraps results with score 0.0.
         */
        private static final class SearchOnlyKB implements WikiKnowledgeBase {
            private final WikiPage stubPage = WikiPage.builder().path("/wiki/x.md").title("X").content("# X").build();

            @Override
            public at.aimon.core.knowledge.wiki.IngestResult ingest(WikiScope scope,
                    at.aimon.core.knowledge.wiki.WikiSource source,
                    at.aimon.core.knowledge.wiki.IngestOptions options) {
                return at.aimon.core.knowledge.wiki.IngestResult.builder().build();
            }

            @Override
            public List<WikiPage> search(WikiScope scope, WikiSearchQuery query) {
                return List.of(stubPage);
            }

            @Override
            public java.util.Optional<WikiPage> getPage(WikiScope scope, String pagePath) {
                return java.util.Optional.empty();
            }

            @Override
            public at.aimon.core.knowledge.wiki.WikiStatus getStatus(WikiScope scope) {
                return at.aimon.core.knowledge.wiki.WikiStatus.builder()
                        .state(at.aimon.core.knowledge.wiki.WikiStatus.State.READY).build();
            }

            @Override
            public WikiPage fileAnswer(WikiScope scope, at.aimon.core.knowledge.wiki.FiledAnswer answer) {
                return stubPage;
            }

            @Override
            public void close() {
            }
        }

        @Test
        @DisplayName("default searchWithScores wraps existing search() results with score 0.0")
        void defaultWrapsWithZero() {
            WikiKnowledgeBase kb = new SearchOnlyKB();

            List<WikiSearchResult> results = kb.searchWithScores(SCOPE,
                    WikiSearchQuery.builder().queryText("foo").build());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getScore()).isZero();
            assertThat(results.get(0).getPage().getTitle()).isEqualTo("X");
        }
    }
}
