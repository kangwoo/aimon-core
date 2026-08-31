package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Exercises the concurrency claim in {@link DefaultWikiKnowledgeBase} — the class Javadoc promises that
 * concurrent calls on the same scope are serialized through the per-scope write lock. These tests race
 * multiple threads against each other on the same {@link WikiScope} and verify that (a) no writer loses
 * its page, (b) the log and index files don't get torn, and (c) the final state is internally consistent.
 *
 * <p>
 * Uses a synchronized in-memory stub VFS that faithfully reproduces the happens-before a real multi-writer
 * VFS would provide. This is a regression test for the "ReentrantLock per scope" design — if that lock is
 * ever removed or weakened, these tests will flake or fail hard.
 */
@DisplayName("DefaultWikiKnowledgeBase Concurrency Tests")
class DefaultWikiKnowledgeBaseConcurrencyTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");
    private static final String WIKI_ROOT = "/wiki";

    private DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs;
    private DefaultWikiKnowledgeBase wiki;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator generator = new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator();
        wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT), generator);
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
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

    private String scopeDir() {
        return WIKI_ROOT + "/" + SCOPE.getAgentName() + "/" + SCOPE.getContextId() + "/" + SCOPE.getWikiName() + "/";
    }

    @Test
    @DisplayName("concurrent ingests on the same scope write all sources without data loss")
    void concurrentIngestsDoNotLoseData() throws Exception {
        // Arrange 8 DISTINCT sources (different base names per batch) in two source trees. Using disjoint
        // file names matters: the target page file names for each ingest are derived from the source file
        // name, so if both batches used "doc0.md ... doc3.md" the second ingest would CREATE-skip every
        // page that the first ingest had already written, defeating the point of the race. With alpha*/
        // beta* names, both ingests write to non-overlapping targets and we can assert that BOTH made it
        // through the per-scope lock cleanly.
        final DefaultWikiKnowledgeBaseTest.StubFileSystem sourceVfs1 = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        final DefaultWikiKnowledgeBaseTest.StubFileSystem sourceVfs2 = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        for (int i = 0; i < 4; i++) {
            sourceVfs1.addFile("/raw1/alpha" + i + ".md", "# Alpha" + i + "\nBody " + i);
            sourceVfs2.addFile("/raw2/beta" + i + ".md", "# Beta" + i + "\nBody " + i);
        }

        final CountDownLatch start = new CountDownLatch(1);
        final List<java.util.concurrent.Future<IngestResult>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            start.await();
            return wiki.ingest(SCOPE, new WikiSource(sourceVfs1, "/raw1"), IngestOptions.defaults());
        }));
        futures.add(executor.submit(() -> {
            start.await();
            return wiki.ingest(SCOPE, new WikiSource(sourceVfs2, "/raw2"), IngestOptions.defaults());
        }));

        start.countDown();

        final IngestResult r1 = futures.get(0).get(10, TimeUnit.SECONDS);
        final IngestResult r2 = futures.get(1).get(10, TimeUnit.SECONDS);

        // Both ingests fully succeed — no source skipped, no error, no lost page.
        assertThat(r1.getIngestedCount()).isEqualTo(4);
        assertThat(r2.getIngestedCount()).isEqualTo(4);
        assertThat(r1.getErrors()).isEmpty();
        assertThat(r2.getErrors()).isEmpty();

        // All 8 summary pages exist on disk — every source from both batches made it through.
        for (int i = 0; i < 4; i++) {
            assertThat(wikiVfs.getFileContent(scopeDir() + "pages/summary-alpha" + i + ".md"))
                    .as("alpha" + i + " written").isNotNull();
            assertThat(wikiVfs.getFileContent(scopeDir() + "pages/summary-beta" + i + ".md"))
                    .as("beta" + i + " written").isNotNull();
        }
        // Exactly 8 SOURCE_INGESTED entries in the log — one per successful source across both ingests.
        // This is the real concurrency guarantee under test: without the per-scope lock, concurrent
        // appendLogEntry calls could tear the log.md file and produce fewer entries.
        final String logContent = wikiVfs.getFileContent(scopeDir() + "log.md");
        final long sourceIngestedCount = logContent.lines().filter(l -> l.contains("SOURCE_INGESTED")).count();
        assertThat(sourceIngestedCount).isEqualTo(8L);
    }

    @Test
    @DisplayName("concurrent ingest + lint on the same scope do not deadlock")
    void ingestAndLintDoNotDeadlock() throws Exception {
        // Seed a few pages first so lint has something to check.
        final DefaultWikiKnowledgeBaseTest.StubFileSystem sourceVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        sourceVfs.addFile("/raw/doc0.md", "# Doc0\nBody");
        wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"), IngestOptions.defaults());

        // Race an ingest and a lint. lint() does not hold the write lock, so the ingest must complete
        // without waiting on lint, and lint must complete without waiting on ingest. Deadlock would show
        // up as the 5-second timeout on get() below.
        sourceVfs.addFile("/raw/doc1.md", "# Doc1\nBody");

        final CountDownLatch start = new CountDownLatch(1);
        final java.util.concurrent.Future<IngestResult> ingestFuture = executor.submit(() -> {
            start.await();
            return wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"), IngestOptions.defaults());
        });
        final java.util.concurrent.Future<Integer> lintFuture = executor.submit(() -> {
            start.await();
            return wiki.lint(SCOPE).getCheckedPageCount();
        });

        start.countDown();

        final IngestResult r = ingestFuture.get(5, TimeUnit.SECONDS);
        final int linted = lintFuture.get(5, TimeUnit.SECONDS);

        // Both operations completed. Ingest added at least the new page (it may skip doc0.md if it was
        // already there from the seed). Lint checked at least one page — we don't assert an exact count
        // because the race determines whether lint saw 1 or 2 pages.
        assertThat(r.getErrors()).isEmpty();
        assertThat(linted).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("concurrent migrateFrontmatter + ingest serialize through the per-scope lock")
    void migrateAndIngestSerialize() throws Exception {
        // Seed an un-typed page so migration has something to rewrite.
        wikiVfs.addFile(scopeDir() + "pages/summary-seed.md", "---\ntitle: Seed\ntags: []\n---\n\n# Seed");

        final DefaultWikiKnowledgeBaseTest.StubFileSystem sourceVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        sourceVfs.addFile("/raw/newdoc.md", "# NewDoc\nBody");

        final CountDownLatch start = new CountDownLatch(1);
        final java.util.concurrent.Future<?> migrateFuture = executor.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            wiki.migrateFrontmatter(SCOPE);
        });
        final java.util.concurrent.Future<IngestResult> ingestFuture = executor.submit(() -> {
            start.await();
            return wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"), IngestOptions.defaults());
        });

        start.countDown();

        migrateFuture.get(5, TimeUnit.SECONDS);
        final IngestResult r = ingestFuture.get(5, TimeUnit.SECONDS);

        // Migration rewrote the seed page with a type field; ingest added the new page. Both must be on
        // disk at the end, and neither must have produced an error.
        assertThat(r.getErrors()).isEmpty();
        assertThat(wikiVfs.getFileContent(scopeDir() + "pages/summary-seed.md")).contains("type: summary");
        assertThat(wikiVfs.getFileContent(scopeDir() + "pages/summary-newdoc.md")).isNotNull();
    }
}
