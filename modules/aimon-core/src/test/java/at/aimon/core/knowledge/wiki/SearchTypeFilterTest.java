package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("Search type filter (Query Improvement 1)")
class SearchTypeFilterTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");
    private static final String WIKI_ROOT = "/wiki";

    private DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs;
    private DefaultWikiKnowledgeBase wiki;

    @BeforeEach
    void setUp() {
        wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator generator = new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator();
        wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT), generator);
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
        // One page of every relevant type, all containing the keyword "kubernetes" so the same query reaches
        // them all and the type filter is the only thing differentiating the result sets.
        wikiVfs.addFile(pagesDir() + "summary-doc.md",
                "---\ntitle: Doc Summary\ntype: summary\ntags: [k8s]\n---\n\n# Doc Summary\n\nA kubernetes summary.");
        wikiVfs.addFile(pagesDir() + "entity-pod.md",
                "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod\n\nA kubernetes pod.");
        wikiVfs.addFile(pagesDir() + "concept-scheduling.md",
                "---\ntitle: Scheduling\ntype: concept\ntags: [k8s]\n---\n\n# Scheduling\n\nThe kubernetes scheduling model.");
        wikiVfs.addFile(pagesDir() + "overview-kubernetes.md",
                "---\ntitle: Kubernetes Overview\ntype: overview\ntags: [k8s]\n---\n\n# Kubernetes Overview\n\nA kubernetes overview.");
        wikiVfs.addFile(pagesDir() + "synthesis-infra.md",
                "---\ntitle: Infrastructure Synthesis\ntype: synthesis\ntags: []\n---\n\n# Infrastructure Synthesis\n\nA kubernetes synthesis.");
    }

    @Nested
    @DisplayName("FullScanSearchStrategy")
    class FullScanFilter {

        @BeforeEach
        void useFullScan() {
            wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator(), new FullScanSearchStrategy());
        }

        @Test
        @DisplayName("includeTypes returns only matching types")
        void includeTypes() {
            List<WikiPage> results = wiki.search(SCOPE, WikiSearchQuery.builder().queryText("kubernetes")
                    .includeTypes(EnumSet.of(WikiPageType.ENTITY)).build());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getTitle()).isEqualTo("Pod");
        }

        @Test
        @DisplayName("excludeTypes drops OVERVIEW and SYNTHESIS")
        void excludeTypes() {
            List<WikiPage> results = wiki.search(SCOPE, WikiSearchQuery.builder().queryText("kubernetes")
                    .excludeTypes(EnumSet.of(WikiPageType.OVERVIEW, WikiPageType.SYNTHESIS)).build());

            assertThat(results).extracting(WikiPage::getTitle).containsExactlyInAnyOrder("Doc Summary", "Pod",
                    "Scheduling");
        }

        @Test
        @DisplayName("no filter returns all matching pages")
        void noFilterReturnsAll() {
            List<WikiPage> results = wiki.search(SCOPE, WikiSearchQuery.builder().queryText("kubernetes").build());

            assertThat(results).hasSize(5);
        }
    }

    @Nested
    @DisplayName("IndexFirstSearchStrategy")
    class IndexFirstFilter {

        @BeforeEach
        void useIndexFirst() {
            // Seed an index.md with all five pages so the index-first path activates.
            wikiVfs.addFile(
                    WIKI_ROOT + "/" + SCOPE.getAgentName() + "/" + SCOPE.getContextId() + "/" + SCOPE.getWikiName()
                            + "/index.md",
                    "# Wiki Index\n\n" + "- [Doc Summary](" + pagesDir() + "summary-doc.md) — kubernetes {k8s}\n"
                            + "- [Pod](" + pagesDir() + "entity-pod.md) — kubernetes {k8s}\n" + "- [Scheduling]("
                            + pagesDir() + "concept-scheduling.md) — kubernetes {k8s}\n" + "- [Kubernetes Overview]("
                            + pagesDir() + "overview-kubernetes.md) — kubernetes {k8s}\n"
                            + "- [Infrastructure Synthesis](" + pagesDir() + "synthesis-infra.md) — kubernetes\n");
            wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator(), new IndexFirstSearchStrategy());
        }

        @Test
        @DisplayName("includeTypes returns only matching types")
        void includeTypes() {
            List<WikiPage> results = wiki.search(SCOPE, WikiSearchQuery.builder().queryText("kubernetes")
                    .includeTypes(EnumSet.of(WikiPageType.CONCEPT)).build());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getTitle()).isEqualTo("Scheduling");
        }

        @Test
        @DisplayName("excludeTypes drops OVERVIEW and SYNTHESIS at index level")
        void excludeTypes() {
            List<WikiPage> results = wiki.search(SCOPE, WikiSearchQuery.builder().queryText("kubernetes")
                    .excludeTypes(EnumSet.of(WikiPageType.OVERVIEW, WikiPageType.SYNTHESIS)).build());

            assertThat(results).extracting(WikiPage::getTitle).doesNotContain("Kubernetes Overview",
                    "Infrastructure Synthesis");
        }

        @Test
        @DisplayName("includeTypes={ENTITY,CONCEPT} returns both")
        void includeTypesMultiple() {
            List<WikiPage> results = wiki.search(SCOPE, WikiSearchQuery.builder().queryText("kubernetes")
                    .includeTypes(EnumSet.of(WikiPageType.ENTITY, WikiPageType.CONCEPT)).build());

            assertThat(results).extracting(WikiPage::getTitle).containsExactlyInAnyOrder("Pod", "Scheduling");
        }
    }
}
