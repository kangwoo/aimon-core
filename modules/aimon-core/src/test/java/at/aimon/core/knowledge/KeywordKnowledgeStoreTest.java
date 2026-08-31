package at.aimon.core.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("KeywordKnowledgeStore Tests")
class KeywordKnowledgeStoreTest {

    private static final KnowledgeScope SCOPE = new KnowledgeScope("test-agent", "ctx-001");

    private StubFileSystem vfs;
    private KeywordKnowledgeStore store;

    @BeforeEach
    void setUp() {
        vfs = new StubFileSystem();
        store = new KeywordKnowledgeStore(new SimpleDocumentChunker());
    }

    private KnowledgeSource source(String directory) {
        return new KnowledgeSource(vfs, directory);
    }

    @Nested
    @DisplayName("Indexing")
    class Indexing {

        @Test
        @DisplayName("Should index documents and return statistics")
        void testIndexDocuments() {
            vfs.addFile("/knowledge/runbook.md", """
                    # CrashLoopBackOff Troubleshooting
                    Check pod logs with kubectl logs.
                    Review container restart count.
                    """);
            vfs.addFile("/knowledge/faq.md", """
                    # FAQ
                    ## What is CrashLoopBackOff?
                    A pod restart state in Kubernetes.
                    """);

            final IndexResult result = store.index(SCOPE, source("/knowledge"), IndexOptions.defaults());

            assertThat(result.getIndexedDocumentCount()).isEqualTo(2);
            assertThat(result.getIndexedChunkCount()).isGreaterThan(0);
            assertThat(result.getSkippedDocumentCount()).isZero();
            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Should skip files not matching patterns")
        void testFilePatternFiltering() {
            vfs.addFile("/knowledge/doc.md", "# Markdown doc");
            vfs.addFile("/knowledge/script.sh", "#!/bin/bash");

            final IndexResult result = store.index(SCOPE, source("/knowledge"),
                    IndexOptions.builder().filePatterns(List.of("*.md")).build());

            assertThat(result.getIndexedDocumentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should respect maxDocuments limit")
        void testMaxDocumentsLimit() {
            vfs.addFile("/knowledge/doc1.md", "# Document One\nContent one.");
            vfs.addFile("/knowledge/doc2.md", "# Document Two\nContent two.");
            vfs.addFile("/knowledge/doc3.md", "# Document Three\nContent three.");

            final IndexResult result = store.index(SCOPE, source("/knowledge"),
                    IndexOptions.builder().maxDocuments(2).build());

            assertThat(result.getIndexedDocumentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle empty directory")
        void testEmptyDirectory() {
            final IndexResult result = store.index(SCOPE, source("/empty"), IndexOptions.defaults());

            assertThat(result.getIndexedDocumentCount()).isZero();
            assertThat(result.getIndexedChunkCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Search")
    class Search {

        @BeforeEach
        void indexDocuments() {
            vfs.addFile("/knowledge/runbook.md", """
                    # CrashLoopBackOff Troubleshooting

                    ## Diagnosis Steps
                    Use kubectl describe pod to check events.
                    Use kubectl logs to check container logs.

                    ## Common Causes
                    OOM killed, configuration error, dependency unavailable.
                    """);
            vfs.addFile("/knowledge/faq.md", """
                    # FAQ

                    ## What is Kubernetes?
                    An open-source container orchestration platform.

                    ## What is a Pod?
                    The smallest deployable unit in Kubernetes.
                    """);
            store.index(SCOPE, source("/knowledge"), IndexOptions.defaults());
        }

        @Test
        @DisplayName("Should find relevant documents by keyword")
        void testKeywordSearch() {
            final List<SearchResult> results = store.search(SCOPE,
                    SearchQuery.builder().queryText("CrashLoopBackOff").build());

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getDocumentPath()).contains("runbook.md");
            assertThat(results.get(0).getScore()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("Should return results sorted by score descending")
        void testResultOrdering() {
            final List<SearchResult> results = store.search(SCOPE,
                    SearchQuery.builder().queryText("kubectl logs pod").build());

            assertThat(results).isNotEmpty();
            for (int i = 1; i < results.size(); i++) {
                assertThat(results.get(i).getScore()).isLessThanOrEqualTo(results.get(i - 1).getScore());
            }
        }

        @Test
        @DisplayName("Should respect maxResults")
        void testMaxResults() {
            final List<SearchResult> results = store.search(SCOPE,
                    SearchQuery.builder().queryText("kubernetes").maxResults(1).build());

            assertThat(results).hasSizeLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should respect minScore filter")
        void testMinScore() {
            final List<SearchResult> results = store.search(SCOPE,
                    SearchQuery.builder().queryText("CrashLoopBackOff").minScore(0.99).build());

            for (SearchResult result : results) {
                assertThat(result.getScore()).isGreaterThanOrEqualTo(0.99);
            }
        }

        @Test
        @DisplayName("Should return empty list for no match")
        void testNoMatch() {
            final List<SearchResult> results = store.search(SCOPE,
                    SearchQuery.builder().queryText("blockchain cryptocurrency").build());

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Should filter by file pattern")
        void testFilePatternFilter() {
            final List<SearchResult> results = store.search(SCOPE,
                    SearchQuery.builder().queryText("kubernetes").filePatterns(List.of("faq*")).build());

            for (SearchResult result : results) {
                assertThat(result.getDocumentPath()).contains("faq");
            }
        }

        @Test
        @DisplayName("Should normalize scores to [0.0, 1.0]")
        void testScoreNormalization() {
            final List<SearchResult> results = store.search(SCOPE,
                    SearchQuery.builder().queryText("kubectl logs").build());

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getScore()).isEqualTo(1.0);
            for (SearchResult result : results) {
                assertThat(result.getScore()).isBetween(0.0, 1.0);
            }
        }
    }

    @Nested
    @DisplayName("Status")
    class Status {

        @Test
        @DisplayName("Should return EMPTY before indexing")
        void testEmptyStatus() {
            final IndexStatus status = store.getStatus();

            assertThat(status.getState()).isEqualTo(IndexStatus.State.EMPTY);
            assertThat(status.getDocumentCount()).isZero();
        }

        @Test
        @DisplayName("Should return READY after indexing")
        void testReadyStatus() {
            vfs.addFile("/knowledge/doc.md", "# Title\nContent here.");
            store.index(SCOPE, source("/knowledge"), IndexOptions.defaults());

            final IndexStatus status = store.getStatus();

            assertThat(status.getState()).isEqualTo(IndexStatus.State.READY);
            assertThat(status.getDocumentCount()).isEqualTo(1);
            assertThat(status.getChunkCount()).isGreaterThan(0);
            assertThat(status.getLastIndexedAt()).isNotNull();
            assertThat(status.getIndexedDirectory()).isEqualTo("/knowledge");
        }
    }

    @Nested
    @DisplayName("Reindex")
    class Reindex {

        @Test
        @DisplayName("Should replace index on reindex")
        void testReindex() {
            vfs.addFile("/knowledge/doc.md", "# Old Content\nOld text.");
            store.index(SCOPE, source("/knowledge"), IndexOptions.defaults());

            vfs.clearFiles();
            vfs.addFile("/knowledge/doc.md", "# New Content\nNew text about deployment.");
            store.reindex(SCOPE, source("/knowledge"), IndexOptions.defaults());

            final List<SearchResult> results = store.search(SCOPE,
                    SearchQuery.builder().queryText("deployment").build());

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getChunkContent()).contains("deployment");
        }
    }

    @Nested
    @DisplayName("Close")
    class Close {

        @Test
        @DisplayName("Should clear index on close")
        void testClose() {
            vfs.addFile("/knowledge/doc.md", "# Title\nContent.");
            store.index(SCOPE, source("/knowledge"), IndexOptions.defaults());

            store.close();

            final IndexStatus status = store.getStatus();
            assertThat(status.getState()).isEqualTo(IndexStatus.State.EMPTY);
        }

        @Test
        @DisplayName("Close should be idempotent")
        void testCloseIdempotent() {
            store.close();
            store.close();

            assertThat(store.getStatus().getState()).isEqualTo(IndexStatus.State.EMPTY);
        }
    }

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorValidation {

        @Test
        @DisplayName("Should reject null chunker")
        void testNullChunker() {
            assertThatThrownBy(() -> new KeywordKnowledgeStore(null)).isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * Minimal in-memory VFS stub for testing.
     */
    static class StubFileSystem implements VirtualFileSystem {
        private final Map<String, String> files = new HashMap<>();

        void addFile(String path, String content) {
            files.put(path, content);
        }

        void clearFiles() {
            files.clear();
        }

        @Override
        public void write(String path, InputStream content, long contentLength) {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
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
            for (String path : files.keySet()) {
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
