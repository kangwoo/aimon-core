package at.aimon.core.tools.web.fetch;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

@DisplayName("FirecrawlContentExtractor Tests")
class FirecrawlContentExtractorTest {

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
            assertThatThrownBy(() -> new FirecrawlContentExtractor(null, "key", objectMapper))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null apiKey")
        void testNullApiKey() {
            assertThatThrownBy(() -> new FirecrawlContentExtractor(httpClient, null, objectMapper))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null objectMapper")
        void testNullObjectMapper() {
            assertThatThrownBy(() -> new FirecrawlContentExtractor(httpClient, "key", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("extract - success cases")
    class SuccessCase {

        @Test
        @DisplayName("Should extract markdown content from Firecrawl response")
        void testMarkdownExtraction() throws Exception {
            String jsonResponse = """
                    {
                      "success": true,
                      "data": {
                        "markdown": "# Hello World"
                      }
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            FirecrawlContentExtractor extractor = createExtractor();
            String result = extractor.extract("<html>ignored</html>", "https://example.com", "markdown");

            assertThat(result).isEqualTo("# Hello World");
        }

        @Test
        @DisplayName("Should always request markdown format even for text mode")
        void testTextModeAlsoRequestsMarkdown() throws Exception {
            String jsonResponse = """
                    {
                      "success": true,
                      "data": {
                        "markdown": "Plain text content"
                      }
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            FirecrawlContentExtractor extractor = createExtractor();
            String result = extractor.extract("<html></html>", "https://example.com", "text");

            assertThat(result).isEqualTo("Plain text content");

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            JsonNode bodyJson = objectMapper.readTree(body);
            assertThat(bodyJson.path("formats").get(0).asText()).isEqualTo("markdown");
        }

        @Test
        @DisplayName("Should send correct Authorization header")
        void testAuthorizationHeader() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("{\"success\":true,\"data\":{\"markdown\":\"\"}}")
                    .setHeader("Content-Type", "application/json"));

            FirecrawlContentExtractor extractor = createExtractor();
            extractor.extract("<html></html>", "https://example.com", "markdown");

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
        }

        @Test
        @DisplayName("Should include url and onlyMainContent in request body")
        void testRequestBody() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("{\"success\":true,\"data\":{\"markdown\":\"\"}}")
                    .setHeader("Content-Type", "application/json"));

            FirecrawlContentExtractor extractor = createExtractor();
            extractor.extract("<html></html>", "https://example.com/page", "markdown");

            RecordedRequest request = mockWebServer.takeRequest();
            String body = request.getBody().readUtf8();
            JsonNode bodyJson = objectMapper.readTree(body);
            assertThat(bodyJson.path("url").asText()).isEqualTo("https://example.com/page");
            assertThat(bodyJson.path("onlyMainContent").asBoolean()).isTrue();
            assertThat(bodyJson.path("formats").get(0).asText()).isEqualTo("markdown");
        }

        @Test
        @DisplayName("Should return empty string when markdown is empty")
        void testEmptyMarkdown() throws Exception {
            String jsonResponse = """
                    {
                      "success": true,
                      "data": {
                        "markdown": ""
                      }
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            FirecrawlContentExtractor extractor = createExtractor();
            String result = extractor.extract("<html></html>", "https://example.com", "markdown");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("extract - error cases")
    class ErrorCase {

        @Test
        @DisplayName("Should return empty string for null URL")
        void testNullUrl() {
            FirecrawlContentExtractor extractor = createExtractor();
            String result = extractor.extract("<html></html>", null, "markdown");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty string for blank URL")
        void testBlankUrl() {
            FirecrawlContentExtractor extractor = createExtractor();
            String result = extractor.extract("<html></html>", "  ", "markdown");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty string when Firecrawl returns failure")
        void testFirecrawlFailure() throws Exception {
            String jsonResponse = """
                    {
                      "success": false,
                      "error": "URL is not accessible"
                    }
                    """;
            mockWebServer
                    .enqueue(new MockResponse().setBody(jsonResponse).setHeader("Content-Type", "application/json"));

            FirecrawlContentExtractor extractor = createExtractor();
            String result = extractor.extract("<html></html>", "https://example.com", "markdown");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty string when HTTP error occurs")
        void testHttpError() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            FirecrawlContentExtractor extractor = createExtractor();
            String result = extractor.extract("<html></html>", "https://example.com", "markdown");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty string when data is missing")
        void testMissingData() throws Exception {
            mockWebServer.enqueue(
                    new MockResponse().setBody("{\"success\":true}").setHeader("Content-Type", "application/json"));

            FirecrawlContentExtractor extractor = createExtractor();
            String result = extractor.extract("<html></html>", "https://example.com", "markdown");

            assertThat(result).isEmpty();
        }
    }

    private FirecrawlContentExtractor createExtractor() {
        String baseUrl = mockWebServer.url("/v1/scrape").toString();
        return new FirecrawlContentExtractor(httpClient, "test-api-key", objectMapper) {
            @Override
            protected String getBaseUrl() {
                return baseUrl;
            }
        };
    }
}
