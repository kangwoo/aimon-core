package at.aimon.core.tools.web.provider;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.tools.web.model.WebSearchResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

@DisplayName("GoogleScrapingSearchProvider Tests")
class GoogleScrapingSearchProviderTest {

    private MockWebServer mockWebServer;
    private OkHttpClient httpClient;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        httpClient = new OkHttpClient();
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
            assertThatThrownBy(() -> new GoogleScrapingSearchProvider(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("search - success cases")
    class SuccessCase {

        @Test
        @DisplayName("Should parse Google HTML response correctly")
        void testSuccessfulParsing() throws Exception {
            String html = createGoogleHtml(createResultDiv("Example Title", "https://example.com", "Example snippet"),
                    createResultDiv("Another Title", "https://another.com", "Another snippet"));
            mockWebServer.enqueue(new MockResponse().setBody(html).setHeader("Content-Type", "text/html"));

            GoogleScrapingSearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test query", 5, null, null, null);

            assertThat(response.getProvider()).isEqualTo("google");
            assertThat(response.getResults()).hasSize(2);
            assertThat(response.getResults().get(0).getTitle()).isEqualTo("Example Title");
            assertThat(response.getResults().get(0).getUrl()).isEqualTo("https://example.com");
            assertThat(response.getResults().get(0).getSnippet()).isEqualTo("Example snippet");
        }

        @Test
        @DisplayName("Should send correct query parameters")
        void testQueryParameters() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody(createGoogleHtml()));

            GoogleScrapingSearchProvider provider = createProvider();
            provider.search("test", 3, "US", "en", null);

            RecordedRequest request = mockWebServer.takeRequest();
            String path = request.getPath();
            assertThat(path).contains("q=test");
            assertThat(path).contains("num=3");
            assertThat(path).contains("gl=US");
            assertThat(path).contains("hl=en");
        }

        @Test
        @DisplayName("Should map freshness pd to tbs=qdr:d")
        void testFreshnessMapping() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody(createGoogleHtml()));

            GoogleScrapingSearchProvider provider = createProvider();
            provider.search("test", 5, null, null, "pd");

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getPath()).contains("tbs=qdr%3Ad");
        }

        @Test
        @DisplayName("Should ignore date range freshness values")
        void testDateRangeFreshnessIgnored() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody(createGoogleHtml()));

            GoogleScrapingSearchProvider provider = createProvider();
            provider.search("test", 5, null, null, "2024-01-01to2024-12-31");

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getPath()).doesNotContain("tbs=");
        }

        @Test
        @DisplayName("Should return empty results when no search results in HTML")
        void testEmptyResults() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody("<html><body><div>No results</div></body></html>"));

            GoogleScrapingSearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("nothing", 5, null, null, null);

            assertThat(response.getResults()).isEmpty();
        }

        @Test
        @DisplayName("Should skip results without title")
        void testMissingTitle() throws Exception {
            String html = createGoogleHtml("<div class=\"g\"><a href=\"https://example.com\">" + "<h3></h3></a></div>",
                    createResultDiv("Valid Title", "https://valid.com", "snippet"));
            mockWebServer.enqueue(new MockResponse().setBody(html));

            GoogleScrapingSearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test", 5, null, null, null);

            assertThat(response.getResults()).hasSize(1);
            assertThat(response.getResults().get(0).getTitle()).isEqualTo("Valid Title");
        }

        @Test
        @DisplayName("Should skip results without valid link")
        void testMissingLink() throws Exception {
            String html = createGoogleHtml("<div class=\"g\"><div>No link here</div></div>",
                    createResultDiv("Valid", "https://valid.com", "snippet"));
            mockWebServer.enqueue(new MockResponse().setBody(html));

            GoogleScrapingSearchProvider provider = createProvider();
            WebSearchResponse response = provider.search("test", 5, null, null, null);

            assertThat(response.getResults()).hasSize(1);
            assertThat(response.getResults().get(0).getTitle()).isEqualTo("Valid");
        }

        @Test
        @DisplayName("Should handle null body gracefully")
        void testNullBody() throws Exception {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200));

            GoogleScrapingSearchProvider provider = createProvider();
            // OkHttp MockWebServer returns empty body (not null),
            // which parses as empty HTML
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
            mockWebServer.enqueue(new MockResponse().setResponseCode(429));

            GoogleScrapingSearchProvider provider = createProvider();
            assertThatThrownBy(() -> provider.search("test", 5, null, null, null)).isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP 429");
        }
    }

    @Nested
    @DisplayName("getProviderName")
    class ProviderName {

        @Test
        @DisplayName("Should return 'google'")
        void testProviderName() {
            GoogleScrapingSearchProvider provider = new GoogleScrapingSearchProvider(httpClient);
            assertThat(provider.getProviderName()).isEqualTo("google");
        }
    }

    private GoogleScrapingSearchProvider createProvider() {
        String baseUrl = mockWebServer.url("/search").toString();
        return new GoogleScrapingSearchProvider(httpClient) {
            @Override
            protected String getBaseUrl() {
                return baseUrl;
            }
        };
    }

    private static String createGoogleHtml(String... resultDivs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><div id=\"search\">");
        for (String div : resultDivs) {
            sb.append(div);
        }
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String createResultDiv(String title, String url, String snippet) {
        return "<div class=\"g\">" + "<a href=\"" + url + "\"><h3>" + title + "</h3></a>" + "<div class=\"VwiC3b\">"
                + snippet + "</div>" + "</div>";
    }
}
