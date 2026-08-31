package at.aimon.core.knowledge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * In-memory keyword-based {@link KnowledgeStore} implementation using TF-IDF scoring.
 *
 * <p>
 * This store builds per-scope inverted indices from documents read via {@link KnowledgeSource}. Search queries are
 * tokenized and matched against the scope's index, with results ranked by TF-IDF scores normalized to [0.0, 1.0].
 *
 * <p>
 * Multi-scope support: each {@link KnowledgeScope} has its own independent inverted index. Indexing for one scope does
 * not affect other scopes. Cross-context search ({@link SearchQuery#isCrossContext()}) merges results from all contexts
 * of the same agent.
 *
 * <p>
 * Thread safety: {@link #search(KnowledgeScope, SearchQuery)} is safe for concurrent calls. Concurrent indexing for
 * different scopes is safe. Concurrent indexing for the same scope should be avoided.
 *
 * @see KnowledgeStore
 */
public final class KeywordKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(KeywordKnowledgeStore.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\s\\p{Punct}]+");

    private final DocumentChunker chunker;

    /** Per-scope index snapshots. Key: "agentName:contextId". */
    private final ConcurrentHashMap<String, IndexSnapshot> scopeIndices = new ConcurrentHashMap<>();

    /**
     * Creates a keyword knowledge store.
     *
     * @param chunker
     *            the document chunker (must not be null)
     * @throws NullPointerException
     *             if chunker is null
     */
    public KeywordKnowledgeStore(DocumentChunker chunker) {
        this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");
    }

    @Override
    public IndexResult index(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(options, "options must not be null");

        return doIndex(scope, source, options);
    }

    @Override
    public IndexResult reindex(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(options, "options must not be null");

        // Remove existing data for this scope before re-indexing
        scopeIndices.remove(scopeKey(scope));
        return doIndex(scope, source, options);
    }

    @Override
    public List<SearchResult> search(KnowledgeScope scope, SearchQuery query) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(query, "query must not be null");

        final List<String> queryTerms = tokenize(query.getQueryText());
        if (queryTerms.isEmpty()) {
            return Collections.emptyList();
        }

        // Collect snapshots to search
        final List<IndexSnapshot> snapshots = collectSnapshots(scope, query.isCrossContext());
        if (snapshots.isEmpty()) {
            return Collections.emptyList();
        }

        // Merge and score across all matching snapshots
        final List<ScoredChunk> allScored = new ArrayList<>();
        for (IndexSnapshot snapshot : snapshots) {
            scoreSnapshot(snapshot, queryTerms, allScored);
        }

        if (allScored.isEmpty()) {
            return Collections.emptyList();
        }

        // Normalize scores across all snapshots
        final double maxScore = allScored.stream().mapToDouble(sc -> sc.score).max().orElse(1.0);
        final List<String> filePatterns = query.getFilePatterns();

        return allScored.stream().map(sc -> new ScoredChunk(sc.chunk, maxScore > 0 ? sc.score / maxScore : 0.0))
                .filter(sc -> sc.score >= query.getMinScore())
                .filter(sc -> matchesFilePatterns(sc.chunk.getDocumentPath(), filePatterns))
                .sorted((a, b) -> Double.compare(b.score, a.score)).limit(query.getMaxResults())
                .map(sc -> SearchResult.builder().documentPath(sc.chunk.getDocumentPath())
                        .chunkContent(sc.chunk.getContent()).score(sc.score).chunkIndex(sc.chunk.getChunkIndex())
                        .metadata(sc.chunk.getMetadata()).build())
                .collect(Collectors.toList());
    }

    @Override
    public IndexStatus getStatus() {
        if (scopeIndices.isEmpty()) {
            return IndexStatus.builder().state(IndexStatus.State.EMPTY).build();
        }
        int totalDocs = 0;
        int totalChunks = 0;
        Instant latestIndexedAt = null;
        String latestDirectory = null;
        for (IndexSnapshot snapshot : scopeIndices.values()) {
            totalDocs += snapshot.documentCount;
            totalChunks += snapshot.chunks.size();
            if (latestIndexedAt == null
                    || (snapshot.indexedAt != null && snapshot.indexedAt.isAfter(latestIndexedAt))) {
                latestIndexedAt = snapshot.indexedAt;
                latestDirectory = snapshot.indexedDirectory;
            }
        }
        return IndexStatus.builder().state(IndexStatus.State.READY).documentCount(totalDocs).chunkCount(totalChunks)
                .lastIndexedAt(latestIndexedAt).indexedDirectory(latestDirectory).build();
    }

    @Override
    public void close() {
        scopeIndices.clear();
    }

    private IndexResult doIndex(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
        final VirtualFileSystem vfs = source.getFileSystem();
        final String directory = source.getDirectory();

        log.debug("Indexing knowledge directory: {} for scope {}", directory, scope);
        final long startTime = System.currentTimeMillis();

        final List<String> files = listFiles(vfs, directory, options);

        final List<DocumentChunk> chunks = new ArrayList<>();
        final Map<String, Set<Integer>> invertedIndex = new HashMap<>();
        final List<Map<String, Integer>> tokenCounts = new ArrayList<>();
        final List<Integer> tokenTotals = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        int indexedDocCount = 0;
        int skippedDocCount = 0;

        for (String filePath : files) {
            if (indexedDocCount >= options.getMaxDocuments()) {
                skippedDocCount = files.size() - indexedDocCount - skippedDocCount;
                break;
            }

            try {
                final String content = readFileContent(vfs, filePath);
                if (content.isEmpty()) {
                    skippedDocCount++;
                    continue;
                }

                final List<DocumentChunk> fileChunks = chunker.chunk(filePath, content);
                final int maxChunkSize = options.getMaxChunkSize();
                for (DocumentChunk chunk : fileChunks) {
                    if (chunk.getContent().length() > maxChunkSize) {
                        continue;
                    }
                    final int globalIdx = chunks.size();
                    chunks.add(chunk);

                    final List<String> tokens = tokenize(chunk.getContent());
                    final Map<String, Integer> counts = new HashMap<>();
                    for (String token : tokens) {
                        counts.merge(token, 1, Integer::sum);
                        invertedIndex.computeIfAbsent(token, k -> new HashSet<>()).add(globalIdx);
                    }
                    tokenCounts.add(Collections.unmodifiableMap(counts));
                    tokenTotals.add(tokens.size());
                }

                indexedDocCount++;

            } catch (Exception e) {
                log.warn("Failed to index file: {}", filePath, e);
                errors.add(filePath + ": " + e.getMessage());
                skippedDocCount++;
            }
        }

        final long durationMs = System.currentTimeMillis() - startTime;

        // Store scope-specific snapshot
        final IndexSnapshot snapshot = IndexSnapshot.builder().chunks(Collections.unmodifiableList(chunks))
                .invertedIndex(Collections.unmodifiableMap(invertedIndex))
                .chunkTokenCounts(Collections.unmodifiableList(tokenCounts))
                .chunkTokenTotals(Collections.unmodifiableList(tokenTotals)).documentCount(indexedDocCount)
                .indexedAt(Instant.now()).indexedDirectory(directory).agentName(scope.getAgentName()).build();

        scopeIndices.put(scopeKey(scope), snapshot);

        log.debug("Indexing complete for scope {}: {} docs, {} chunks in {}ms", scope, indexedDocCount, chunks.size(),
                durationMs);

        return IndexResult.builder().indexedDocumentCount(indexedDocCount).indexedChunkCount(chunks.size())
                .skippedDocumentCount(skippedDocCount).durationMs(durationMs).errors(errors).build();
    }

    /**
     * Collects snapshots matching the given scope. For cross-context search, returns all snapshots of the same agent.
     */
    private List<IndexSnapshot> collectSnapshots(KnowledgeScope scope, boolean crossContext) {
        if (!crossContext) {
            final IndexSnapshot snapshot = scopeIndices.get(scopeKey(scope));
            return snapshot != null ? List.of(snapshot) : Collections.emptyList();
        }

        // Cross-context: collect all snapshots for the same agent
        final List<IndexSnapshot> result = new ArrayList<>();
        for (Map.Entry<String, IndexSnapshot> entry : scopeIndices.entrySet()) {
            if (entry.getValue().agentName.equals(scope.getAgentName())) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    /**
     * Scores chunks in a single snapshot against the query terms using TF-IDF.
     */
    private static void scoreSnapshot(IndexSnapshot snapshot, List<String> queryTerms, List<ScoredChunk> output) {
        if (snapshot.chunks.isEmpty()) {
            return;
        }

        final Map<Integer, Double> chunkScores = new HashMap<>();
        final int totalChunks = snapshot.chunks.size();

        for (String term : queryTerms) {
            final Set<Integer> matchingChunks = snapshot.invertedIndex.getOrDefault(term, Collections.emptySet());
            if (matchingChunks.isEmpty()) {
                continue;
            }

            final double idf = Math.log((double) totalChunks / matchingChunks.size());

            for (int chunkIdx : matchingChunks) {
                final double tf = computeTf(term, snapshot.chunkTokenCounts.get(chunkIdx),
                        snapshot.chunkTokenTotals.get(chunkIdx));
                chunkScores.merge(chunkIdx, tf * idf, Double::sum);
            }
        }

        for (Map.Entry<Integer, Double> entry : chunkScores.entrySet()) {
            output.add(new ScoredChunk(snapshot.chunks.get(entry.getKey()), entry.getValue()));
        }
    }

    private static String scopeKey(KnowledgeScope scope) {
        return scope.getAgentName() + ":" + scope.getContextId();
    }

    private static List<String> listFiles(VirtualFileSystem vfs, String directory, IndexOptions options) {
        final List<String> allFiles;
        if (options.isRecursive()) {
            allFiles = vfs.listRecursive(directory);
        } else {
            allFiles = vfs.list(directory).stream().filter(path -> !vfs.isDirectory(path)).collect(Collectors.toList());
        }

        return allFiles.stream().filter(path -> matchesFilePatterns(path, options.getFilePatterns()))
                .collect(Collectors.toList());
    }

    private static String readFileContent(VirtualFileSystem vfs, String filePath) throws IOException {
        try (InputStream is = vfs.read(filePath);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    static List<String> tokenize(String text) {
        final String[] tokens = TOKEN_PATTERN.split(text.toLowerCase());
        final List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() > 1) {
                result.add(token);
            }
        }
        return result;
    }

    private static double computeTf(String term, Map<String, Integer> counts, int totalTokens) {
        if (totalTokens == 0) {
            return 0.0;
        }
        return (double) counts.getOrDefault(term, 0) / totalTokens;
    }

    private static boolean matchesFilePatterns(String path, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        final String fileName = extractFileName(path);
        for (String pattern : patterns) {
            if (matchGlob(pattern, fileName)) {
                return true;
            }
        }
        return false;
    }

    private static String extractFileName(String path) {
        final int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * Matches a simple glob pattern against a file name without regex conversion. Supports {@code *} (any sequence of
     * characters) and {@code ?} (single character). This avoids ReDoS vulnerabilities.
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

    /**
     * Immutable snapshot of a single scope's inverted index.
     */
    private static final class IndexSnapshot {
        final List<DocumentChunk> chunks;
        final Map<String, Set<Integer>> invertedIndex;
        final List<Map<String, Integer>> chunkTokenCounts;
        final List<Integer> chunkTokenTotals;
        final int documentCount;
        final Instant indexedAt;
        final String indexedDirectory;
        final String agentName;

        IndexSnapshot(Builder builder) {
            this.chunks = builder.chunks;
            this.invertedIndex = builder.invertedIndex;
            this.chunkTokenCounts = builder.chunkTokenCounts;
            this.chunkTokenTotals = builder.chunkTokenTotals;
            this.documentCount = builder.documentCount;
            this.indexedAt = builder.indexedAt;
            this.indexedDirectory = builder.indexedDirectory;
            this.agentName = builder.agentName;
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private List<DocumentChunk> chunks;
            private Map<String, Set<Integer>> invertedIndex;
            private List<Map<String, Integer>> chunkTokenCounts;
            private List<Integer> chunkTokenTotals;
            private int documentCount;
            private Instant indexedAt;
            private String indexedDirectory;
            private String agentName;

            Builder chunks(List<DocumentChunk> chunks) {
                this.chunks = chunks;
                return this;
            }

            Builder invertedIndex(Map<String, Set<Integer>> invertedIndex) {
                this.invertedIndex = invertedIndex;
                return this;
            }

            Builder chunkTokenCounts(List<Map<String, Integer>> chunkTokenCounts) {
                this.chunkTokenCounts = chunkTokenCounts;
                return this;
            }

            Builder chunkTokenTotals(List<Integer> chunkTokenTotals) {
                this.chunkTokenTotals = chunkTokenTotals;
                return this;
            }

            Builder documentCount(int documentCount) {
                this.documentCount = documentCount;
                return this;
            }

            Builder indexedAt(Instant indexedAt) {
                this.indexedAt = indexedAt;
                return this;
            }

            Builder indexedDirectory(String indexedDirectory) {
                this.indexedDirectory = indexedDirectory;
                return this;
            }

            Builder agentName(String agentName) {
                this.agentName = agentName;
                return this;
            }

            IndexSnapshot build() {
                return new IndexSnapshot(this);
            }
        }
    }

    /**
     * Internal helper pairing a chunk with its computed score.
     */
    private static final class ScoredChunk {
        final DocumentChunk chunk;
        final double score;

        ScoredChunk(DocumentChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
