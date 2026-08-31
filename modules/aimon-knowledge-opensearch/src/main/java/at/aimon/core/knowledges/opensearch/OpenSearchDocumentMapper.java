package at.aimon.core.knowledges.opensearch;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.knowledge.DocumentChunk;
import at.aimon.core.knowledge.SearchResult;

/**
 * Maps between framework {@link DocumentChunk}/{@link SearchResult} objects and OpenSearch document representations.
 *
 * <p>
 * OpenSearch documents are represented as {@code Map<String, Object>} for JSON serialization. This mapper handles the
 * conversion in both directions.
 */
final class OpenSearchDocumentMapper {

    static final String FIELD_DOCUMENT_PATH = "document_path";
    static final String FIELD_CHUNK_CONTENT = "chunk_content";
    static final String FIELD_CHUNK_INDEX = "chunk_index";
    static final String FIELD_EMBEDDING = "embedding";
    static final String FIELD_METADATA = "metadata";
    static final String FIELD_INDEXED_AT = "indexed_at";
    static final String FIELD_AGENT_NAME = "agent_name";
    /**
     * <b>FROZEN WIRE KEY — do not rename alongside the Java identifier.</b> The agent-scope refactor renamed the type
     * to {@code AgentRuntime} but deliberately left persisted names alone (CHANGELOG, "Not changed (deliberately
     * frozen)") because this one is an <em>indexed</em> field: renaming it requires a full reindex.
     *
     * <p>
     * This constant spans the write path ({@link #toDocument}), the index mapping
     * ({@code OpenSearchIndexManager#buildMappings}), and the read path ({@link ScopeFilter#toFilterQueries}), so a
     * rename moves all three together and every test stays green — while every document already indexed still carries
     * {@code context_id} and therefore matches the new term filter <em>never</em>. The failure is silent: scoped search
     * returns an empty result set, which is indistinguishable from "no relevant knowledge". Pinned on both paths by
     * {@code OpenSearchDocumentMapperTest} and {@code ScopeFilterTest}.
     */
    static final String FIELD_CONTEXT_ID = "context_id";

    private OpenSearchDocumentMapper() {
        throw new AssertionError("Utility class");
    }

    /**
     * Converts a {@link DocumentChunk} to an OpenSearch document map.
     *
     * @param chunk
     *            the document chunk (must not be null)
     * @param embedding
     *            the embedding vector (nullable — omitted when null)
     * @param scopeFilter
     *            the scope filter containing agentName and contextId (must not be null)
     * @return the document map for indexing
     */
    static Map<String, Object> toDocument(DocumentChunk chunk, float[] embedding, ScopeFilter scopeFilter) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        Objects.requireNonNull(scopeFilter, "scopeFilter must not be null");

        final Map<String, Object> doc = new HashMap<>();
        doc.put(FIELD_DOCUMENT_PATH, chunk.getDocumentPath());
        doc.put(FIELD_CHUNK_CONTENT, chunk.getContent());
        doc.put(FIELD_CHUNK_INDEX, chunk.getChunkIndex());
        doc.put(FIELD_INDEXED_AT, Instant.now().toString());
        doc.put(FIELD_AGENT_NAME, scopeFilter.getAgentName());
        doc.put(FIELD_CONTEXT_ID, scopeFilter.getContextId());

        if (!chunk.getMetadata().isEmpty()) {
            doc.put(FIELD_METADATA, chunk.getMetadata());
        }

        if (embedding != null) {
            doc.put(FIELD_EMBEDDING, embedding);
        }

        return doc;
    }

    /**
     * Converts an OpenSearch hit to a {@link SearchResult}.
     *
     * @param source
     *            the document source map from the search hit
     * @param score
     *            the normalized relevance score in [0.0, 1.0]
     * @return the search result
     */
    static SearchResult toSearchResult(Map<String, Object> source, double score) {
        Objects.requireNonNull(source, "source must not be null");

        final SearchResult.Builder builder = SearchResult.builder()
                .documentPath((String) source.get(FIELD_DOCUMENT_PATH))
                .chunkContent((String) source.get(FIELD_CHUNK_CONTENT)).score(score);

        final Object chunkIndex = source.get(FIELD_CHUNK_INDEX);
        if (chunkIndex instanceof Number number) {
            builder.chunkIndex(number.intValue());
        }

        final Object metadata = source.get(FIELD_METADATA);
        if (metadata instanceof Map<?, ?> rawMap) {
            final Map<String, String> safeMetadata = new HashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    safeMetadata.put(key, value);
                }
            }
            if (!safeMetadata.isEmpty()) {
                builder.metadata(safeMetadata);
            }
        }

        return builder.build();
    }
}
