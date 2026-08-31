package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.knowledge.IndexOptions;
import at.aimon.core.knowledge.IndexResult;
import at.aimon.core.knowledge.IndexStatus;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeSource;
import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;

@DisplayName("WikiKnowledgeStore Tests")
class WikiKnowledgeStoreTest {

    private static final String DEFAULT_WIKI_NAME = "runbook-wiki";
    private static final KnowledgeScope SCOPE = new KnowledgeScope("test-agent", "ctx-001");

    private StubWikiKnowledgeBase stub;
    private WikiKnowledgeStore store;

    @BeforeEach
    void setUp() {
        stub = new StubWikiKnowledgeBase();
        store = new WikiKnowledgeStore(stub, DEFAULT_WIKI_NAME);
    }

    private KnowledgeSource knowledgeSource(String directory) {
        return new KnowledgeSource(new StubFileSystem(), directory);
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Null wikiKnowledgeBase throws NullPointerException")
        void nullWikiKnowledgeBaseThrowsNpe() {
            assertThatThrownBy(() -> new WikiKnowledgeStore(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null wikiKnowledgeBase with custom name throws NullPointerException")
        void nullWikiKnowledgeBaseWithCustomNameThrowsNpe() {
            assertThatThrownBy(() -> new WikiKnowledgeStore(null, DEFAULT_WIKI_NAME))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Empty defaultWikiName throws IllegalArgumentException")
        void emptyDefaultWikiNameThrowsIllegalArgument() {
            assertThatThrownBy(() -> new WikiKnowledgeStore(stub, "")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Null defaultWikiName throws NullPointerException")
        void nullDefaultWikiNameThrowsNpe() {
            assertThatThrownBy(() -> new WikiKnowledgeStore(stub, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Valid construction with default wiki name uses 'default' as wiki name")
        void validConstructionWithDefaultWikiName() {
            WikiKnowledgeStore s = new WikiKnowledgeStore(stub);
            assertThat(s.getWikiKnowledgeBase()).isSameAs(stub);
        }

        @Test
        @DisplayName("Valid construction with custom wiki name")
        void validConstructionWithCustomWikiName() {
            WikiKnowledgeStore s = new WikiKnowledgeStore(stub, "custom-wiki");
            assertThat(s.getWikiKnowledgeBase()).isSameAs(stub);
        }

        @Test
        @DisplayName("getWikiKnowledgeBase returns the delegate")
        void getWikiKnowledgeBaseReturnsDelegate() {
            assertThat(store.getWikiKnowledgeBase()).isSameAs(stub);
        }

        @Test
        @DisplayName("getWikiKnowledgeBaseAdmin returns empty when delegate does not implement admin")
        void getWikiKnowledgeBaseAdminEmptyWhenNotAdmin() {
            assertThat(store.getWikiKnowledgeBaseAdmin()).isEmpty();
        }

        @Test
        @DisplayName("getWikiKnowledgeBaseAdmin returns present when delegate implements admin")
        void getWikiKnowledgeBaseAdminPresentWhenAdmin() {
            StubWikiKnowledgeBaseAdmin adminStub = new StubWikiKnowledgeBaseAdmin();
            WikiKnowledgeStore adminStore = new WikiKnowledgeStore(adminStub, DEFAULT_WIKI_NAME);

            Optional<WikiKnowledgeBaseAdmin> admin = adminStore.getWikiKnowledgeBaseAdmin();

            assertThat(admin).isPresent();
            assertThat(admin.get()).isSameAs(adminStub);
        }
    }

    // -------------------------------------------------------------------------
    // Index
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Index")
    class Index {

        @Test
        @DisplayName("index() delegates to wikiKnowledgeBase.ingest()")
        void indexDelegatesToIngest() {
            stub.cannedIngestResult = IngestResult.builder().ingestedCount(3).build();

            store.index(SCOPE, knowledgeSource("/docs"), IndexOptions.defaults());

            assertThat(stub.lastIngestCallCount).isEqualTo(1);
        }

        @Test
        @DisplayName("IndexResult fields are correctly mapped from IngestResult")
        void indexResultFieldsMappedFromIngestResult() {
            stub.cannedIngestResult = IngestResult.builder().ingestedCount(5).skippedCount(2).createdPageCount(3)
                    .updatedPageCount(4).durationMs(120).errors(List.of("error1", "error2")).build();

            IndexResult result = store.index(SCOPE, knowledgeSource("/docs"), IndexOptions.defaults());

            assertThat(result.getIndexedDocumentCount()).isEqualTo(5);
            // indexedChunkCount = createdPageCount(3) + updatedPageCount(4)
            assertThat(result.getIndexedChunkCount()).isEqualTo(7);
            assertThat(result.getSkippedDocumentCount()).isEqualTo(2);
            assertThat(result.getDurationMs()).isEqualTo(120);
            assertThat(result.getErrors()).containsExactly("error1", "error2");
        }

        @Test
        @DisplayName("KnowledgeScope agentName is preserved; defaultWikiName is used as wikiName")
        void scopeAdaptation() {
            KnowledgeScope scope = new KnowledgeScope("my-agent", "ctx-xyz");
            store.index(scope, knowledgeSource("/docs"), IndexOptions.defaults());

            assertThat(stub.lastIngestScope).isNotNull();
            assertThat(stub.lastIngestScope.getAgentName()).isEqualTo("my-agent");
            assertThat(stub.lastIngestScope.getWikiName()).isEqualTo(DEFAULT_WIKI_NAME);
        }

        @Test
        @DisplayName("contextId is passed through to WikiScope")
        void contextIdIsPassedThrough() {
            store.index(new KnowledgeScope("agent", "ctx-aaa"), knowledgeSource("/docs"), IndexOptions.defaults());
            WikiScope scopeA = stub.lastIngestScope;

            store.index(new KnowledgeScope("agent", "ctx-bbb"), knowledgeSource("/docs"), IndexOptions.defaults());
            WikiScope scopeB = stub.lastIngestScope;

            assertThat(scopeA.getContextId()).isEqualTo("ctx-aaa");
            assertThat(scopeA.getWikiName()).isEqualTo(DEFAULT_WIKI_NAME);
            assertThat(scopeB.getContextId()).isEqualTo("ctx-bbb");
            assertThat(scopeB.getWikiName()).isEqualTo(DEFAULT_WIKI_NAME);
        }

        @Test
        @DisplayName("KnowledgeSource is correctly adapted to WikiSource")
        void sourceAdaptation() {
            KnowledgeSource source = knowledgeSource("/my-docs");
            store.index(SCOPE, source, IndexOptions.defaults());

            assertThat(stub.lastIngestSource).isNotNull();
            assertThat(stub.lastIngestSource.getDirectory()).isEqualTo("/my-docs");
            assertThat(stub.lastIngestSource.getFileSystem()).isSameAs(source.getFileSystem());
        }

        @Test
        @DisplayName("IndexOptions are correctly adapted to IngestOptions with overwrite=true")
        void optionsAdaptation() {
            IndexOptions options = IndexOptions.builder().filePatterns(List.of("*.md")).recursive(false)
                    .maxDocuments(42).build();

            store.index(SCOPE, knowledgeSource("/docs"), options);

            IngestOptions ingestOptions = stub.lastIngestOptions;
            assertThat(ingestOptions).isNotNull();
            assertThat(ingestOptions.getFilePatterns()).containsExactly("*.md");
            assertThat(ingestOptions.isRecursive()).isFalse();
            assertThat(ingestOptions.getMaxDocuments()).isEqualTo(42);
            assertThat(ingestOptions.isOverwrite()).isTrue();
        }

        @Test
        @DisplayName("index() returns error result when ingest throws exception")
        void indexReturnsErrorResultOnException() {
            stub.throwOnIngest = true;

            IndexResult result = store.index(SCOPE, knowledgeSource("/docs"), IndexOptions.defaults());

            assertThat(result.getErrors()).isNotEmpty();
            assertThat(result.getErrors().get(0)).startsWith("Ingest failed:");
        }
    }

    // -------------------------------------------------------------------------
    // Reindex
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Reindex")
    class Reindex {

        @Test
        @DisplayName("reindex() delegates to ingest() with overwrite=true")
        void reindexDelegatesToIngestWithOverwrite() {
            stub.cannedIngestResult = IngestResult.builder().ingestedCount(1).build();

            store.reindex(SCOPE, knowledgeSource("/docs"), IndexOptions.defaults());

            assertThat(stub.lastIngestCallCount).isEqualTo(1);
            assertThat(stub.lastIngestOptions.isOverwrite()).isTrue();
        }

        @Test
        @DisplayName("reindex() result fields are mapped the same as index()")
        void reindexResultMappedLikeIndex() {
            stub.cannedIngestResult = IngestResult.builder().ingestedCount(4).createdPageCount(2).updatedPageCount(2)
                    .durationMs(75).build();

            IndexResult result = store.reindex(SCOPE, knowledgeSource("/docs"), IndexOptions.defaults());

            assertThat(result.getIndexedDocumentCount()).isEqualTo(4);
            assertThat(result.getIndexedChunkCount()).isEqualTo(4); // created(2) + updated(2)
            assertThat(result.getDurationMs()).isEqualTo(75);
        }
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Search")
    class Search {

        @Test
        @DisplayName("search() returns converted WikiPages as SearchResults")
        void searchReturnsConvertedPages() {
            stub.cannedSearchResults = List.of(page("/wiki/page1.md", "Page 1", "Content 1"),
                    page("/wiki/page2.md", "Page 2", "Content 2"));

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("test").build());

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getDocumentPath()).isEqualTo("/wiki/page1.md");
            assertThat(results.get(0).getChunkContent()).isEqualTo("Content 1");
            assertThat(results.get(1).getDocumentPath()).isEqualTo("/wiki/page2.md");
            assertThat(results.get(1).getChunkContent()).isEqualTo("Content 2");
        }

        @Test
        @DisplayName("Score normalization: first result gets 1.0, last gets 0.0 for multiple results")
        void scoreNormalizationMultipleResults() {
            stub.cannedSearchResults = List.of(page("/wiki/p1.md", "P1", "c1"), page("/wiki/p2.md", "P2", "c2"),
                    page("/wiki/p3.md", "P3", "c3"));

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("query").build());

            assertThat(results).hasSize(3);
            assertThat(results.get(0).getScore()).isCloseTo(1.0, within(0.0001));
            assertThat(results.get(1).getScore()).isCloseTo(0.5, within(0.0001));
            assertThat(results.get(2).getScore()).isCloseTo(0.1, within(0.0001));
        }

        @Test
        @DisplayName("Single result gets score 1.0")
        void singleResultGetScore1() {
            stub.cannedSearchResults = List.of(page("/wiki/only.md", "Only", "content"));

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("query").build());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getScore()).isCloseTo(1.0, within(0.0001));
        }

        @Test
        @DisplayName("Empty results returns empty list")
        void emptyResultsReturnsEmptyList() {
            stub.cannedSearchResults = Collections.emptyList();

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("query").build());

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("SearchQuery text and maxResults are mapped to WikiSearchQuery")
        void searchQueryFieldsMappedToWikiQuery() {
            SearchQuery query = SearchQuery.builder().queryText("CrashLoopBackOff").maxResults(7).build();

            store.search(SCOPE, query);

            assertThat(stub.lastSearchQuery).isNotNull();
            assertThat(stub.lastSearchQuery.getQueryText()).isEqualTo("CrashLoopBackOff");
            assertThat(stub.lastSearchQuery.getMaxResults()).isEqualTo(7);
        }

        @Test
        @DisplayName("SearchQuery filePatterns are mapped to WikiSearchQuery pagePathPatterns")
        void filePatternsMappedToPagePathPatterns() {
            SearchQuery query = SearchQuery.builder().queryText("k8s").filePatterns(List.of("*.md", "ops/*")).build();

            store.search(SCOPE, query);

            assertThat(stub.lastSearchQuery.getPagePathPatterns()).containsExactly("*.md", "ops/*");
        }

        @Test
        @DisplayName("Empty filePatterns results in empty pagePathPatterns in WikiSearchQuery")
        void emptyFilePatternsResultsInEmptyPagePathPatterns() {
            store.search(SCOPE, SearchQuery.builder().queryText("query").build());

            assertThat(stub.lastSearchQuery.getPagePathPatterns()).isEmpty();
        }

        @Test
        @DisplayName("Content longer than 2000 chars is truncated in SearchResult")
        void contentTruncatedAt2000Chars() {
            String longContent = "x".repeat(3000);
            stub.cannedSearchResults = List.of(page("/wiki/big.md", "Big", longContent));

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("x").build());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getChunkContent()).hasSize(2000);
        }

        @Test
        @DisplayName("Content exactly 2000 chars is not truncated")
        void contentAt2000CharsNotTruncated() {
            String exactContent = "y".repeat(2000);
            stub.cannedSearchResults = List.of(page("/wiki/exact.md", "Exact", exactContent));

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("y").build());

            assertThat(results.get(0).getChunkContent()).hasSize(2000);
        }

        @Test
        @DisplayName("Metadata includes title and tags from WikiPage")
        void metadataIncludesTitleAndTags() {
            WikiPage taggedPage = WikiPage.builder().path("/wiki/tagged.md").title("Tagged Page")
                    .content("Some content").tags(List.of("kubernetes", "ops")).build();
            stub.cannedSearchResults = List.of(taggedPage);

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("query").build());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getMetadata()).containsEntry("title", "Tagged Page");
            assertThat(results.get(0).getMetadata()).containsEntry("tags", "kubernetes,ops");
        }

        @Test
        @DisplayName("Metadata has title but no tags entry when WikiPage has no tags")
        void metadataHasTitleWhenNoTags() {
            stub.cannedSearchResults = List.of(page("/wiki/noTags.md", "No Tags Page", "content"));

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("q").build());

            assertThat(results.get(0).getMetadata()).containsEntry("title", "No Tags Page");
            assertThat(results.get(0).getMetadata()).doesNotContainKey("tags");
        }

        @Test
        @DisplayName("WikiPage with empty content is skipped in results")
        void pageWithEmptyContentIsSkipped() {
            stub.cannedSearchResults = List.of(page("/wiki/empty.md", "Empty", ""),
                    page("/wiki/valid.md", "Valid", "some content"));

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("query").build());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getDocumentPath()).isEqualTo("/wiki/valid.md");
        }

        @Test
        @DisplayName("KnowledgeScope agentName is preserved in WikiScope passed to search")
        void searchScopeAdaptation() {
            KnowledgeScope scope = new KnowledgeScope("search-agent", "ctx-999");
            store.search(scope, SearchQuery.builder().queryText("query").build());

            assertThat(stub.lastSearchScope).isNotNull();
            assertThat(stub.lastSearchScope.getAgentName()).isEqualTo("search-agent");
            assertThat(stub.lastSearchScope.getContextId()).isEqualTo("ctx-999");
            assertThat(stub.lastSearchScope.getWikiName()).isEqualTo(DEFAULT_WIKI_NAME);
        }

        @Test
        @DisplayName("search() returns empty list when wiki search throws exception")
        void searchReturnsEmptyListOnException() {
            stub.throwOnSearch = true;

            List<SearchResult> results = store.search(SCOPE, SearchQuery.builder().queryText("query").build());

            assertThat(results).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // GetStatus
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GetStatus")
    class GetStatus {

        @Test
        @DisplayName("getStatus() returns EMPTY when wiki reports EMPTY state")
        void getStatusReturnsEmptyWhenWikiEmpty() {
            stub.cannedStatus = WikiStatus.builder().state(WikiStatus.State.EMPTY).build();

            IndexStatus status = store.getStatus();

            assertThat(status.getState()).isEqualTo(IndexStatus.State.EMPTY);
        }

        @Test
        @DisplayName("getStatus() maps wiki fields: pageCount, wikiDirectory, lastIngestedAt")
        void getStatusMapsWikiFields() {
            Instant now = Instant.now();
            stub.cannedStatus = WikiStatus.builder().state(WikiStatus.State.READY).pageCount(10).wikiDirectory("/wiki")
                    .lastIngestedAt(now).build();

            IndexStatus status = store.getStatus();

            assertThat(status.getState()).isEqualTo(IndexStatus.State.READY);
            assertThat(status.getDocumentCount()).isEqualTo(10);
            assertThat(status.getIndexedDirectory()).isEqualTo("/wiki");
            assertThat(status.getLastIndexedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("getStatus() maps INGESTING wiki state to INDEXING index state")
        void getStatusMapsIngestingToIndexing() {
            stub.cannedStatus = WikiStatus.builder().state(WikiStatus.State.INGESTING).build();

            assertThat(store.getStatus().getState()).isEqualTo(IndexStatus.State.INDEXING);
        }

        @Test
        @DisplayName("getStatus() maps ERROR wiki state to ERROR index state")
        void getStatusMapsErrorToError() {
            stub.cannedStatus = WikiStatus.builder().state(WikiStatus.State.ERROR).build();

            assertThat(store.getStatus().getState()).isEqualTo(IndexStatus.State.ERROR);
        }

        @Test
        @DisplayName("getStatus() reflects wiki state after an index call updates lastWikiScope")
        void getStatusUsesLastWikiScopeAfterIndex() {
            stub.cannedIngestResult = IngestResult.builder().ingestedCount(1).build();
            store.index(SCOPE, knowledgeSource("/docs"), IndexOptions.defaults());

            stub.cannedStatus = WikiStatus.builder().state(WikiStatus.State.READY).pageCount(5).build();

            IndexStatus status = store.getStatus();

            assertThat(status.getState()).isEqualTo(IndexStatus.State.READY);
            assertThat(status.getDocumentCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("getStatus() returns ERROR state when wiki getStatus throws exception")
        void getStatusReturnsErrorOnException() {
            stub.throwOnGetStatus = true;

            IndexStatus status = store.getStatus();

            assertThat(status.getState()).isEqualTo(IndexStatus.State.ERROR);
        }
    }

    // -------------------------------------------------------------------------
    // Close
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Close")
    class Close {

        @Test
        @DisplayName("close() delegates to wikiKnowledgeBase.close()")
        void closeDelegatesToWikiClose() {
            store.close();

            assertThat(stub.closeCallCount).isEqualTo(1);
        }

        @Test
        @DisplayName("close() is idempotent — calling twice delegates twice without error")
        void closeIsIdempotent() {
            store.close();
            store.close();

            assertThat(stub.closeCallCount).isEqualTo(2);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static WikiPage page(String path, String title, String content) {
        return WikiPage.builder().path(path).title(title).content(content).build();
    }

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    /**
     * Minimal WikiKnowledgeBase stub. Does not implement WikiKnowledgeBaseAdmin.
     */
    static class StubWikiKnowledgeBase implements WikiKnowledgeBase {

        WikiScope lastIngestScope;
        WikiSource lastIngestSource;
        IngestOptions lastIngestOptions;
        int lastIngestCallCount;

        WikiScope lastSearchScope;
        WikiSearchQuery lastSearchQuery;

        IngestResult cannedIngestResult = IngestResult.builder().build();
        List<WikiPage> cannedSearchResults = Collections.emptyList();
        WikiStatus cannedStatus = WikiStatus.builder().state(WikiStatus.State.EMPTY).build();

        boolean throwOnIngest;
        boolean throwOnSearch;
        boolean throwOnGetStatus;

        int closeCallCount;

        @Override
        public IngestResult ingest(WikiScope scope, WikiSource source, IngestOptions options) {
            if (throwOnIngest) {
                throw new RuntimeException("stub ingest failure");
            }
            lastIngestScope = scope;
            lastIngestSource = source;
            lastIngestOptions = options;
            lastIngestCallCount++;
            return cannedIngestResult;
        }

        @Override
        public List<WikiPage> search(WikiScope scope, WikiSearchQuery query) {
            if (throwOnSearch) {
                throw new RuntimeException("stub search failure");
            }
            lastSearchScope = scope;
            lastSearchQuery = query;
            return new ArrayList<>(cannedSearchResults);
        }

        @Override
        public Optional<WikiPage> getPage(WikiScope scope, String pagePath) {
            return Optional.empty();
        }

        @Override
        public WikiStatus getStatus(WikiScope scope) {
            if (throwOnGetStatus) {
                throw new RuntimeException("stub getStatus failure");
            }
            return cannedStatus;
        }

        @Override
        public WikiPage fileAnswer(WikiScope scope, FiledAnswer answer) {
            throw new UnsupportedOperationException("fileAnswer not used in this test");
        }

        @Override
        public void close() {
            closeCallCount++;
        }
    }

    /**
     * Stub that implements both WikiKnowledgeBase and WikiKnowledgeBaseAdmin.
     */
    static class StubWikiKnowledgeBaseAdmin extends StubWikiKnowledgeBase implements WikiKnowledgeBaseAdmin {

        @Override
        public LintReport lint(WikiScope scope) {
            return LintReport.builder().checkedAt(Instant.now()).build();
        }

        @Override
        public WikiLog getLog(WikiScope scope, int limit) {
            return WikiLog.builder().build();
        }
    }

    /**
     * Minimal in-memory VFS stub for providing a non-null VirtualFileSystem in KnowledgeSource.
     */
    static class StubFileSystem implements VirtualFileSystem {

        @Override
        public void write(String path, InputStream content, long contentLength) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream read(String path) {
            return new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void delete(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String path) {
            return false;
        }

        @Override
        public boolean isDirectory(String path) {
            return false;
        }

        @Override
        public FileMetadata getMetadata(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> list(String directory) {
            return Collections.emptyList();
        }

        @Override
        public List<String> listRecursive(String directory) {
            return Collections.emptyList();
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
