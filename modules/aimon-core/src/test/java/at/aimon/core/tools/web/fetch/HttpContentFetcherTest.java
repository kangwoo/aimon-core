package at.aimon.core.tools.web.fetch;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

@DisplayName("HttpContentFetcher Tests")
class HttpContentFetcherTest {

    private MockWebServer mockWebServer;
    private OkHttpClient httpClient;
    private HttpContentFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        httpClient = new OkHttpClient();
        fetcher = new HttpContentFetcher(httpClient, "TestAgent/1.0", 1_000_000);
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
            assertThatThrownBy(() -> new HttpContentFetcher(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for blank userAgent")
        void testBlankUserAgent() {
            assertThatThrownBy(() -> new HttpContentFetcher(httpClient, "  ", 1000))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userAgent cannot be blank");
        }

        @Test
        @DisplayName("Should throw for maxResponseBytes < 1")
        void testInvalidMaxResponseBytes() {
            assertThatThrownBy(() -> new HttpContentFetcher(httpClient, "Agent", 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxResponseBytes must be > 0");
        }

        @Test
        @DisplayName("Should create with default settings")
        void testDefaultConstructor() {
            HttpContentFetcher defaultFetcher = new HttpContentFetcher(httpClient);
            assertThat(defaultFetcher).isNotNull();
        }
    }

    @Nested
    @DisplayName("fetch - success cases")
    class SuccessCase {

        @Test
        @DisplayName("Should return FetchResult for successful response")
        void testSuccessfulFetch() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("<html><body>Hello</body></html>")
                    .setHeader("Content-Type", "text/html"));

            FetchResult result = fetcher.fetch(mockWebServer.url("/page").toString());

            assertThat(result.getBody()).contains("Hello");
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getContentType()).contains("text/html");
        }

        @Test
        @DisplayName("Should send correct User-Agent header")
        void testUserAgentHeader() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("OK"));

            fetcher.fetch(mockWebServer.url("/test").toString());

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getHeader("User-Agent")).isEqualTo("TestAgent/1.0");
        }

        @Test
        @DisplayName("Should return empty FetchResult when body is null")
        void testNullBody() throws Exception {
            // MockWebServer with empty body
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            FetchResult result = fetcher.fetch(mockWebServer.url("/empty").toString());

            assertThat(result.getStatusCode()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("fetch - error cases")
    class ErrorCase {

        @Test
        @DisplayName("Should throw IOException for HTTP error codes")
        void testHttpErrorCode() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(404));

            assertThatThrownBy(() -> fetcher.fetch(mockWebServer.url("/notfound").toString()))
                    .isInstanceOf(IOException.class).hasMessageContaining("HTTP 404");
        }

        @Test
        @DisplayName("Should throw IOException for server error")
        void testServerError() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));

            assertThatThrownBy(() -> fetcher.fetch(mockWebServer.url("/error").toString()))
                    .isInstanceOf(IOException.class).hasMessageContaining("HTTP 500");
        }
    }

    @Nested
    @DisplayName("fetch - response size limiting")
    class ResponseSizeLimiting {

        @Test
        @DisplayName("Should truncate response body exceeding maxResponseBytes")
        void testTruncation() throws Exception {
            HttpContentFetcher smallFetcher = new HttpContentFetcher(httpClient, "Agent", 100);
            String largeBody = "x".repeat(500);
            mockWebServer.enqueue(new MockResponse().setBody(largeBody));

            FetchResult result = smallFetcher.fetch(mockWebServer.url("/large").toString());

            assertThat(result.getBody().length()).isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("Should not truncate response body within limit")
        void testNoTruncation() throws Exception {
            String body = "Small body";
            mockWebServer.enqueue(new MockResponse().setBody(body));

            FetchResult result = fetcher.fetch(mockWebServer.url("/small").toString());

            assertThat(result.getBody()).isEqualTo(body);
        }
    }
}
