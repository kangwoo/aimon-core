package at.aimon.core.knowledges.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.knowledge.DocumentChunker;
import at.aimon.core.knowledge.IndexOptions;
import at.aimon.core.knowledge.IndexStatus;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeSource;
import at.aimon.core.knowledge.SearchQuery;

class OpenSearchKnowledgeStoreTest {

    private static final KnowledgeScope SCOPE = new KnowledgeScope("test-agent", "ctx-001");

    private OpenSearchClient mockClient;
    private DocumentChunker mockChunker;
    private OpenSearchKnowledgeStore store;

    @BeforeEach
    void setUp() {
        mockClient = mock(OpenSearchClient.class);
        mockChunker = mock(DocumentChunker.class);

        final OpenSearchConfig config = OpenSearchConfig.builder().host("localhost").build();

        store = new OpenSearchKnowledgeStore(mockClient, config, mockChunker);
    }

    @Test
    void initialStatusShouldBeEmpty() {
        assertThat(store.getStatus().getState()).isEqualTo(IndexStatus.State.EMPTY);
    }

    @Test
    void searchShouldRejectNullQuery() {
        assertThatThrownBy(() -> store.search(SCOPE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void searchShouldRejectNullScope() {
        final SearchQuery query = SearchQuery.builder().queryText("test").build();
        assertThatThrownBy(() -> store.search(null, query)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void indexShouldRejectNullScope() {
        final KnowledgeSource source = new KnowledgeSource(mock(VirtualFileSystem.class), "/docs");
        assertThatThrownBy(() -> store.index(null, source, IndexOptions.defaults()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void indexShouldRejectNullSource() {
        assertThatThrownBy(() -> store.index(SCOPE, null, IndexOptions.defaults()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void closeShouldNotThrow() {
        store.close();
        store.close();
    }

    @Test
    void constructorShouldRejectNullRequiredParams() {
        final OpenSearchConfig config = OpenSearchConfig.builder().host("localhost").build();

        assertThatThrownBy(() -> new OpenSearchKnowledgeStore(null, config, mockChunker))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new OpenSearchKnowledgeStore(mockClient, null, mockChunker))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new OpenSearchKnowledgeStore(mockClient, config, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorShouldRejectMissingEmbeddingClientForVectorMode() {
        final OpenSearchConfig vectorConfig = OpenSearchConfig.builder().host("localhost").searchMode(SearchMode.VECTOR)
                .build();

        assertThatThrownBy(() -> new OpenSearchKnowledgeStore(mockClient, vectorConfig, mockChunker))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EmbeddingClient is required");
    }
}
