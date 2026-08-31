package at.aimon.core.tools.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
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
import at.aimon.core.tools.web.model.WebSearchResponse;
import at.aimon.core.tools.web.model.WebSearchResult;
import at.aimon.core.tools.web.provider.WebSearchProvider;

@DisplayName("WebSearchTool Tests")
class WebSearchToolTest {

    private WebSearchProvider provider;
    private WebToolCacheRepository cache;
    private WebSearchTool tool;

    @BeforeEach
    void setUp() {
        provider = mock(WebSearchProvider.class);
        cache = mock(WebToolCacheRepository.class);
        tool = new WebSearchTool(provider, cache, Duration.ofMinutes(15));
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinition {

        @Test
        @DisplayName("Should have correct tool name")
        void testToolName() {
            assertThat(WebSearchTool.TOOL_NAME).isEqualTo("WebSearch");
            assertThat(tool.getDefinition().getName()).isEqualTo("WebSearch");
        }

        @Test
        @DisplayName("Should have description")
        void testToolDescription() {
            String description = tool.getDefinition().getDescription();
            assertThat(description).isNotNull();
            assertThat(description).contains("search");
        }

        @Test
        @DisplayName("Should have valid input schema with required query")
        @SuppressWarnings("unchecked")
        void testInputSchema() {
            Map<String, Object> schema = tool.getDefinition().getInputSchema();
            assertThat(schema).containsKey("type");
            assertThat(schema).containsKey("properties");
            assertThat(schema).containsKey("required");

            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).containsKeys("query", "count", "country", "search_lang", "freshness");

            List<String> required = (List<String>) schema.get("required");
            assertThat(required).containsExactly("query");
        }
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw for null provider")
        void testNullProvider() {
            assertThatThrownBy(() -> new WebSearchTool(null, cache, Duration.ofMinutes(15)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null cache")
        void testNullCache() {
            assertThatThrownBy(() -> new WebSearchTool(provider, null, Duration.ofMinutes(15)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should throw for null cacheTtl")
        void testNullCacheTtl() {
            assertThatThrownBy(() -> new WebSearchTool(provider, cache, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Successful Execution")
    class SuccessfulExecution {

        @Test
        @DisplayName("Should return formatted search results")
        void testSuccessfulSearch() throws Exception {
            WebSearchResponse response = WebSearchResponse.builder().provider("brave")
                    .results(List.of(
                            WebSearchResult.builder().title("Title 1").url("https://example.com/1").snippet("Snippet 1")
                                    .build(),
                            WebSearchResult.builder().title("Title 2").url("https://example.com/2").snippet("Snippet 2")
                                    .build()))
                    .build();

            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(provider.search(eq("test query"), eq(5), isNull(), isNull(), isNull())).thenReturn(response);

            ToolInput input = ToolInput.of("query", "test query");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Provider: brave");
            assertThat(result.getContent()).contains("[1] Title 1");
            assertThat(result.getContent()).contains("    URL: https://example.com/1");
            assertThat(result.getContent()).contains("    Snippet 1");
            assertThat(result.getContent()).contains("[2] Title 2");
        }

        @Test
        @DisplayName("Should return 'No results found' when empty")
        void testEmptyResults() throws Exception {
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(provider.search(anyString(), anyInt(), any(), any(), any()))
                    .thenReturn(WebSearchResponse.builder().provider("brave").build());

            ToolInput input = ToolInput.of("query", "nothing");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).isEqualTo("No results found.");
        }

        @Test
        @DisplayName("Should clamp count to 1-10 range")
        void testCountClamping() throws Exception {
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(provider.search(anyString(), eq(10), any(), any(), any()))
                    .thenReturn(WebSearchResponse.builder().provider("brave").build());

            ToolInput input = ToolInput.of(Map.of("query", "test", "count", 50));
            tool.execute(input, ToolContext.empty());

            verify(provider).search("test", 10, null, null, null);
        }

        @Test
        @DisplayName("Should use default count of 5")
        void testDefaultCount() throws Exception {
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(provider.search(anyString(), eq(5), any(), any(), any()))
                    .thenReturn(WebSearchResponse.builder().provider("brave").build());

            ToolInput input = ToolInput.of("query", "test");
            tool.execute(input, ToolContext.empty());

            verify(provider).search("test", 5, null, null, null);
        }

        @Test
        @DisplayName("Should store result in cache")
        void testCacheStore() throws Exception {
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(provider.search(anyString(), anyInt(), any(), any(), any()))
                    .thenReturn(WebSearchResponse.builder().provider("brave").build());

            ToolInput input = ToolInput.of("query", "test");
            tool.execute(input, ToolContext.empty());

            verify(cache).put(anyString(), anyString(), eq(Duration.ofMinutes(15)));
        }
    }

    @Nested
    @DisplayName("Cache Behavior")
    class CacheBehavior {

        @Test
        @DisplayName("Should return cached result without calling provider")
        void testCacheHit() throws Exception {
            when(cache.get(anyString())).thenReturn(Optional.of("Cached results"));

            ToolInput input = ToolInput.of("query", "test");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).isEqualTo("Cached results");
            verifyNoInteractions(provider);
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should return error for empty query")
        void testEmptyQuery() {
            ToolInput input = ToolInput.of("query", "   ");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("query cannot be empty");
        }

        @Test
        @DisplayName("Should return error for missing query")
        void testMissingQuery() {
            ToolInput input = ToolInput.of();
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("Should return error when provider throws IOException")
        void testProviderIOException() throws Exception {
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(provider.search(anyString(), anyInt(), any(), any(), any()))
                    .thenThrow(new IOException("Network error"));

            ToolInput input = ToolInput.of("query", "test");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Search failed");
            assertThat(result.getContent()).contains("Network error");
        }

        @Test
        @DisplayName("Should return error when provider throws RuntimeException")
        void testProviderRuntimeException() throws Exception {
            when(cache.get(anyString())).thenReturn(Optional.empty());
            when(provider.search(anyString(), anyInt(), any(), any(), any()))
                    .thenThrow(new RuntimeException("Unexpected"));

            ToolInput input = ToolInput.of("query", "test");
            ToolResult result = tool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Search failed");
        }
    }
}
