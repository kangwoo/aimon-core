package at.aimon.core.tools.web.provider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.tools.web.model.WebSearchResponse;
import at.aimon.core.tools.web.model.WebSearchResult;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Brave Search API implementation of {@link WebSearchProvider}.
 *
 * <p>
 * Uses the Brave Web Search API to perform web searches. Requires a valid API key
 * obtained from the Brave Search developer portal.
 *
 * <h3>API Details</h3>
 * <ul>
 * <li>Endpoint: https://api.search.brave.com/res/v1/web/search
 * <li>Authentication: X-Subscription-Token header
 * <li>Response format: JSON with web.results array
 * </ul>
 */
public class BraveSearchProvider implements WebSearchProvider {

    private static final String DEFAULT_BASE_URL = "https://api.search.brave.com/res/v1/web/search";

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new BraveSearchProvider.
     *
     * @param httpClient
     *            the HTTP client (not null)
     * @param apiKey
     *            the Brave Search API key (not null)
     * @param objectMapper
     *            the Jackson ObjectMapper for JSON parsing (not null)
     */
    public BraveSearchProvider(OkHttpClient httpClient, String apiKey, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    @Override
    public WebSearchResponse search(String query, int count, String country, String searchLang, String freshness)
            throws IOException {
        HttpUrl parsedUrl = HttpUrl.parse(getBaseUrl());
        if (parsedUrl == null) {
            throw new IOException("Invalid search URL: " + getBaseUrl());
        }

        HttpUrl.Builder urlBuilder = parsedUrl.newBuilder().addQueryParameter("q", query).addQueryParameter("count",
                String.valueOf(count));

        if (country != null) {
            urlBuilder.addQueryParameter("country", country);
        }
        if (searchLang != null) {
            urlBuilder.addQueryParameter("search_lang", searchLang);
        }
        if (freshness != null) {
            urlBuilder.addQueryParameter("freshness", freshness);
        }

        Request request = new Request.Builder().url(urlBuilder.build()).header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Brave search failed: HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                return WebSearchResponse.builder().provider("brave").build();
            }
            return parseBraveResponse(body.string());
        }
    }

    /**
     * Returns the base URL for the Brave Search API.
     *
     * <p>
     * Overridable for testing with MockWebServer.
     *
     * @return the base URL
     */
    protected String getBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    private WebSearchResponse parseBraveResponse(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode webResults = root.path("web").path("results");

        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : webResults) {
            String title = item.path("title").asText("");
            String url = item.path("url").asText("");
            String snippet = item.has("description")
                    ? item.path("description").asText("")
                    : item.path("snippet").asText("");

            if (!title.isEmpty() && !url.isEmpty()) {
                results.add(WebSearchResult.builder().title(title).url(url).snippet(snippet).build());
            }
        }

        return WebSearchResponse.builder().provider("brave").results(results).build();
    }

    @Override
    public String getProviderName() {
        return "brave";
    }
}
