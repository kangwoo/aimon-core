package at.aimon.core.knowledges.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OpenSearchConfigTest {

    @Test
    void shouldBuildWithDefaults() {
        final OpenSearchConfig config = OpenSearchConfig.builder().host("localhost").build();

        assertThat(config.getHost()).isEqualTo("localhost");
        assertThat(config.getPort()).isEqualTo(OpenSearchConfig.DEFAULT_PORT);
        assertThat(config.getScheme()).isEqualTo(OpenSearchConfig.DEFAULT_SCHEME);
        assertThat(config.getIndexName()).isEqualTo(OpenSearchConfig.DEFAULT_INDEX_NAME);
        assertThat(config.getSearchMode()).isEqualTo(SearchMode.KEYWORD);
        assertThat(config.getVectorDimensions()).isEqualTo(OpenSearchConfig.DEFAULT_VECTOR_DIMENSIONS);
        assertThat(config.getKeywordWeight()).isEqualTo(OpenSearchConfig.DEFAULT_KEYWORD_WEIGHT);
        assertThat(config.getVectorWeight()).isEqualTo(OpenSearchConfig.DEFAULT_VECTOR_WEIGHT);
        assertThat(config.getUsername()).isNull();
        assertThat(config.getPassword()).isNull();
        assertThat(config.hasCredentials()).isFalse();
        assertThat(config.requiresEmbedding()).isFalse();
    }

    @Test
    void shouldBuildWithAllFields() {
        final OpenSearchConfig config = OpenSearchConfig.builder().host("search.example.com").port(9201).scheme("http")
                .indexName("my-index").username("admin").password("secret").searchMode(SearchMode.HYBRID)
                .vectorDimensions(768).keywordWeight(0.4f).vectorWeight(0.6f).build();

        assertThat(config.getHost()).isEqualTo("search.example.com");
        assertThat(config.getPort()).isEqualTo(9201);
        assertThat(config.getScheme()).isEqualTo("http");
        assertThat(config.getIndexName()).isEqualTo("my-index");
        assertThat(config.getUsername()).isEqualTo("admin");
        assertThat(config.getPassword()).isEqualTo("secret");
        assertThat(config.hasCredentials()).isTrue();
        assertThat(config.getSearchMode()).isEqualTo(SearchMode.HYBRID);
        assertThat(config.requiresEmbedding()).isTrue();
        assertThat(config.getVectorDimensions()).isEqualTo(768);
    }

    @Test
    void shouldRejectNullHost() {
        assertThatThrownBy(() -> OpenSearchConfig.builder().build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("host");
    }

    @Test
    void shouldRejectEmptyHost() {
        assertThatThrownBy(() -> OpenSearchConfig.builder().host("").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host must not be empty");
    }

    @Test
    void shouldRejectInvalidPort() {
        assertThatThrownBy(() -> OpenSearchConfig.builder().host("localhost").port(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("port");

        assertThatThrownBy(() -> OpenSearchConfig.builder().host("localhost").port(70000).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("port");
    }

    @Test
    void shouldRejectInvalidWeights() {
        assertThatThrownBy(
                () -> OpenSearchConfig.builder().host("localhost").keywordWeight(0.5f).vectorWeight(0.3f).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keywordWeight + vectorWeight must equal 1.0");
    }

    @Test
    void shouldRejectNegativeWeight() {
        assertThatThrownBy(
                () -> OpenSearchConfig.builder().host("localhost").keywordWeight(-0.1f).vectorWeight(1.1f).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keywordWeight must be in [0.0, 1.0]");
    }

    @Test
    void shouldRejectInvalidVectorDimensions() {
        assertThatThrownBy(() -> OpenSearchConfig.builder().host("localhost").vectorDimensions(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("vectorDimensions");
    }

    @Test
    void requiresEmbeddingForVectorMode() {
        final OpenSearchConfig vectorConfig = OpenSearchConfig.builder().host("localhost").searchMode(SearchMode.VECTOR)
                .build();
        assertThat(vectorConfig.requiresEmbedding()).isTrue();
    }

    @Test
    void requiresEmbeddingForHybridMode() {
        final OpenSearchConfig hybridConfig = OpenSearchConfig.builder().host("localhost").searchMode(SearchMode.HYBRID)
                .build();
        assertThat(hybridConfig.requiresEmbedding()).isTrue();
    }

    @Test
    void doesNotRequireEmbeddingForKeywordMode() {
        final OpenSearchConfig keywordConfig = OpenSearchConfig.builder().host("localhost")
                .searchMode(SearchMode.KEYWORD).build();
        assertThat(keywordConfig.requiresEmbedding()).isFalse();
    }

    @Test
    void toStringShouldContainKeyFields() {
        final OpenSearchConfig config = OpenSearchConfig.builder().host("localhost").indexName("test-index").build();
        final String str = config.toString();
        assertThat(str).contains("localhost");
        assertThat(str).contains("test-index");
        assertThat(str).contains("KEYWORD");
        assertThat(str).contains("authenticated=false");
        assertThat(str).doesNotContain("password");
        assertThat(str).doesNotContain("secret");
    }
}
