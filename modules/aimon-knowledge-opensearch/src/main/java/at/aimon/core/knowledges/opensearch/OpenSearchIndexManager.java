package at.aimon.core.knowledges.opensearch;

import java.io.IOException;
import java.util.Objects;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.KeywordProperty;
import org.opensearch.client.opensearch._types.mapping.KnnVectorProperty;
import org.opensearch.client.opensearch._types.mapping.ObjectProperty;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TextProperty;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages OpenSearch index lifecycle: creation, deletion, and existence checks.
 *
 * <p>
 * Index mappings are configured based on the {@link OpenSearchConfig}, including optional kNN vector fields when
 * vector or hybrid search mode is enabled.
 */
final class OpenSearchIndexManager {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexManager.class);

    private static final String RESOURCE_ALREADY_EXISTS = "resource_already_exists_exception";

    private final OpenSearchClient client;
    private final OpenSearchConfig config;

    OpenSearchIndexManager(OpenSearchClient client, OpenSearchConfig config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Creates the index with appropriate mappings. Does nothing if the index already exists.
     *
     * <p>
     * Uses exception-based detection instead of an extra exists-check round-trip.
     *
     * @throws IOException
     *             if the request fails for reasons other than the index already existing
     */
    void createIndexIfNotExists() throws IOException {
        final CreateIndexRequest.Builder requestBuilder = new CreateIndexRequest.Builder().index(config.getIndexName())
                .mappings(buildMappings());

        if (config.requiresEmbedding()) {
            requestBuilder.settings(new IndexSettings.Builder().knn(true).build());
        }

        try {
            client.indices().create(requestBuilder.build());
            log.info("Created index '{}' with search mode {}", config.getIndexName(), config.getSearchMode());
        } catch (OpenSearchException e) {
            if (e.error() != null && RESOURCE_ALREADY_EXISTS.equals(e.error().type())) {
                log.debug("Index '{}' already exists, skipping creation", config.getIndexName());
            } else {
                throw e;
            }
        }
    }

    /**
     * Deletes the index if it exists.
     *
     * @throws IOException
     *             if the request fails
     */
    void deleteIndexIfExists() throws IOException {
        if (!indexExists()) {
            return;
        }
        client.indices().delete(new DeleteIndexRequest.Builder().index(config.getIndexName()).build());
        log.info("Deleted index '{}'", config.getIndexName());
    }

    private boolean indexExists() throws IOException {
        return client.indices().exists(new ExistsRequest.Builder().index(config.getIndexName()).build()).value();
    }

    private TypeMapping buildMappings() {
        final TypeMapping.Builder mappingBuilder = new TypeMapping.Builder()
                .properties(OpenSearchDocumentMapper.FIELD_DOCUMENT_PATH,
                        new Property.Builder().keyword(new KeywordProperty.Builder().build()).build())
                .properties(OpenSearchDocumentMapper.FIELD_CHUNK_CONTENT,
                        new Property.Builder().text(new TextProperty.Builder().build()).build())
                .properties(OpenSearchDocumentMapper.FIELD_CHUNK_INDEX, new Property.Builder().integer(
                        new org.opensearch.client.opensearch._types.mapping.IntegerNumberProperty.Builder().build())
                        .build())
                .properties(OpenSearchDocumentMapper.FIELD_METADATA,
                        new Property.Builder().object(new ObjectProperty.Builder()
                                .dynamic(org.opensearch.client.opensearch._types.mapping.DynamicMapping.True).build())
                                .build())
                .properties(OpenSearchDocumentMapper.FIELD_INDEXED_AT,
                        new Property.Builder().date(
                                new org.opensearch.client.opensearch._types.mapping.DateProperty.Builder().build())
                                .build())
                .properties(OpenSearchDocumentMapper.FIELD_AGENT_NAME,
                        new Property.Builder().keyword(new KeywordProperty.Builder().build()).build())
                .properties(OpenSearchDocumentMapper.FIELD_CONTEXT_ID,
                        new Property.Builder().keyword(new KeywordProperty.Builder().build()).build());

        if (config.requiresEmbedding()) {
            mappingBuilder.properties(OpenSearchDocumentMapper.FIELD_EMBEDDING,
                    new Property.Builder()
                            .knnVector(new KnnVectorProperty.Builder().dimension(config.getVectorDimensions()).build())
                            .build());
        }

        return mappingBuilder.build();
    }
}
