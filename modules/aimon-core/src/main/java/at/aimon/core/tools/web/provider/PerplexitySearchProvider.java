package at.aimon.core.tools.web.provider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.tools.web.model.WebSearchResponse;
import at.aimon.core.tools.web.model.WebSearchResult;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Perplexity Sonar API implementation of {@link WebSearchProvider}.
 *
 * <p>
 * Uses the Perplexity chat/completions API with Sonar models to perform web searches.
 * Perplexity returns a summarized answer with citations rather than traditional search
 * results, so the response is normalized into the standard {@link WebSearchResult} format.
 *
 * <h3>Response Normalization</h3>
 * <ul>
 * <li>results[0]: title="Perplexity Answer", url="", snippet=summarized answer
 * <li>results[1..n]: title=citation domain, url=citation URL, snippet="citation"
 * </ul>
 *
 * <h3>Parameter Handling</h3>
 * <p>
 * Perplexity's chat/completions API does not support {@code count} or {@code country}
 * parameters directly. The {@code count} parameter is used to limit the number of
 * citation results returned. The {@code country} parameter is ignored.
 *
 * <h3>Freshness Mapping</h3>
 * <p>
 * Supports "pd", "pw", "pm", "py" freshness values mapped to Perplexity's
 * {@code search_recency_filter}. Date range format ("YYYY-MM-DDtoYYYY-MM-DD") is
 * not supported and will be ignored.
 */
public class PerplexitySearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(PerplexitySearchProvider.class);

    private static final String DEFAULT_BASE_URL = "https://api.perplexity.ai/chat/completions";
    private static final String DEFAULT_MODEL = "sonar-pro";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final Map<String, String> FRESHNESS_MAP = Map.of("pd", "day", "pw", "week", "pm", "month", "py",
            "year");

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    /**
     * Creates a PerplexitySearchProvider with a custom model.
     *
     * @param httpClient
     *            the HTTP client (not null)
     * @param apiKey
     *            the Perplexity API key (not null)
     * @param model
     *            the Sonar model to use (not null, e.g. "sonar-pro", "sonar")
     * @param objectMapper
     *            the Jackson ObjectMapper for JSON processing (not null)
     */
    public PerplexitySearchProvider(OkHttpClient httpClient, String apiKey, String model, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey cannot be null");
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    /**
     * Creates a PerplexitySearchProvider with the default model (sonar-pro).
     *
     * @param httpClient
     *            the HTTP client (not null)
     * @param apiKey
     *            the Perplexity API key (not null)
     * @param objectMapper
     *            the Jackson ObjectMapper for JSON processing (not null)
     */
    public PerplexitySearchProvider(OkHttpClient httpClient, String apiKey, ObjectMapper objectMapper) {
        this(httpClient, apiKey, DEFAULT_MODEL, objectMapper);
    }

    @Override
    public WebSearchResponse search(String query, int count, String country, String searchLang, String freshness)
            throws IOException {
        HttpUrl parsedUrl = HttpUrl.parse(getBaseUrl());
        if (parsedUrl == null) {
            throw new IOException("Invalid API URL: " + getBaseUrl());
        }

        String requestJson = buildRequestBody(query, searchLang, freshness);

        Request request = new Request.Builder().url(parsedUrl).header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .post(RequestBody.create(requestJson, JSON_MEDIA_TYPE)).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Perplexity search failed: HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                return WebSearchResponse.builder().provider("perplexity").build();
            }
            return parsePerplexityResponse(body.string(), count);
        }
    }

    /**
     * Returns the base URL for the Perplexity API.
     *
     * <p>
     * Overridable for testing with MockWebServer.
     *
     * @return the base URL
     */
    protected String getBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    private String buildRequestBody(String query, String searchLang, String freshness) throws IOException {
        var messages = List.of(Map.of("role", "system", "content", "Return citations with your answer"),
                Map.of("role", "user", "content", query));

        var bodyMap = new LinkedHashMap<String, Object>();
        bodyMap.put("model", model);
        bodyMap.put("messages", messages);

        String recency = mapFreshness(freshness);
        if (recency != null) {
            bodyMap.put("search_recency_filter", recency);
        }

        return objectMapper.writeValueAsString(bodyMap);
    }

    /**
     * Maps Brave-style freshness values to Perplexity search_recency_filter.
     *
     * @param freshness
     *            the freshness value (nullable)
     * @return the Perplexity recency filter value, or null if not mappable
     */
    private String mapFreshness(String freshness) {
        if (freshness == null) {
            return null;
        }
        String mapped = FRESHNESS_MAP.get(freshness);
        if (mapped != null) {
            return mapped;
        }
        // Date range format (YYYY-MM-DDtoYYYY-MM-DD) is not supported by Perplexity
        log.debug("Unsupported freshness format for Perplexity, ignoring: {}", freshness);
        return null;
    }

    private WebSearchResponse parsePerplexityResponse(String json, int count) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        List<WebSearchResult> results = new ArrayList<>();

        // Extract answer from choices[0].message.content
        JsonNode choices = root.path("choices");
        String answer = "";
        if (choices.isArray() && !choices.isEmpty()) {
            answer = choices.get(0).path("message").path("content").asText("");
        }

        if (!answer.isEmpty()) {
            results.add(WebSearchResult.builder().title("Perplexity Answer").snippet(answer).build());
        }

        // Extract citations (limited by count parameter)
        JsonNode citations = root.path("citations");
        if (citations.isArray()) {
            int citationCount = 0;
            for (JsonNode citation : citations) {
                if (citationCount >= count) {
                    break;
                }
                String url = citation.asText("");
                if (!url.isEmpty()) {
                    String domain = extractDomain(url);
                    results.add(WebSearchResult.builder().title(domain).url(url).snippet("Source: " + domain).build());
                    citationCount++;
                }
            }
        }

        return WebSearchResponse.builder().provider("perplexity").results(results).build();
    }

    private String extractDomain(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed != null) {
            return parsed.host();
        }
        return url;
    }

    @Override
    public String getProviderName() {
        return "perplexity";
    }
}
