package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("DefaultWikiKnowledgeBase Tests")
class DefaultWikiKnowledgeBaseTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");
    private static final String WIKI_ROOT = "/wiki";

    private StubFileSystem wikiVfs;
    private StubFileSystem sourceVfs;
    private StubWikiPageGenerator pageGenerator;
    private DefaultWikiKnowledgeBase wiki;

    @BeforeEach
    void setUp() {
        wikiVfs = new StubFileSystem();
        sourceVfs = new StubFileSystem();
        pageGenerator = new StubWikiPageGenerator();
        wiki = new DefaultWikiKnowledgeBase(defaultLocator(wikiVfs, WIKI_ROOT), pageGenerator);
    }

    private WikiSource source(String directory) {
        return new WikiSource(sourceVfs, directory);
    }

    private String scopeDir() {
        return WIKI_ROOT + "/" + SCOPE.getAgentName() + "/" + SCOPE.getContextId() + "/" + SCOPE.getWikiName() + "/";
    }

    /** Inline locator using the {@code {root}/{agent}/{ctx}/{wiki}} layout against a fixed VFS. */
    private static WikiStorageLocator defaultLocator(VirtualFileSystem fs, String root) {
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

    /** Inline locator using the compact {@code {root}/{ctx}/{wiki}} layout against a fixed VFS. */
    private static WikiStorageLocator contextScopedLocator(VirtualFileSystem fs, String root) {
        return new WikiStorageLocator() {
            @Override
            public VirtualFileSystem fileSystemFor(WikiScope scope) {
                return fs;
            }

            @Override
            public String directoryFor(WikiScope scope) {
                return root + "/" + scope.getContextId() + "/" + scope.getWikiName();
            }
        };
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Null locator should throw NPE")
        void nullLocatorThrowsNpe() {
            assertThatThrownBy(() -> new DefaultWikiKnowledgeBase(null, pageGenerator))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null pageGenerator should throw NPE")
        void nullPageGeneratorThrowsNpe() {
            assertThatThrownBy(() -> new DefaultWikiKnowledgeBase(defaultLocator(wikiVfs, WIKI_ROOT), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Valid construction succeeds")
        void validConstructionSucceeds() {
            assertThatCode(() -> new DefaultWikiKnowledgeBase(defaultLocator(wikiVfs, WIKI_ROOT), pageGenerator))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Custom contextScoped locator is used for storage paths")
        void customLayoutDrivesStorage() {
            DefaultWikiKnowledgeBase customWiki = new DefaultWikiKnowledgeBase(contextScopedLocator(wikiVfs, "/custom"),
                    pageGenerator);
            sourceVfs.addFile("/raw/sample.md", "# Sample\nContent");

            customWiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            // contextScoped drops agentName: /custom/{ctx}/{wiki}/pages/summary-sample.md
            assertThat(wikiVfs.exists("/custom/ctx-1/runbook/pages/summary-sample.md")).isTrue();
            assertThat(wikiVfs.exists("/custom/ctx-1/runbook/index.md")).isTrue();
            assertThat(wikiVfs.exists("/custom/ctx-1/runbook/log.md")).isTrue();
        }

        @Test
        @DisplayName("IllegalStateException from locator propagates from every public method")
        void locatorIseIsNotSwallowed() {
            WikiStorageLocator failingLocator = new WikiStorageLocator() {
                @Override
                public VirtualFileSystem fileSystemFor(WikiScope scope) {
                    throw new IllegalStateException("no context registered for " + scope);
                }

                @Override
                public String directoryFor(WikiScope scope) {
                    return "/wiki/" + scope.getWikiName();
                }
            };
            DefaultWikiKnowledgeBase failingWiki = new DefaultWikiKnowledgeBase(failingLocator, pageGenerator);

            // Every public method must propagate the ISE rather than silently degrade. Lifecycle errors are
            // configuration bugs and must be loud, not swallowed by the soft-fail catch.
            assertThatThrownBy(() -> failingWiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("no context registered");
            assertThatThrownBy(() -> failingWiki.search(SCOPE, WikiSearchQuery.builder().queryText("foo").build()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> failingWiki.getStatus(SCOPE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> failingWiki.lint(SCOPE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> failingWiki.getLog(SCOPE, 10)).isInstanceOf(IllegalStateException.class);

            // getPage: a path inside the scope reaches fileSystemFor and must throw; an out-of-scope path returns
            // empty WITHOUT touching the locator's VFS lookup (cheap early-exit on path mismatch).
            assertThatThrownBy(() -> failingWiki.getPage(SCOPE, "/wiki/runbook/pages/foo.md"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(failingWiki.getPage(SCOPE, "/elsewhere/foo.md")).isEmpty();
            // A crafted path that starts with the scope dir but contains a '..' segment must be rejected by the scope
            // guard BEFORE any VFS access — it returns empty rather than reaching (and throwing from) the locator.
            assertThat(failingWiki.getPage(SCOPE, "/wiki/runbook/pages/../../secret.md")).isEmpty();
        }

        @Test
        @DisplayName("Per-scope VFS routing via custom locator")
        void perScopeVfsRouting() {
            StubFileSystem vfsA = new StubFileSystem();
            StubFileSystem vfsB = new StubFileSystem();
            WikiScope scopeA = new WikiScope("agent-a", "ctx", "wiki");
            WikiScope scopeB = new WikiScope("agent-b", "ctx", "wiki");

            WikiStorageLocator routingLocator = new WikiStorageLocator() {
                @Override
                public VirtualFileSystem fileSystemFor(WikiScope scope) {
                    return scope.getAgentName().equals("agent-a") ? vfsA : vfsB;
                }

                @Override
                public String directoryFor(WikiScope scope) {
                    return "/wiki/" + scope.getWikiName();
                }
            };

            DefaultWikiKnowledgeBase routedWiki = new DefaultWikiKnowledgeBase(routingLocator, pageGenerator);
            sourceVfs.addFile("/raw/doc.md", "# Doc\nContent.");

            routedWiki.ingest(scopeA, source("/raw"), IngestOptions.defaults());

            assertThat(vfsA.exists("/wiki/wiki/pages/summary-doc.md")).isTrue();
            assertThat(vfsB.exists("/wiki/wiki/pages/summary-doc.md")).isFalse();

            routedWiki.ingest(scopeB, source("/raw"), IngestOptions.defaults());

            assertThat(vfsB.exists("/wiki/wiki/pages/summary-doc.md")).isTrue();
        }
    }

    @Nested
    @DisplayName("Ingest")
    class Ingest {

        @Test
        @DisplayName("Ingest single .md file creates wiki page via generator, updates index, appends to log")
        void ingestSingleMdFileCreatesPageAndLog() {
            sourceVfs.addFile("/raw/kubernetes.md", "# Kubernetes Basics\nA container orchestration platform.");

            final IngestResult result = wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            assertThat(result.getIngestedCount()).isEqualTo(1);
            assertThat(result.getCreatedPageCount()).isEqualTo(1);
            assertThat(result.getUpdatedPageCount()).isZero();
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);

            // generator was called for page + index
            assertThat(pageGenerator.getPageCallCount()).isEqualTo(1);
            assertThat(pageGenerator.getIndexCallCount()).isEqualTo(1);

            assertThat(wikiVfs.exists(scopeDir() + "index.md")).isTrue();
            assertThat(wikiVfs.exists(scopeDir() + "log.md")).isTrue();
        }

        @Test
        @DisplayName("Ingest with overwrite=false skips existing pages")
        void ingestOverwriteFalseSkipsExisting() {
            sourceVfs.addFile("/raw/guide.md", "# Guide\nOriginal content.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            final IngestResult result = wiki.ingest(SCOPE, source("/raw"),
                    IngestOptions.builder().overwrite(false).build());

            assertThat(result.getSkippedCount()).isGreaterThan(0);
            assertThat(result.getUpdatedPageCount()).isZero();
        }

        @Test
        @DisplayName("Ingest with overwrite=true updates existing pages")
        void ingestOverwriteTrueUpdatesExisting() {
            sourceVfs.addFile("/raw/guide.md", "# Guide\nOriginal content.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            sourceVfs.clearFiles();
            sourceVfs.addFile("/raw/guide.md", "# Guide\nUpdated content.");

            final IngestResult result = wiki.ingest(SCOPE, source("/raw"),
                    IngestOptions.builder().overwrite(true).build());

            assertThat(result.getIngestedCount()).isEqualTo(1);
            assertThat(result.getUpdatedPageCount()).isEqualTo(1);
            assertThat(result.getCreatedPageCount()).isZero();
        }

        @Test
        @DisplayName("Ingest with file pattern filtering only processes matching files")
        void ingestFilePatternFiltering() {
            sourceVfs.addFile("/raw/doc.md", "# Markdown doc\nContent.");
            sourceVfs.addFile("/raw/script.sh", "#!/bin/bash\necho hello");

            final IngestResult result = wiki.ingest(SCOPE, source("/raw"),
                    IngestOptions.builder().filePatterns(List.of("*.md")).build());

            assertThat(result.getIngestedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Ingest empty directory returns 0 ingested")
        void ingestEmptyDirectoryReturnsZero() {
            final IngestResult result = wiki.ingest(SCOPE, source("/empty"), IngestOptions.defaults());

            assertThat(result.getIngestedCount()).isZero();
            assertThat(result.getErrors()).isEmpty();
            assertThat(pageGenerator.getPageCallCount()).isZero();
        }

        @Test
        @DisplayName("Existing page names are collected once and accumulated during ingest")
        void existingPageNamesAccumulated() {
            sourceVfs.addFile("/raw/doc1.md", "# Doc One\nContent.");
            sourceVfs.addFile("/raw/doc2.md", "# Doc Two\nContent.");

            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            // Second call should have received the name of the first ingested page
            final List<String> secondCallPageNames = pageGenerator.getLastExistingPageNames();
            assertThat(secondCallPageNames).isNotEmpty();
        }

        @Test
        @DisplayName("Log entries include both per-page PAGE_CREATED and per-source SOURCE_INGESTED summary")
        void ingestWritesBothPageAndSourceLogEntries() {
            sourceVfs.addFile("/raw/doc.md", "# Doc\nContent.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            final String logContent = wikiVfs.getFileContent(scopeDir() + "log.md");
            final long pageCreatedCount = logContent.lines().filter(l -> l.contains("PAGE_CREATED")).count();
            final long sourceIngestedCount = logContent.lines().filter(l -> l.contains("SOURCE_INGESTED")).count();

            // Per-page entry — "which pages did this source touch?" answered by PAGE_CREATED entries.
            assertThat(pageCreatedCount).isEqualTo(1);
            // Per-source summary — one SOURCE_INGESTED per successful source. Matches the doc example at
            // docs/references/llm-wiki.md: `## [date] ingest | Article Title`.
            assertThat(sourceIngestedCount).isEqualTo(1);
            assertThat(logContent).contains("/raw/doc.md").contains("created 1");
        }

        @Test
        @DisplayName("Skipped source (empty file) does not write a SOURCE_INGESTED entry")
        void skippedSourceDoesNotLog() {
            sourceVfs.addFile("/raw/empty.md", "");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            final String logContent = wikiVfs.getFileContent(scopeDir() + "log.md");
            if (logContent != null) {
                assertThat(logContent.lines().filter(l -> l.contains("SOURCE_INGESTED")).count()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("Search")
    class Search {

        @BeforeEach
        void ingestDocuments() {
            // StubWikiPageGenerator returns content with the original source content,
            // so search keyword matching works against the generated page content.
            sourceVfs.addFile("/raw/kubernetes.md", "# Kubernetes Basics\n"
                    + "A container orchestration platform.\nPods are the smallest deployable units.");
            sourceVfs.addFile("/raw/docker.md",
                    "# Docker Overview\n" + "Docker is a containerization tool.\nUse docker run to start containers.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());
        }

        @Test
        @DisplayName("Search after ingesting finds matching pages")
        void searchFindsMatchingPages() {
            final List<WikiPage> results = wiki.search(SCOPE,
                    WikiSearchQuery.builder().queryText("kubernetes").build());

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getTitle()).isEqualTo("Kubernetes Basics");
        }

        @Test
        @DisplayName("Search with no matches returns empty list")
        void searchWithNoMatchesReturnsEmpty() {
            final List<WikiPage> results = wiki.search(SCOPE,
                    WikiSearchQuery.builder().queryText("blockchain cryptocurrency").build());

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Search respects maxResults limit")
        void searchRespectsMaxResults() {
            final List<WikiPage> results = wiki.search(SCOPE,
                    WikiSearchQuery.builder().queryText("container").maxResults(1).build());

            assertThat(results).hasSizeLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Search filters by tags")
        void searchFiltersByTags() {
            final List<WikiPage> results = wiki.search(SCOPE,
                    WikiSearchQuery.builder().queryText("kubernetes").tags(List.of("nonexistent-tag")).build());

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Search with no pages returns empty list")
        void searchWithNoPages() {
            final WikiScope emptyScope = new WikiScope("empty-agent", "ctx-1", "no-wiki");

            final List<WikiPage> results = wiki.search(emptyScope,
                    WikiSearchQuery.builder().queryText("kubernetes").build());

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("GetPage")
    class GetPage {

        @Test
        @DisplayName("Get existing page returns content")
        void getExistingPageReturnsContent() {
            sourceVfs.addFile("/raw/guide.md", "# Getting Started\nFollow these steps.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            final List<String> pages = wikiVfs.listRecursive(scopeDir() + "pages/");
            assertThat(pages).isNotEmpty();

            final String pagePath = pages.get(0);
            final Optional<WikiPage> page = wiki.getPage(SCOPE, pagePath);

            assertThat(page).isPresent();
            assertThat(page.get().getPath()).isEqualTo(pagePath);
            assertThat(page.get().getContent()).isNotEmpty();
        }

        @Test
        @DisplayName("Get non-existent page returns empty")
        void getNonExistentPageReturnsEmpty() {
            final Optional<WikiPage> page = wiki.getPage(SCOPE, "/wiki/no-such-page.md");

            assertThat(page).isEmpty();
        }

        @Test
        @DisplayName("Page parsing extracts title from # heading")
        void pageParsingExtractsTitleFromHeading() {
            sourceVfs.addFile("/raw/runbook.md", "# CrashLoopBackOff Runbook\nDiagnosis steps follow.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            final List<String> pages = wikiVfs.listRecursive(scopeDir() + "pages/");
            assertThat(pages).isNotEmpty();

            final Optional<WikiPage> page = wiki.getPage(SCOPE, pages.get(0));
            assertThat(page).isPresent();
            assertThat(page.get().getTitle()).isEqualTo("CrashLoopBackOff Runbook");
        }
    }

    @Nested
    @DisplayName("GetStatus")
    class GetStatus {

        @Test
        @DisplayName("Status of empty wiki returns EMPTY state with 0 pages")
        void statusOfEmptyWikiIsEmpty() {
            final WikiStatus status = wiki.getStatus(new WikiScope("new-agent", "ctx-1", "new-wiki"));

            assertThat(status.getState()).isEqualTo(WikiStatus.State.EMPTY);
            assertThat(status.getPageCount()).isZero();
        }

        @Test
        @DisplayName("Status after ingest returns READY state with page count")
        void statusAfterIngestIsReady() {
            sourceVfs.addFile("/raw/doc.md", "# Document\nContent here.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            final WikiStatus status = wiki.getStatus(SCOPE);

            assertThat(status.getState()).isEqualTo(WikiStatus.State.READY);
            assertThat(status.getPageCount()).isEqualTo(1);
            assertThat(status.getSourceCount()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Lint")
    class Lint {

        @Test
        @DisplayName("Lint of empty wiki returns healthy report with 0 pages")
        void lintEmptyWikiIsHealthy() {
            final LintReport report = wiki.lint(new WikiScope("clean-agent", "ctx-1", "clean-wiki"));

            assertThat(report.isHealthy()).isTrue();
            assertThat(report.getCheckedPageCount()).isZero();
            assertThat(report.getCheckedAt()).isNotNull();
        }

        @Test
        @DisplayName("Lint with orphan pages reports INFO issues")
        void lintWithOrphanPagesReportsIssues() {
            sourceVfs.addFile("/raw/page1.md", "# Page One\nNo links to other pages.");
            sourceVfs.addFile("/raw/page2.md", "# Page Two\nAlso no links.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            final LintReport report = wiki.lint(SCOPE);

            assertThat(report.getCheckedPageCount()).isEqualTo(2);
            assertThat(report.countBySeverity(LintReport.Severity.INFO)).isGreaterThan(0);
        }

        @Test
        @DisplayName("Lint with empty pages reports WARNING issues")
        void lintWithEmptyPagesReportsWarning() {
            final String emptyPagePath = scopeDir() + "pages/empty-page.md";
            wikiVfs.addFile(emptyPagePath, "");

            final LintReport report = wiki.lint(SCOPE);

            assertThat(report.countBySeverity(LintReport.Severity.WARNING)).isGreaterThan(0);
            final boolean hasEmptyPageIssue = report.getIssues().stream()
                    .anyMatch(i -> i.getMessage().contains("empty"));
            assertThat(hasEmptyPageIssue).isTrue();
        }
    }

    @Nested
    @DisplayName("GetLog")
    class GetLog {

        @Test
        @DisplayName("Log of empty wiki returns empty entries")
        void logOfEmptyWikiIsEmpty() {
            final WikiLog wikiLog = wiki.getLog(new WikiScope("fresh-agent", "ctx-1", "fresh-wiki"), 10);

            assertThat(wikiLog.getEntries()).isEmpty();
            assertThat(wikiLog.getTotalEntryCount()).isZero();
        }

        @Test
        @DisplayName("Log after operations contains entries")
        void logAfterOperationsContainsEntries() {
            sourceVfs.addFile("/raw/guide.md", "# Guide\nContent.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            final WikiLog wikiLog = wiki.getLog(SCOPE, 100);

            assertThat(wikiLog.getEntries()).isNotEmpty();
        }

        @Test
        @DisplayName("Log respects limit parameter")
        void logRespectsLimit() {
            sourceVfs.addFile("/raw/doc1.md", "# Doc One\nContent.");
            sourceVfs.addFile("/raw/doc2.md", "# Doc Two\nContent.");
            wiki.ingest(SCOPE, source("/raw"), IngestOptions.defaults());

            final WikiLog wikiLog = wiki.getLog(SCOPE, 1);

            assertThat(wikiLog.getEntries()).hasSizeLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Invalid limit (0) throws IAE")
        void invalidLimitThrowsIae() {
            assertThatThrownBy(() -> wiki.getLog(SCOPE, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Close")
    class Close {

        @Test
        @DisplayName("Close is idempotent")
        void closeIsIdempotent() {
            assertThatCode(() -> {
                wiki.close();
                wiki.close();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Close does not throw")
        void closeDoesNotThrow() {
            assertThatCode(() -> wiki.close()).doesNotThrowAnyException();
        }
    }

    // -------------------------------------------------------------------------
    // Stub WikiPageGenerator for testing DefaultWikiKnowledgeBase in isolation
    // -------------------------------------------------------------------------

    static class StubWikiPageGenerator implements WikiPageGenerator {

        private final AtomicInteger pageCallCount = new AtomicInteger(0);
        private final AtomicInteger indexCallCount = new AtomicInteger(0);
        private volatile List<String> lastExistingPageNames = List.of();

        int getPageCallCount() {
            return pageCallCount.get();
        }

        int getIndexCallCount() {
            return indexCallCount.get();
        }

        List<String> getLastExistingPageNames() {
            return lastExistingPageNames;
        }

        @Override
        public String generatePageContent(WikiScope scope, String sourceFilePath, String sourceContent,
                List<String> existingPageNames) {
            pageCallCount.incrementAndGet();
            lastExistingPageNames = List.copyOf(existingPageNames);

            // Produce deterministic content that preserves source text for search tests
            final String title = extractTitle(sourceContent, sourceFilePath);
            return "---\ntitle: " + title + "\ntags: []\nsource: " + sourceFilePath + "\n---\n\n# " + title + "\n\n"
                    + sourceContent;
        }

        @Override
        public String generateIndexContent(WikiScope scope, String scopeLabel, List<PageInfo> pages) {
            indexCallCount.incrementAndGet();
            final StringBuilder sb = new StringBuilder();
            sb.append("# Wiki Index\n\n");
            sb.append("Total pages: ").append(pages.size()).append("\n\n");
            for (PageInfo page : pages) {
                sb.append("- [").append(page.getTitle()).append("](").append(page.getPath()).append(")\n");
            }
            return sb.toString();
        }

        private static String extractTitle(String content, String path) {
            for (String line : content.split("\n")) {
                if (line.startsWith("# ")) {
                    return line.substring(2).trim();
                }
            }
            final String fileName = path.substring(path.lastIndexOf('/') + 1);
            final int dot = fileName.lastIndexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        }
    }

    // -------------------------------------------------------------------------
    // Minimal in-memory VFS stub for testing
    // -------------------------------------------------------------------------

    static class StubFileSystem implements VirtualFileSystem {

        // ConcurrentHashMap so the concurrency tests (DefaultWikiKnowledgeBaseConcurrencyTest) can race on
        // this stub without ConcurrentModificationException. The regular tests only use a single thread so
        // the extra synchronization overhead is irrelevant for them.
        private final Map<String, String> files = new java.util.concurrent.ConcurrentHashMap<>();

        void addFile(String path, String content) {
            files.put(path, content);
        }

        void clearFiles() {
            files.clear();
        }

        String getFileContent(String path) {
            return files.get(path);
        }

        @Override
        public void write(String path, InputStream content, long contentLength) {
            try {
                final String text = new String(content.readAllBytes(), StandardCharsets.UTF_8);
                files.put(path, text);
            } catch (Exception e) {
                throw new RuntimeException("StubFileSystem write failed", e);
            }
        }

        @Override
        public InputStream read(String path) {
            final String content = files.get(path);
            if (content == null) {
                throw new at.aimon.core.filesystem.exception.FileNotFoundException("Not found: " + path);
            }
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }

        @Override
        public boolean exists(String path) {
            return files.containsKey(path) || isDirectory(path);
        }

        @Override
        public boolean isDirectory(String path) {
            final String prefix = path.endsWith("/") ? path : path + "/";
            return files.keySet().stream().anyMatch(p -> p.startsWith(prefix));
        }

        @Override
        public FileMetadata getMetadata(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> list(String directory) {
            final String prefix = directory.endsWith("/") ? directory : directory + "/";
            final List<String> result = new ArrayList<>();
            // Snapshot via toArray to avoid CME under concurrent writes. ConcurrentHashMap's weakly-
            // consistent iterator would also work but a copy gives the test deterministic reads.
            for (final String path : files.keySet().toArray(new String[0])) {
                if (path.startsWith(prefix)) {
                    result.add(path);
                }
            }
            return result;
        }

        @Override
        public List<String> listRecursive(String directory) {
            return list(directory);
        }

        @Override
        public void copy(String sourcePath, String destinationPath, boolean overwrite) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(String sourcePath, String destinationPath, boolean overwrite) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream openOutputStream(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream openInputStream(String path) {
            return read(path);
        }

        @Override
        public String getWorkingDirectory() {
            return "/";
        }

        @Override
        public void initialize() {
            // no-op
        }

        @Override
        public BackendStatus getStatus() {
            return null;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
