package at.aimon.core.tools.web.provider;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.tools.web.model.WebSearchResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

@DisplayName("PerplexitySearchProvider Tests")
class PerplexitySearchProviderTest {

    private MockWebServer mockWebServer;
    private OkHttpClient httpClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        httpClient = new OkHttpClient();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw for null httpClient")
        void testNullHttpClient() {
            assertThatThrownBy(() -> new PerplexitySearchProvider(null, "key", objectMapper))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null apiKey")
        void testNullApiKey() {
            assertThatThrownBy(() -> new PerplexitySearchProvider(httpClient, null, objectMapper))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null objectMapper")
        void testNullObjectMapper() {
            assertThatThrownBy(() -> new PerplexitySearchProvider(httpClient, "key", (ObjectMapper) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null model in 4-arg constructor")
        void testNullModel() {
            assertThatThrownBy(() -> new PerplexitySearchProvider(httpClient, "key", null, objectMapper))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("search - success cases")
    class SuccessCase {

        @Test
        @DisplayName("Should parse Perplexity response with answer and citations")
        void testSuccessfulSearch() throws Exception {
            String jsonResponse = """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "Java 17 introduced sealed classes and pattern matching."
                          }
                        }
                      ],
                      "citations": [
                        "https://docs.oracle.com/en/java/javase/17/",
                        "https://openjdk.org/projects/jdk/17/"
                      ]
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("Java 17 features", 5, null, null, null);

            assertThat(response.getProvider()).isEqualTo("perplexity");
            assertThat(response.getResults()).hasSize(3);

            // First result is the answer
            assertThat(response.getResults().get(0).getTitle()).isEqualTo("Perplexity Answer");
            assertThat(response.getResults().get(0).getUrl()).isNull();
            assertThat(response.getResults().get(0).getSnippet())
                    .isEqualTo("Java 17 introduced sealed classes and pattern matching.");

            // Citations
            assertThat(response.getResults().get(1).getUrl()).isEqualTo("https://docs.oracle.com/en/java/javase/17/");
            assertThat(response.getResults().get(1).getTitle()).isEqualTo("docs.oracle.com");
            assertThat(response.getResults().get(1).getSnippet()).isEqualTo("Source: docs.oracle.com");

            assertThat(response.getResults().get(2).getUrl()).isEqualTo("https://openjdk.org/projects/jdk/17/");
            assertThat(response.getResults().get(2).getTitle()).isEqualTo("openjdk.org");
        }

        @Test
        @DisplayName("Should send correct Authorization header")
        void testAuthorizationHeader() throws Exception {
            mockWebServer.enqueue(
                    new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}],\"citations\":[]}")
                            .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            provider.search("test", 5, null, null, null);

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
            assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        }

        @Test
        @DisplayName("Should use correct model in request body")
        void testModelInRequestBody() throws Exception {
            mockWebServer.enqueue(
                    new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}],\"citations\":[]}")
                            .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProviderWithModel("sonar");
            provider.search("test", 5, null, null, null);

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            JsonNode bodyJson = objectMapper.readTree(body);
            assertThat(bodyJson.path("model").asText()).isEqualTo("sonar");
        }

        @Test
        @DisplayName("Should handle response without citations")
        void testNoCitations() throws Exception {
            String jsonResponse = """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "An answer without citations."
                          }
                        }
                      ]
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test", 5, null, null, null);

            assertThat(response.getResults()).hasSize(1);
            assertThat(response.getResults().get(0).getTitle()).isEqualTo("Perplexity Answer");
        }

        @Test
        @DisplayName("Should handle empty answer with citations")
        void testEmptyAnswerWithCitations() throws Exception {
            String jsonResponse = """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": ""
                          }
                        }
                      ],
                      "citations": [
                        "https://example.com"
                      ]
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test", 5, null, null, null);

            // Empty answer is excluded, only citation remains
            assertThat(response.getResults()).hasSize(1);
            assertThat(response.getResults().get(0).getSnippet()).isEqualTo("Source: example.com");
        }

        @Test
        @DisplayName("Should handle empty choices")
        void testEmptyChoices() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("{\"choices\":[], \"citations\":[]}")
                    .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test", 5, null, null, null);

            assertThat(response.getResults()).isEmpty();
        }

        @Test
        @DisplayName("Should return empty results for empty choices and citations")
        void testEmptyChoicesAndCitations() throws Exception {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"choices\":[],\"citations\":[]}")
                    .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test", 5, null, null, null);

            assertThat(response.getResults()).isEmpty();
        }

        @Test
        @DisplayName("Should limit citations to count parameter")
        void testCitationLimitedByCount() throws Exception {
            String jsonResponse = """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "Answer text."
                          }
                        }
                      ],
                      "citations": [
                        "https://example.com/1",
                        "https://example.com/2",
                        "https://example.com/3",
                        "https://example.com/4",
                        "https://example.com/5"
                      ]
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test", 2, null, null, null);

            // 1 answer + 2 citations (limited by count=2)
            assertThat(response.getResults()).hasSize(3);
            assertThat(response.getResults().get(1).getUrl()).isEqualTo("https://example.com/1");
            assertThat(response.getResults().get(2).getUrl()).isEqualTo("https://example.com/2");
        }
    }

    @Nested
    @DisplayName("search - freshness mapping")
    class FreshnessMapping {

        @Test
        @DisplayName("Should map 'pd' to 'day' in request body")
        void testFreshnessPd() throws Exception {
            mockWebServer.enqueue(
                    new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}],\"citations\":[]}")
                            .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            provider.search("test", 5, null, null, "pd");

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            JsonNode bodyJson = objectMapper.readTree(body);
            assertThat(bodyJson.path("search_recency_filter").asText()).isEqualTo("day");
        }

        @Test
        @DisplayName("Should map 'pw' to 'week' in request body")
        void testFreshnessPw() throws Exception {
            mockWebServer.enqueue(
                    new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}],\"citations\":[]}")
                            .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            provider.search("test", 5, null, null, "pw");

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            JsonNode bodyJson = objectMapper.readTree(body);
            assertThat(bodyJson.path("search_recency_filter").asText()).isEqualTo("week");
        }

        @Test
        @DisplayName("Should ignore unsupported date range freshness")
        void testUnsupportedFreshness() throws Exception {
            mockWebServer.enqueue(
                    new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}],\"citations\":[]}")
                            .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            provider.search("test", 5, null, null, "2024-01-01to2024-12-31");

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            JsonNode bodyJson = objectMapper.readTree(body);
            assertThat(bodyJson.has("search_recency_filter")).isFalse();
        }

        @Test
        @DisplayName("Should map 'pm' to 'month' in request body")
        void testFreshnessPm() throws Exception {
            mockWebServer.enqueue(
                    new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}],\"citations\":[]}")
                            .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            provider.search("test", 5, null, null, "pm");

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            JsonNode bodyJson = objectMapper.readTree(body);
            assertThat(bodyJson.path("search_recency_filter").asText()).isEqualTo("month");
        }

        @Test
        @DisplayName("Should map 'py' to 'year' in request body")
        void testFreshnessPy() throws Exception {
            mockWebServer.enqueue(
                    new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}],\"citations\":[]}")
                            .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            provider.search("test", 5, null, null, "py");

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            JsonNode bodyJson = objectMapper.readTree(body);
            assertThat(bodyJson.path("search_recency_filter").asText()).isEqualTo("year");
        }

        @Test
        @DisplayName("Should not include recency filter when freshness is null")
        void testNullFreshness() throws Exception {
            mockWebServer.enqueue(
                    new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"\"}}],\"citations\":[]}")
                            .setHeader("Content-Type", "application/json"));

            PerplexitySearchProvider provider = createProvider();
            provider.search("test", 5, null, null, null);

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            JsonNode bodyJson = objectMapper.readTree(body);
            assertThat(bodyJson.has("search_recency_filter")).isFalse();
        }
    }

    @Nested
    @DisplayName("search - error cases")
    class ErrorCase {

        @Test
        @DisplayName("Should throw IOException for HTTP error codes")
        void testHttpErrorCode() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(401));

            PerplexitySearchProvider provider = createProvider();
            assertThatThrownBy(() -> provider.search("test", 5, null, null, null)).isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP 401");
        }

        @Test
        @DisplayName("Should throw IOException for server error")
        void testServerError() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            PerplexitySearchProvider provider = createProvider();
            assertThatThrownBy(() -> provider.search("test", 5, null, null, null)).isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP 500");
        }
    }

    @Nested
    @DisplayName("getProviderName")
    class ProviderName {

        @Test
        @DisplayName("Should return 'perplexity'")
        void testProviderName() {
            PerplexitySearchProvider provider = createProvider();
            assertThat(provider.getProviderName()).isEqualTo("perplexity");
        }
    }

    private PerplexitySearchProvider createProvider() {
        String baseUrl = mockWebServer.url("/chat/completions").toString();
        return new PerplexitySearchProvider(httpClient, "test-api-key", objectMapper) {
            @Override
            protected String getBaseUrl() {
                return baseUrl;
            }
        };
    }

    private PerplexitySearchProvider createProviderWithModel(String model) {
        String baseUrl = mockWebServer.url("/chat/completions").toString();
        return new PerplexitySearchProvider(httpClient, "test-api-key", model, objectMapper) {
            @Override
            protected String getBaseUrl() {
                return baseUrl;
            }
        };
    }
}
