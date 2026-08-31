package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("DefaultWikiKnowledgeBase Synthesize Tests")
class DefaultWikiKnowledgeBaseSynthesizeTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");
    private static final String WIKI_ROOT = "/wiki";

    private DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs;
    private DefaultWikiKnowledgeBaseTest.StubFileSystem sourceVfs;
    private DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator pageGenerator;
    private RecordingSynthesisStrategy strategy;
    private DefaultWikiKnowledgeBase wiki;

    @BeforeEach
    void setUp() {
        wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        sourceVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        pageGenerator = new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator();
        strategy = new RecordingSynthesisStrategy();
        wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT), pageGenerator,
                new at.aimon.core.knowledge.wiki.IndexFirstSearchStrategy(), null, strategy);
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

    @Nested
    @DisplayName("synthesize() — strategy gating")
    class StrategyGating {

        @Test
        @DisplayName("synthesize throws UnsupportedOperationException when no strategy is wired")
        void noStrategyWired() {
            DefaultWikiKnowledgeBase wikiNoStrategy = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    pageGenerator, new at.aimon.core.knowledge.wiki.IndexFirstSearchStrategy(), null, null);

            assertThatThrownBy(() -> wikiNoStrategy.synthesize(SCOPE, SynthesizeOptions.defaults()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("synthesize() — page writing")
    class PageWriting {

        @Test
        @DisplayName("strategy returns pages → counters and files reflect them")
        void writesAndCounts() {
            strategy.queue(List.of(buildSynthesizedPage(WikiPageType.OVERVIEW, "kubernetes", "Kubernetes Overview"),
                    buildSynthesizedPage(WikiPageType.SYNTHESIS, "infra-patterns", "Infra Patterns")));
            strategy.lastCallCount = 2;

            SynthesizeResult result = wiki.synthesize(SCOPE, SynthesizeOptions.defaults());

            assertThat(result.getCreatedPageCount()).isEqualTo(2);
            assertThat(result.getUpdatedPageCount()).isZero();
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getLlmCallCount()).isEqualTo(2);
            assertThat(wikiVfs.getFileContent(pagesDir() + "overview-kubernetes.md")).contains("# Kubernetes Overview");
            assertThat(wikiVfs.getFileContent(pagesDir() + "synthesis-infra-patterns.md")).contains("# Infra Patterns");
        }

        @Test
        @DisplayName("existing page is skipped when overwrite=false")
        void existingPageSkippedWithoutOverwrite() {
            wikiVfs.addFile(pagesDir() + "overview-kubernetes.md", "existing content");
            strategy.queue(List.of(buildSynthesizedPage(WikiPageType.OVERVIEW, "kubernetes", "Kubernetes Overview")));

            SynthesizeResult result = wiki.synthesize(SCOPE, SynthesizeOptions.defaults());

            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getCreatedPageCount()).isZero();
            assertThat(result.getUpdatedPageCount()).isZero();
            assertThat(wikiVfs.getFileContent(pagesDir() + "overview-kubernetes.md")).isEqualTo("existing content");
        }

        @Test
        @DisplayName("existing page is overwritten when overwrite=true")
        void existingPageOverwrittenWithOverwrite() {
            wikiVfs.addFile(pagesDir() + "overview-kubernetes.md", "existing content");
            strategy.queue(List.of(buildSynthesizedPage(WikiPageType.OVERVIEW, "kubernetes", "Kubernetes Overview")));

            SynthesizeResult result = wiki.synthesize(SCOPE, SynthesizeOptions.builder().overwrite(true).build());

            assertThat(result.getUpdatedPageCount()).isEqualTo(1);
            assertThat(result.getCreatedPageCount()).isZero();
            assertThat(wikiVfs.getFileContent(pagesDir() + "overview-kubernetes.md")).contains("# Kubernetes Overview");
        }
    }

    @Nested
    @DisplayName("synthesize() — input filtering")
    class InputFiltering {

        @Test
        @DisplayName("OVERVIEW and SYNTHESIS pages are filtered out of strategy input")
        void filtersOverviewAndSynthesisFromInput() {
            wikiVfs.addFile(pagesDir() + "entity-pod.md", "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod");
            wikiVfs.addFile(pagesDir() + "overview-kubernetes.md",
                    "---\ntitle: Kubernetes\ntype: overview\ntags: [k8s]\n---\n\n# Kubernetes");
            wikiVfs.addFile(pagesDir() + "synthesis-infra.md",
                    "---\ntitle: Infra\ntype: synthesis\ntags: []\n---\n\n# Infra");

            strategy.queue(Collections.emptyList());
            wiki.synthesize(SCOPE, SynthesizeOptions.defaults());

            assertThat(strategy.lastInputPages).hasSize(1);
            assertThat(strategy.lastInputPages.get(0).getType()).isEqualTo(WikiPageType.ENTITY);
        }
    }

    @Nested
    @DisplayName("synthesize() — strategy failure")
    class StrategyFailure {

        @Test
        @DisplayName("strategy throwing produces an error and zero counts")
        void strategyThrows() {
            strategy.throwOnNext = new RuntimeException("strategy blew up");

            SynthesizeResult result = wiki.synthesize(SCOPE, SynthesizeOptions.defaults());

            assertThat(result.getCreatedPageCount()).isZero();
            assertThat(result.getUpdatedPageCount()).isZero();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0)).contains("strategy blew up");
        }
    }

    @Nested
    @DisplayName("ingest() autoSynthesize hook")
    class AutoSynthesizeHook {

        @Test
        @DisplayName("autoSynthesize=true triggers strategy at end of ingest")
        void autoSynthesizeTriggers() {
            sourceVfs.addFile("/raw/doc.md", "# Doc\nContent.");
            strategy.queue(List.of(buildSynthesizedPage(WikiPageType.OVERVIEW, "topic", "Topic")));

            IngestResult result = wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"),
                    IngestOptions.builder().autoSynthesize(true).build());

            assertThat(result.getIngestedCount()).isEqualTo(1);
            assertThat(strategy.callCount.get()).isEqualTo(1);
            assertThat(wikiVfs.getFileContent(pagesDir() + "overview-topic.md")).contains("# Topic");
        }

        @Test
        @DisplayName("autoSynthesize=false does not trigger strategy")
        void autoSynthesizeOff() {
            sourceVfs.addFile("/raw/doc.md", "# Doc\nContent.");

            wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"), IngestOptions.defaults());

            assertThat(strategy.callCount.get()).isZero();
        }

        @Test
        @DisplayName("autoSynthesize errors do not poison the IngestResult")
        void autoSynthesizeErrorsAreSwallowed() {
            sourceVfs.addFile("/raw/doc.md", "# Doc\nContent.");
            strategy.throwOnNext = new RuntimeException("synth blew up");

            IngestResult result = wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"),
                    IngestOptions.builder().autoSynthesize(true).build());

            // Ingest itself succeeded.
            assertThat(result.getIngestedCount()).isEqualTo(1);
            assertThat(result.getErrors()).isEmpty();
        }
    }

    private static GeneratedPage buildSynthesizedPage(WikiPageType type, String slug, String title) {
        return GeneratedPage.builder().type(type).slug(slug).title(title)
                .content("---\ntitle: " + title + "\ntype: " + type.getToken() + "\ntags: []\n---\n\n# " + title)
                .strategy(GeneratedPage.UpdateStrategy.CREATE).build();
    }

    /**
     * Recording strategy that captures invocations and returns pre-queued page lists. Lets the storage tests
     * assert on counters, file writes, and input filtering without involving an LLM.
     */
    private static final class RecordingSynthesisStrategy implements SynthesisStrategy {

        final AtomicInteger callCount = new AtomicInteger(0);
        final java.util.Deque<List<GeneratedPage>> queued = new java.util.ArrayDeque<>();
        List<WikiPage> lastInputPages = new ArrayList<>();
        int lastCallCount;
        RuntimeException throwOnNext;

        void queue(List<GeneratedPage> pages) {
            queued.add(pages);
        }

        @Override
        public List<GeneratedPage> synthesize(WikiScope scope, List<WikiPage> sourcePages, SynthesizeOptions options) {
            callCount.incrementAndGet();
            lastInputPages = new ArrayList<>(sourcePages);
            if (throwOnNext != null) {
                final RuntimeException e = throwOnNext;
                throwOnNext = null;
                throw e;
            }
            if (queued.isEmpty()) {
                return Collections.emptyList();
            }
            return queued.pollFirst();
        }

        @Override
        public int getLastCallCount() {
            return lastCallCount;
        }
    }
}
