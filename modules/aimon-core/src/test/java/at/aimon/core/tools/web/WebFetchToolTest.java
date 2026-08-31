package at.aimon.core.tools.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.tools.web.cache.WebToolCacheRepository;
import at.aimon.core.tools.web.fetch.ContentExtractor;
import at.aimon.core.tools.web.fetch.FetchResult;
import at.aimon.core.tools.web.fetch.HttpContentFetcher;
import at.aimon.core.tools.web.security.SsrfGuard;

@DisplayName("WebFetchTool Tests")
class WebFetchToolTest {

    private HttpContentFetcher fetcher;
    private ContentExtractor contentExtractor;
    private WebToolCacheRepository cache;
    private SsrfGuard ssrfGuard;
    private WebFetchTool tool;

    @BeforeEach
    void setUp() {
        fetcher = mock(HttpContentFetcher.class);
        contentExtractor = mock(ContentExtractor.class);
        cache = mock(WebToolCacheRepository.class);
        ssrfGuard = mock(SsrfGuard.class);
        tool = new WebFetchTool(fetcher, contentExtractor, cache, ssrfGuard, Duration.ofMinutes(15), 50_000);
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinition {

        @Test
        @DisplayName("Should have correct tool name")
        void testToolName() {
            assertThat(WebFetchTool.TOOL_NAME).isEqualTo("WebFetch");
            assertThat(tool.getDefinition().getName()).isEqualTo("WebFetch");
        }

        @Test
        @DisplayName("Should have description")
        void testToolDescription() {
            String description = tool.getDefinition().getDescription();
            assertThat(description).isNotNull();
            assertThat(description).contains("Fetch");
        }

        @Test
        @DisplayName("Should have valid input schema with required url")
        @SuppressWarnings("unchecked")
        void testInputSchema() {
            Map<String, Object> schema = tool.getDefinition().getInputSchema();
            assertThat(schema).containsKey("properties");
            assertThat(schema).containsKey("required");

            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).containsKeys("url", "extract_mode", "max_chars");

            List<String> required = (List<String>) schema.get("required");
            assertThat(required).containsExactly("url");
        }
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw for null fetcher")
        void testNullFetcher() {
            assertThatThrownBy(
                    () -> new WebFetchTool(null, contentExtractor, cache, ssrfGuard, Duration.ofMinutes(15), 50_000))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null contentExtractor")
        void testNullExtractor() {
            assertThatThrownBy(() -> new WebFetchTool(fetcher, null, cache, ssrfGuard, Duration.ofMinutes(15), 50_000))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for maxCharsCap < 1")
        void testInvalidMaxCharsCap() {
            assertThatThrownBy(
                    () -> new WebFetchTool(fetcher, contentExtractor, cache, ssrfGuard, Duration.ofMinutes(15), 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxCharsCap must be > 0");
        }
    }

    @Nested
    @DisplayName("Successful Execution")
    class SuccessfulExecution {

        @Test
        @DisplayName("Should return fetched and extracted content")
        void testSuccessfulFetch() throws Exception {
            when(ssrfGuard.isSafe(anyString())).thenReturn(true);
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(fetcher.fetch("https://example.com"))
                    .thenReturn(FetchResult.builder().body("<html>content</html>").statusCode(200).contentType("text/html").build());
            when(contentExtractor.extract(anyString(), eq("https://example.com"), eq("markdown")))
                    .thenReturn("Extracted markdown content");

            ToolInput input = ToolInput.of("url", "https://example.com");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("URL: https://example.com");
            assertThat(result.getContent()).contains("Content-Type: text/html");
            assertThat(result.getContent()).contains("Extracted markdown content");
        }

        @Test
        @DisplayName("Should use text extract mode when specified")
        void testTextMode() throws Exception {
            when(ssrfGuard.isSafe(anyString())).thenReturn(true);
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(fetcher.fetch(anyString()))
                    .thenReturn(FetchResult.builder().body("<html>content</html>").statusCode(200).contentType("text/html").build());
            when(contentExtractor.extract(anyString(), anyString(), eq("text")))
                    .thenReturn("Plain text content");

            ToolInput input = ToolInput.of(Map.of("url", "https://example.com", "extract_mode", "text"));
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            verify(contentExtractor).extract(anyString(), anyString(), eq("text"));
        }

        @Test
        @DisplayName("Should truncate content and add warning when exceeding max_chars")
        void testTruncation() throws Exception {
            when(ssrfGuard.isSafe(anyString())).thenReturn(true);
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(fetcher.fetch(anyString()))
                    .thenReturn(FetchResult.builder().body("<html>large</html>").statusCode(200).contentType("text/html").build());
            when(contentExtractor.extract(anyString(), anyString(), anyString()))
                    .thenReturn("x".repeat(1000));

            ToolInput input = ToolInput.of(Map.of("url", "https://example.com", "max_chars", 100));
            WebFetchTool smallTool = new WebFetchTool(fetcher, contentExtractor, cache,
                    ssrfGuard, Duration.ofMinutes(15), 100);
            ToolResult result = smallTool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("[Warning: Content was truncated");
        }

        @Test
        @DisplayName("Should store result in cache")
        void testCacheStore() throws Exception {
            when(ssrfGuard.isSafe(anyString())).thenReturn(true);
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(fetcher.fetch(anyString()))
                    .thenReturn(FetchResult.builder().body("html").statusCode(200).contentType("text/html").build());
            when(contentExtractor.extract(anyString(), anyString(), anyString()))
                    .thenReturn("content");

            ToolInput input = ToolInput.of("url", "https://example.com");
            tool.execute(input, ToolContext.empty());

            verify(cache).put(anyString(), anyString(), eq(Duration.ofMinutes(15)));
        }
    }

    @Nested
    @DisplayName("Cache Behavior")
    class CacheBehavior {

        @Test
        @DisplayName("Should return cached result without calling fetcher")
        void testCacheHit() {
            when(ssrfGuard.isSafe(anyString())).thenReturn(true);
            when(cache.get(anyString())).thenReturn(Optional.of("Cached content"));

            ToolInput input = ToolInput.of("url", "https://example.com");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).isEqualTo("Cached content");
            verifyNoInteractions(fetcher);
            verifyNoInteractions(contentExtractor);
        }
    }

    @Nested
    @DisplayName("URL Validation & Security")
    class UrlValidation {

        @Test
        @DisplayName("Should reject non-http URL schemes")
        void testNonHttpScheme() {
            ToolInput input = ToolInput.of("url", "ftp://example.com");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Only http/https URLs are allowed");
        }

        @Test
        @DisplayName("Should reject file protocol")
        void testFileProtocol() {
            ToolInput input = ToolInput.of("url", "file:///etc/passwd");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Only http/https URLs are allowed");
        }

        @Test
        @DisplayName("Should reject SSRF-blocked URLs")
        void testSsrfBlocked() {
            when(ssrfGuard.isSafe("http://10.0.0.1/admin")).thenReturn(false);

            ToolInput input = ToolInput.of("url", "http://10.0.0.1/admin");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("URL blocked by security policy");
        }

        @Test
        @DisplayName("Should reject localhost")
        void testLocalhost() {
            when(ssrfGuard.isSafe("http://localhost:3000/")).thenReturn(false);

            ToolInput input = ToolInput.of("url", "http://localhost:3000/");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("URL blocked by security policy");
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should return error for missing URL")
        void testMissingUrl() {
            ToolInput input = ToolInput.of();
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("Should return timeout error for SocketTimeoutException")
        void testTimeout() throws Exception {
            when(ssrfGuard.isSafe(anyString())).thenReturn(true);
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(fetcher.fetch(anyString())).thenThrow(new SocketTimeoutException("Read timed out"));

            ToolInput input = ToolInput.of("url", "https://example.com");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("Request timed out");
        }

        @Test
        @DisplayName("Should return error for IOException")
        void testIOException() throws Exception {
            when(ssrfGuard.isSafe(anyString())).thenReturn(true);
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(fetcher.fetch(anyString())).thenThrow(new IOException("Connection refused"));

            ToolInput input = ToolInput.of("url", "https://example.com");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Fetch failed");
            assertThat(result.getContent()).contains("Connection refused");
        }

        @Test
        @DisplayName("Should return error for unexpected exception")
        void testUnexpectedException() throws Exception {
            when(ssrfGuard.isSafe(anyString())).thenReturn(true);
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(fetcher.fetch(anyString())).thenThrow(new RuntimeException("Unexpected"));

            ToolInput input = ToolInput.of("url", "https://example.com");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Unexpected error");
        }
    }
}
