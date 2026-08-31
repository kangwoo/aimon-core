package at.aimon.core.knowledges.opensearch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.knowledge.DocumentChunk;
import at.aimon.core.knowledge.DocumentChunker;
import at.aimon.core.knowledge.IndexOptions;
import at.aimon.core.knowledge.IndexResult;
import at.aimon.core.knowledge.IndexStatus;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeSource;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;
import at.aimon.core.knowledge.embedding.EmbeddingClient;
import at.aimon.core.knowledge.embedding.EmbeddingResult;

/**
 * {@link KnowledgeStore} implementation backed by OpenSearch.
 *
 * <p>
 * Supports three search modes:
 * <ul>
 * <li>{@link SearchMode#KEYWORD} — BM25 text search, fast, no embedding cost. Best for structured documents.
 * <li>{@link SearchMode#VECTOR} — kNN vector similarity search, semantic matching. Requires {@link EmbeddingClient}.
 * <li>{@link SearchMode#HYBRID} — combined BM25 + kNN. Recommended for general-purpose use. Requires
 * {@link EmbeddingClient}.
 * </ul>
 *
 * <p>
 * Multi-tenant isolation is achieved via {@link KnowledgeScope}. Each indexed document is tagged with agent name and
 * context ID. Searches are automatically filtered by scope. This store is stateless with respect to scope — the same
 * instance can serve multiple agents and contexts.
 *
 * <p>
 * Thread-safety: {@link #search(KnowledgeScope, SearchQuery)} is safe for concurrent calls.
 *
 * <p>
 * The {@link OpenSearchClient} lifecycle is managed externally. This store does not close the client.
 *
 * @see OpenSearchConfig
 * @see KnowledgeScope
 * @see KnowledgeStore
 */
public class OpenSearchKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchKnowledgeStore.class);

    /** Maximum number of document chunks per OpenSearch bulk request. */
    static final int BULK_BATCH_SIZE = 100;

    /** Maximum number of texts per embedding API call to avoid provider rate/token limits. */
    static final int EMBEDDING_BATCH_SIZE = 50;

    /** Maximum file size in bytes to load into memory (10 MB). Files exceeding this are skipped. */
    static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

    private final OpenSearchClient client;
    private final OpenSearchConfig config;
    private final DocumentChunker chunker;
    private final EmbeddingClient embeddingClient; // nullable for KEYWORD mode
    private final OpenSearchIndexManager indexManager;

    private final AtomicReference<IndexStatus> currentStatus = new AtomicReference<>(
            IndexStatus.builder().state(IndexStatus.State.EMPTY).build());

    /**
     * Creates an OpenSearch knowledge store for keyword-only search.
     *
     * @param client
     *            the OpenSearch client (must not be null)
     * @param config
     *            the configuration (must not be null; searchMode must be KEYWORD)
     * @param chunker
     *            the document chunker (must not be null)
     * @throws IllegalArgumentException
     *             if config requires embedding but no embedding client is provided
     */
    public OpenSearchKnowledgeStore(OpenSearchClient client, OpenSearchConfig config, DocumentChunker chunker) {
        this(client, config, chunker, null);
    }

    /**
     * Creates an OpenSearch knowledge store with optional embedding support.
     *
     * @param client
     *            the OpenSearch client (must not be null)
     * @param config
     *            the configuration (must not be null)
     * @param chunker
     *            the document chunker (must not be null)
     * @param embeddingClient
     *            the embedding client (required for VECTOR and HYBRID modes; nullable for KEYWORD mode)
     * @throws IllegalArgumentException
     *             if config requires embedding but embeddingClient is null
     */
    public OpenSearchKnowledgeStore(OpenSearchClient client, OpenSearchConfig config, DocumentChunker chunker,
            EmbeddingClient embeddingClient) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");

        if (config.requiresEmbedding() && embeddingClient == null) {
            throw new IllegalArgumentException("EmbeddingClient is required for search mode " + config.getSearchMode());
        }
        this.embeddingClient = embeddingClient;

        this.indexManager = new OpenSearchIndexManager(client, config);
    }

    @Override
    public IndexResult index(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(options, "options must not be null");

        final String directory = source.getDirectory();
        final long startTime = System.currentTimeMillis();
        currentStatus.set(IndexStatus.builder().state(IndexStatus.State.INDEXING).indexedDirectory(directory).build());

        try {
            indexManager.createIndexIfNotExists();
            return doIndex(source, options, toScopeFilter(scope), startTime);
        } catch (IOException e) {
            log.error("Failed to index directory '{}': {}", directory, e.getMessage(), e);
            currentStatus.set(IndexStatus.builder().state(IndexStatus.State.ERROR).indexedDirectory(directory).build());
            return IndexResult.builder().durationMs(System.currentTimeMillis() - startTime)
                    .errors(List.of("Indexing failed: " + e.getMessage())).build();
        }
    }

    @Override
    public IndexResult reindex(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(options, "options must not be null");

        try {
            indexManager.deleteIndexIfExists();
        } catch (IOException e) {
            log.error("Failed to delete index before reindexing: {}", e.getMessage(), e);
            return IndexResult.builder().errors(List.of("Failed to delete existing index: " + e.getMessage())).build();
        }

        return index(scope, source, options);
    }

    @Override
    public List<SearchResult> search(KnowledgeScope scope, SearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

        try {
            final OpenSearchSearchStrategy strategy = createSearchStrategy(toScopeFilter(scope));
            return strategy.search(query);
        } catch (IOException e) {
            log.error("Search failed for query '{}': {}", query.getQueryText(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public IndexStatus getStatus() {
        return currentStatus.get();
    }

    /**
     * Releases resources held by this knowledge store. Idempotent.
     *
     * <p>
     * Note: the {@link OpenSearchClient} lifecycle is managed externally by the caller who injected it via the
     * constructor. This method does not close the OpenSearch client.
     */
    @Override
    public void close() {
        log.debug("OpenSearchKnowledgeStore closed");
    }

    private OpenSearchSearchStrategy createSearchStrategy(ScopeFilter scopeFilter) {
        switch (config.getSearchMode()) {
            case VECTOR :
                return new VectorSearchStrategy(client, config.getIndexName(), embeddingClient, scopeFilter);
            case HYBRID :
                return new HybridSearchStrategy(client, config, embeddingClient, scopeFilter);
            default :
                return new KeywordSearchStrategy(client, config.getIndexName(), scopeFilter);
        }
    }

    private static ScopeFilter toScopeFilter(KnowledgeScope scope) {
        return new ScopeFilter(scope.getAgentName(), scope.getContextId());
    }

    private IndexResult doIndex(KnowledgeSource source, IndexOptions options, ScopeFilter scopeFilter, long startTime)
            throws IOException {
        final VirtualFileSystem fileSystem = source.getFileSystem();
        final String directory = source.getDirectory();
        final List<String> files = collectFiles(fileSystem, directory, options);
        final List<String> errors = new ArrayList<>();

        int indexedDocCount = 0;
        int indexedChunkCount = 0;
        int skippedDocCount = 0;

        final List<DocumentChunk> batchChunks = new ArrayList<>();

        for (String filePath : files) {
            if (indexedDocCount >= options.getMaxDocuments()) {
                skippedDocCount++;
                continue;
            }

            try {
                if (isOversizedFile(fileSystem, filePath)) {
                    log.warn("Skipping oversized file (> {} bytes): {}", MAX_FILE_SIZE_BYTES, filePath);
                    skippedDocCount++;
                    continue;
                }

                final String content = readFileContent(fileSystem, filePath);
                if (content.isEmpty()) {
                    skippedDocCount++;
                    continue;
                }

                final List<DocumentChunk> chunks = chunker.chunk(filePath, content);
                if (chunks.isEmpty()) {
                    skippedDocCount++;
                    continue;
                }

                batchChunks.addAll(chunks);
                indexedDocCount++;
                indexedChunkCount += chunks.size();

                if (batchChunks.size() >= BULK_BATCH_SIZE) {
                    flushBatch(batchChunks, scopeFilter, errors);
                    batchChunks.clear();
                }

            } catch (Exception e) {
                log.warn("Failed to index file '{}': {}", filePath, e.getMessage());
                errors.add(filePath + ": " + e.getMessage());
                skippedDocCount++;
            }
        }

        if (!batchChunks.isEmpty()) {
            flushBatch(batchChunks, scopeFilter, errors);
        }

        final long durationMs = System.currentTimeMillis() - startTime;
        currentStatus.set(IndexStatus.builder().state(IndexStatus.State.READY).documentCount(indexedDocCount)
                .chunkCount(indexedChunkCount).lastIndexedAt(Instant.now()).indexedDirectory(directory).build());

        log.info("Indexed {} documents ({} chunks) from '{}' in {}ms", indexedDocCount, indexedChunkCount, directory,
                durationMs);

        return IndexResult.builder().indexedDocumentCount(indexedDocCount).indexedChunkCount(indexedChunkCount)
                .skippedDocumentCount(skippedDocCount).durationMs(durationMs).errors(errors).build();
    }

    private void flushBatch(List<DocumentChunk> chunks, ScopeFilter scopeFilter, List<String> errors)
            throws IOException {
        final List<float[]> embeddings = generateEmbeddings(chunks);

        final BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

        for (int i = 0; i < chunks.size(); i++) {
            final DocumentChunk chunk = chunks.get(i);
            final float[] embedding = (embeddings != null) ? embeddings.get(i) : null;
            final Map<String, Object> doc = OpenSearchDocumentMapper.toDocument(chunk, embedding, scopeFilter);

            bulkBuilder.operations(op -> op.index(idx -> idx.index(config.getIndexName()).document(doc)));
        }

        final BulkResponse bulkResponse = client.bulk(bulkBuilder.build());

        if (bulkResponse.errors()) {
            for (BulkResponseItem item : bulkResponse.items()) {
                if (item.error() != null) {
                    errors.add("Bulk index error: " + item.error().reason());
                }
            }
        }
    }

    private List<float[]> generateEmbeddings(List<DocumentChunk> chunks) {
        if (embeddingClient == null || !config.requiresEmbedding()) {
            return null;
        }

        final List<String> texts = chunks.stream().map(DocumentChunk::getContent).collect(Collectors.toList());
        final List<float[]> allEmbeddings = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i += EMBEDDING_BATCH_SIZE) {
            final int end = Math.min(i + EMBEDDING_BATCH_SIZE, texts.size());
            final List<String> subBatch = texts.subList(i, end);
            final List<EmbeddingResult> results = embeddingClient.embedBatch(subBatch);
            for (EmbeddingResult result : results) {
                allEmbeddings.add(result.getVector());
            }
        }

        return allEmbeddings;
    }

    private static boolean isOversizedFile(VirtualFileSystem fileSystem, String filePath) {
        try {
            return fileSystem.getMetadata(filePath).getSize() > MAX_FILE_SIZE_BYTES;
        } catch (Exception e) {
            log.debug("Could not check file size for '{}': {}", filePath, e.getMessage());
            return false;
        }
    }

    private static List<String> collectFiles(VirtualFileSystem fileSystem, String directory, IndexOptions options) {
        final List<String> allFiles = new ArrayList<>();

        try {
            collectFilesRecursive(fileSystem, directory, options, allFiles);
        } catch (Exception e) {
            log.warn("Failed to list directory '{}': {}", directory, e.getMessage());
        }

        return allFiles;
    }

    private static void collectFilesRecursive(VirtualFileSystem fileSystem, String directory, IndexOptions options,
            List<String> result) {
        final List<String> entries;
        try {
            entries = fileSystem.list(directory);
        } catch (Exception e) {
            log.warn("Failed to list directory '{}': {}", directory, e.getMessage());
            return;
        }

        for (String entry : entries) {
            final String fullPath = directory.endsWith("/") ? directory + entry : directory + "/" + entry;

            if (fileSystem.isDirectory(fullPath)) {
                if (options.isRecursive()) {
                    collectFilesRecursive(fileSystem, fullPath, options, result);
                }
            } else if (matchesFilePatterns(entry, options.getFilePatterns())) {
                result.add(fullPath);
            }
        }
    }

    static boolean matchesFilePatterns(String fileName, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        for (String pattern : patterns) {
            if (matchGlob(pattern, fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches a simple glob pattern against a file name without regex conversion. Supports {@code *} (any sequence of
     * characters) and {@code ?} (single character). This avoids ReDoS vulnerabilities from regex-based glob matching.
     */
    static boolean matchGlob(String pattern, String text) {
        int pi = 0;
        int ti = 0;
        int starPi = -1;
        int starTi = -1;

        while (ti < text.length()) {
            if (pi < pattern.length() && (pattern.charAt(pi) == '?' || pattern.charAt(pi) == text.charAt(ti))) {
                pi++;
                ti++;
            } else if (pi < pattern.length() && pattern.charAt(pi) == '*') {
                starPi = pi;
                starTi = ti;
                pi++;
            } else if (starPi >= 0) {
                pi = starPi + 1;
                starTi++;
                ti = starTi;
            } else {
                return false;
            }
        }

        while (pi < pattern.length() && pattern.charAt(pi) == '*') {
            pi++;
        }
        return pi == pattern.length();
    }

    private static String readFileContent(VirtualFileSystem fileSystem, String filePath) throws IOException {
        try (InputStream is = fileSystem.read(filePath);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
