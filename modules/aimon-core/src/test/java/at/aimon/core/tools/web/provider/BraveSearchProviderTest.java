package at.aimon.core.tools.web.provider;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.tools.web.model.WebSearchResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

@DisplayName("BraveSearchProvider Tests")
class BraveSearchProviderTest {

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
            assertThatThrownBy(() -> new BraveSearchProvider(null, "key", objectMapper))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null apiKey")
        void testNullApiKey() {
            assertThatThrownBy(() -> new BraveSearchProvider(httpClient, null, objectMapper))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null objectMapper")
        void testNullObjectMapper() {
            assertThatThrownBy(() -> new BraveSearchProvider(httpClient, "key", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("search - success cases")
    class SuccessCase {

        @Test
        @DisplayName("Should parse Brave API response correctly")
        void testSuccessfulSearch() throws Exception {
            String jsonResponse = """
                    {
                      "web": {
                        "results": [
                          {
                            "title": "Example Result",
                            "url": "https://example.com",
                            "description": "This is an example snippet"
                          },
                          {
                            "title": "Another Result",
                            "url": "https://another.com",
                            "snippet": "Another snippet"
                          }
                        ]
                      }
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            BraveSearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test query", 5, null, null, null);

            assertThat(response.getProvider()).isEqualTo("brave");
            assertThat(response.getResults()).hasSize(2);
            assertThat(response.getResults().get(0).getTitle()).isEqualTo("Example Result");
            assertThat(response.getResults().get(0).getUrl()).isEqualTo("https://example.com");
            assertThat(response.getResults().get(0).getSnippet()).isEqualTo("This is an example snippet");
            assertThat(response.getResults().get(1).getSnippet()).isEqualTo("Another snippet");
        }

        @Test
        @DisplayName("Should send correct API key header")
        void testApiKeyHeader() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("{\"web\":{\"results\":[]}}").setHeader("Content-Type",
                    "application/json"));

            BraveSearchProvider provider = createProvider();
            provider.search("test", 5, null, null, null);

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getHeader("X-Subscription-Token")).isEqualTo("test-api-key");
            assertThat(request.getHeader("Accept")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("Should include optional query parameters")
        void testOptionalParams() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("{\"web\":{\"results\":[]}}").setHeader("Content-Type",
                    "application/json"));

            BraveSearchProvider provider = createProvider();
            provider.search("test", 3, "US", "en", "pd");

            RecordedRequest request = mockWebServer.takeRequest();
            String path = request.getPath();
            assertThat(path).contains("count=3");
            assertThat(path).contains("country=US");
            assertThat(path).contains("search_lang=en");
            assertThat(path).contains("freshness=pd");
        }

        @Test
        @DisplayName("Should handle empty results")
        void testEmptyResults() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("{\"web\":{\"results\":[]}}").setHeader("Content-Type",
                    "application/json"));

            BraveSearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("nothing", 5, null, null, null);

            assertThat(response.getResults()).isEmpty();
        }

        @Test
        @DisplayName("Should skip results with missing title or URL")
        void testMissingFields() throws Exception {
            String jsonResponse = """
                    {
                      "web": {
                        "results": [
                          {"title": "", "url": "https://example.com",
                           "description": "No title"},
                          {"title": "No URL", "url": "",
                           "description": "Missing URL"},
                          {"title": "Valid", "url": "https://valid.com",
                           "description": "OK"}
                        ]
                      }
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            BraveSearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test", 5, null, null, null);

            assertThat(response.getResults()).hasSize(1);
            assertThat(response.getResults().get(0).getTitle()).isEqualTo("Valid");
        }

        @Test
        @DisplayName("Should handle missing web.results path")
        void testMissingWebResults() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("{}").setHeader("Content-Type", "application/json"));

            BraveSearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test", 5, null, null, null);

            assertThat(response.getResults()).isEmpty();
        }
    }

    @Nested
    @DisplayName("search - error cases")
    class ErrorCase {

        @Test
        @DisplayName("Should throw IOException for HTTP error codes")
        void testHttpErrorCode() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(403));

            BraveSearchProvider provider = createProvider();
            assertThatThrownBy(() -> provider.search("test", 5, null, null, null)).isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP 403");
        }

        @Test
        @DisplayName("Should throw IOException for server error")
        void testServerError() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            BraveSearchProvider provider = createProvider();
            assertThatThrownBy(() -> provider.search("test", 5, null, null, null)).isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP 500");
        }
    }

    @Nested
    @DisplayName("getProviderName")
    class ProviderName {

        @Test
        @DisplayName("Should return 'brave'")
        void testProviderName() {
            BraveSearchProvider provider = createProvider();
            assertThat(provider.getProviderName()).isEqualTo("brave");
        }
    }

    private BraveSearchProvider createProvider() {
        String baseUrl = mockWebServer.url("/res/v1/web/search").toString();
        return new BraveSearchProvider(httpClient, "test-api-key", objectMapper) {
            @Override
            protected String getBaseUrl() {
                return baseUrl;
            }
        };
    }
}
