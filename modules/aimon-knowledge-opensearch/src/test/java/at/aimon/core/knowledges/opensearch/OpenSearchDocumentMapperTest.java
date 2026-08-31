package at.aimon.core.knowledges.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.knowledge.DocumentChunk;
import at.aimon.core.knowledge.SearchResult;

class OpenSearchDocumentMapperTest {

    private static final ScopeFilter SCOPE = new ScopeFilter("test-agent", "ctx-001");

    @Test
    void toDocumentShouldMapRequiredFields() {
        final DocumentChunk chunk = DocumentChunk.builder().documentPath("/docs/guide.md")
                .content("This is test content").chunkIndex(2).build();

        final Map<String, Object> doc = OpenSearchDocumentMapper.toDocument(chunk, null, SCOPE);

        assertThat(doc.get(OpenSearchDocumentMapper.FIELD_DOCUMENT_PATH)).isEqualTo("/docs/guide.md");
        assertThat(doc.get(OpenSearchDocumentMapper.FIELD_CHUNK_CONTENT)).isEqualTo("This is test content");
        assertThat(doc.get(OpenSearchDocumentMapper.FIELD_CHUNK_INDEX)).isEqualTo(2);
        assertThat(doc.get(OpenSearchDocumentMapper.FIELD_INDEXED_AT)).isNotNull();
        assertThat(doc.get(OpenSearchDocumentMapper.FIELD_AGENT_NAME)).isEqualTo("test-agent");
        assertThat(doc.get(OpenSearchDocumentMapper.FIELD_CONTEXT_ID)).isEqualTo("ctx-001");
        assertThat(doc).doesNotContainKey(OpenSearchDocumentMapper.FIELD_EMBEDDING);
        assertThat(doc).doesNotContainKey(OpenSearchDocumentMapper.FIELD_METADATA);
    }

    @Test
    void toDocumentTagsTheOwningRuntimeUnderTheFrozenContextIdFieldName() {
        // FROZEN WIRE FORMAT. AgentExecutionContext became AgentRuntime in Java, but this field name stayed
        // "context_id" on purpose (CHANGELOG, "Not changed (deliberately frozen)") because it is an INDEXED field —
        // renaming it requires a full reindex. This assertion deliberately spells the name out instead of going
        // through FIELD_CONTEXT_ID, as toDocumentShouldMapRequiredFields above does: one constant feeds the write
        // path, the index mapping and the search filter alike, so a rename sweep carries the constant and every
        // assertion that references it along in lockstep and can never fail.
        final DocumentChunk chunk = DocumentChunk.builder().documentPath("/docs/guide.md").content("content")
                .chunkIndex(0).build();

        final Map<String, Object> doc = OpenSearchDocumentMapper.toDocument(chunk, null, SCOPE);

        assertThat(doc).containsEntry("context_id", "ctx-001");
    }

    @Test
    void toDocumentShouldIncludeEmbeddingWhenProvided() {
        final DocumentChunk chunk = DocumentChunk.builder().documentPath("/docs/guide.md").content("content")
                .chunkIndex(0).build();

        final float[] embedding = {0.1f, 0.2f, 0.3f};
        final Map<String, Object> doc = OpenSearchDocumentMapper.toDocument(chunk, embedding, SCOPE);

        assertThat(doc.get(OpenSearchDocumentMapper.FIELD_EMBEDDING)).isEqualTo(embedding);
    }

    @Test
    void toDocumentShouldIncludeMetadataWhenPresent() {
        final DocumentChunk chunk = DocumentChunk.builder().documentPath("/docs/guide.md").content("content")
                .chunkIndex(0).metadata(Map.of("source", "wiki")).build();

        final Map<String, Object> doc = OpenSearchDocumentMapper.toDocument(chunk, null, SCOPE);

        @SuppressWarnings("unchecked")
        final Map<String, String> metadata = (Map<String, String>) doc.get(OpenSearchDocumentMapper.FIELD_METADATA);
        assertThat(metadata).containsEntry("source", "wiki");
    }

    @Test
    void toDocumentShouldRejectNullChunk() {
        assertThatThrownBy(() -> OpenSearchDocumentMapper.toDocument(null, null, SCOPE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toDocumentShouldRejectNullScopeFilter() {
        final DocumentChunk chunk = DocumentChunk.builder().documentPath("/docs/guide.md").content("content")
                .chunkIndex(0).build();
        assertThatThrownBy(() -> OpenSearchDocumentMapper.toDocument(chunk, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toSearchResultShouldMapAllFields() {
        final Map<String, Object> source = new HashMap<>();
        source.put(OpenSearchDocumentMapper.FIELD_DOCUMENT_PATH, "/docs/guide.md");
        source.put(OpenSearchDocumentMapper.FIELD_CHUNK_CONTENT, "Found content");
        source.put(OpenSearchDocumentMapper.FIELD_CHUNK_INDEX, 3);
        source.put(OpenSearchDocumentMapper.FIELD_METADATA, Map.of("tag", "ops"));

        final SearchResult result = OpenSearchDocumentMapper.toSearchResult(source, 0.85);

        assertThat(result.getDocumentPath()).isEqualTo("/docs/guide.md");
        assertThat(result.getChunkContent()).isEqualTo("Found content");
        assertThat(result.getChunkIndex()).isEqualTo(3);
        assertThat(result.getScore()).isEqualTo(0.85);
        assertThat(result.getMetadata()).containsEntry("tag", "ops");
    }

    @Test
    void toSearchResultShouldHandleMissingOptionalFields() {
        final Map<String, Object> source = new HashMap<>();
        source.put(OpenSearchDocumentMapper.FIELD_DOCUMENT_PATH, "/docs/guide.md");
        source.put(OpenSearchDocumentMapper.FIELD_CHUNK_CONTENT, "content");

        final SearchResult result = OpenSearchDocumentMapper.toSearchResult(source, 0.5);

        assertThat(result.getDocumentPath()).isEqualTo("/docs/guide.md");
        assertThat(result.getChunkContent()).isEqualTo("content");
        assertThat(result.getChunkIndex()).isEqualTo(0);
        assertThat(result.getScore()).isEqualTo(0.5);
    }

    @Test
    void toSearchResultShouldRejectNullSource() {
        assertThatThrownBy(() -> OpenSearchDocumentMapper.toSearchResult(null, 0.5))
                .isInstanceOf(NullPointerException.class);
    }
}
