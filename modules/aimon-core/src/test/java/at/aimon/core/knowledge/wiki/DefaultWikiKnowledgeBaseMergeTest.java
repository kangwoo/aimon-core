package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("DefaultWikiKnowledgeBase MERGE Tests")
class DefaultWikiKnowledgeBaseMergeTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");
    private static final String WIKI_ROOT = "/wiki";

    private DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs;
    private DefaultWikiKnowledgeBaseTest.StubFileSystem sourceVfs;
    private MultiPageStubGenerator pageGenerator;
    private RecordingMerger merger;
    private DefaultWikiKnowledgeBase wiki;

    @BeforeEach
    void setUp() {
        wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        sourceVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        pageGenerator = new MultiPageStubGenerator();
        merger = new RecordingMerger();
        wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT), pageGenerator, defaultSearch(), merger);
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

    private static at.aimon.core.knowledge.wiki.IndexFirstSearchStrategy defaultSearch() {
        return new at.aimon.core.knowledge.wiki.IndexFirstSearchStrategy();
    }

    private String pagesDir() {
        return WIKI_ROOT + "/" + SCOPE.getAgentName() + "/" + SCOPE.getContextId() + "/" + SCOPE.getWikiName()
                + "/pages/";
    }

    @Nested
    @DisplayName("MERGE strategy gating")
    class MergeGating {

        @Test
        @DisplayName("MERGE strategy invokes merger when enableMerge=true and target exists")
        void mergeInvokedWhenEnabledAndExists() {
            // Pre-create the target file so MERGE has something to merge against.
            wikiVfs.addFile(pagesDir() + "entity-pod.md",
                    "---\ntitle: Pod\ntype: entity\ntags: [k8s]\nderived_from: [/raw/initial.md]\n---\n\n# Pod\n\nInitial body.");

            sourceVfs.addFile("/raw/new.md", "Some new content");
            pageGenerator.queueExtractedPages(List.of(buildIncomingMergePage("/raw/new.md")));

            IngestResult result = wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"),
                    IngestOptions.builder().enableMerge(true).build());

            assertThat(merger.callCount.get()).isEqualTo(1);
            assertThat(result.getMergedPageCount()).isEqualTo(1);
            assertThat(result.getUpdatedPageCount()).isZero();
            assertThat(result.getCreatedPageCount()).isZero();

            String written = wikiVfs.getFileContent(pagesDir() + "entity-pod.md");
            assertThat(written).contains("MERGED:Pod");
            assertThat(written).contains("derived_from: [/raw/initial.md, /raw/new.md]");
        }

        @Test
        @DisplayName("MERGE falls through to REPLACE when enableMerge=false")
        void mergeFallsThroughWhenDisabled() {
            wikiVfs.addFile(pagesDir() + "entity-pod.md",
                    "---\ntitle: Pod\ntype: entity\ntags: []\n---\n\n# Pod\n\nOld body.");

            sourceVfs.addFile("/raw/new.md", "Some new content");
            pageGenerator.queueExtractedPages(List.of(buildIncomingMergePage("/raw/new.md")));

            IngestResult result = wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"),
                    IngestOptions.builder().enableMerge(false).build());

            assertThat(merger.callCount.get()).isZero();
            assertThat(result.getMergedPageCount()).isZero();
            // The file existed, so plain REPLACE counts as an update.
            assertThat(result.getUpdatedPageCount()).isEqualTo(1);
            String written = wikiVfs.getFileContent(pagesDir() + "entity-pod.md");
            // The incoming page body was written verbatim — no merger involvement.
            assertThat(written).contains("INCOMING:Pod");
            assertThat(written).doesNotContain("MERGED:");
        }

        @Test
        @DisplayName("MERGE falls through to CREATE when target file does not exist")
        void mergeFallsThroughWhenNoExistingFile() {
            sourceVfs.addFile("/raw/new.md", "Some new content");
            pageGenerator.queueExtractedPages(List.of(buildIncomingMergePage("/raw/new.md")));

            IngestResult result = wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"),
                    IngestOptions.builder().enableMerge(true).build());

            assertThat(merger.callCount.get()).isZero();
            assertThat(result.getMergedPageCount()).isZero();
            assertThat(result.getCreatedPageCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("overwrite=true bypasses MERGE entirely (escalates to REPLACE)")
        void overwriteBypassesMerge() {
            wikiVfs.addFile(pagesDir() + "entity-pod.md",
                    "---\ntitle: Pod\ntype: entity\ntags: []\n---\n\n# Pod\n\nOld body.");

            sourceVfs.addFile("/raw/new.md", "Some new content");
            pageGenerator.queueExtractedPages(List.of(buildIncomingMergePage("/raw/new.md")));

            IngestResult result = wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"),
                    IngestOptions.builder().enableMerge(true).overwrite(true).build());

            assertThat(merger.callCount.get()).isZero();
            assertThat(result.getMergedPageCount()).isZero();
            assertThat(result.getUpdatedPageCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("MERGE strategy without merger wired")
    class NoMergerWired {

        @Test
        @DisplayName("MERGE falls through to REPLACE when no merger is injected")
        void mergeFallsThroughWhenNoMerger() {
            DefaultWikiKnowledgeBase wikiNoMerger = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    pageGenerator, defaultSearch(), null);

            wikiVfs.addFile(pagesDir() + "entity-pod.md",
                    "---\ntitle: Pod\ntype: entity\ntags: []\n---\n\n# Pod\n\nOld body.");

            sourceVfs.addFile("/raw/new.md", "Some new content");
            pageGenerator.queueExtractedPages(List.of(buildIncomingMergePage("/raw/new.md")));

            IngestResult result = wikiNoMerger.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"),
                    IngestOptions.builder().enableMerge(true).build());

            assertThat(merger.callCount.get()).isZero();
            assertThat(result.getMergedPageCount()).isZero();
            assertThat(result.getUpdatedPageCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Merger contract violations")
    class ContractViolations {

        @Test
        @DisplayName("Merger throwing produces an ingest error and skips the source")
        void mergerThrowsRecordedAsError() {
            wikiVfs.addFile(pagesDir() + "entity-pod.md",
                    "---\ntitle: Pod\ntype: entity\ntags: []\n---\n\n# Pod\n\nOld body.");
            sourceVfs.addFile("/raw/new.md", "Some new content");
            pageGenerator.queueExtractedPages(List.of(buildIncomingMergePage("/raw/new.md")));

            merger.throwOnNext = new RuntimeException("merge blew up");

            IngestResult result = wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"),
                    IngestOptions.builder().enableMerge(true).build());

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0)).contains("merge blew up");
            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getMergedPageCount()).isZero();
        }
    }

    private static GeneratedPage buildIncomingMergePage(String sourcePath) {
        return GeneratedPage.builder().type(WikiPageType.ENTITY).slug("pod").title("Pod")
                .content("---\ntitle: Pod\ntype: entity\ntags: []\nderived_from: [" + sourcePath
                        + "]\n---\n\n# INCOMING:Pod\n\nNew body.")
                .derivedFrom(List.of(sourcePath)).strategy(GeneratedPage.UpdateStrategy.MERGE).build();
    }

    /**
     * Stub generator that returns pre-queued {@link GeneratedPage} lists from {@code extractPages}, bypassing the
     * default-method legacy fallback. This lets the MERGE tests control exactly what the storage layer receives
     * for each source.
     */
    private static final class MultiPageStubGenerator implements WikiPageGenerator {

        private final java.util.Deque<List<GeneratedPage>> queued = new java.util.ArrayDeque<>();

        void queueExtractedPages(List<GeneratedPage> pages) {
            queued.add(pages);
        }

        @Override
        public String generatePageContent(WikiScope scope, String sourceFilePath, String sourceContent,
                List<String> existingPageNames) {
            // Not used by these tests but must satisfy the interface.
            return "---\ntitle: stub\n---\n\n# stub";
        }

        @Override
        public List<GeneratedPage> extractPages(WikiScope scope, String sourceFilePath, String sourceContent,
                List<String> existingPageNames) {
            if (queued.isEmpty()) {
                return Collections.singletonList(GeneratedPage.builder().type(WikiPageType.SUMMARY).slug("default")
                        .title("default").content("---\n---\n\n# default").build());
            }
            return queued.pollFirst();
        }

        @Override
        public String generateIndexContent(WikiScope scope, String scopeLabel, List<PageInfo> pages) {
            return "# Wiki Index\n\nTotal: " + pages.size() + "\n";
        }
    }

    /**
     * Recording merger that produces a deterministic merged page so tests can assert on derivedFrom union and
     * counter routing without needing a real LLM.
     */
    private static final class RecordingMerger implements WikiPageMerger {

        final AtomicInteger callCount = new AtomicInteger(0);
        RuntimeException throwOnNext;

        @Override
        public GeneratedPage merge(WikiScope scope, WikiPage existing, GeneratedPage incoming) {
            callCount.incrementAndGet();
            if (throwOnNext != null) {
                final RuntimeException e = throwOnNext;
                throwOnNext = null;
                throw e;
            }
            // Union of derivedFrom in stable order — mirrors the LlmWikiPageMerger semantics.
            final List<String> mergedDerivedFrom = new ArrayList<>();
            for (String s : existing.getDerivedFrom()) {
                if (!mergedDerivedFrom.contains(s)) {
                    mergedDerivedFrom.add(s);
                }
            }
            for (String s : incoming.getDerivedFrom()) {
                if (!mergedDerivedFrom.contains(s)) {
                    mergedDerivedFrom.add(s);
                }
            }
            final StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("title: ").append(existing.getTitle()).append('\n');
            sb.append("type: ").append(incoming.getType().getToken()).append('\n');
            sb.append("tags: []\n");
            sb.append("derived_from: [");
            for (int i = 0; i < mergedDerivedFrom.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(mergedDerivedFrom.get(i));
            }
            sb.append("]\n---\n\n# MERGED:").append(existing.getTitle()).append("\n\nMerged body.\n");

            return GeneratedPage.builder().type(incoming.getType()).slug(incoming.getSlug()).title(existing.getTitle())
                    .content(sb.toString()).derivedFrom(mergedDerivedFrom)
                    .strategy(GeneratedPage.UpdateStrategy.REPLACE).build();
        }
    }
}
