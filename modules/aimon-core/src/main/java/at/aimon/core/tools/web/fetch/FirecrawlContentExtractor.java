package at.aimon.core.tools.web.fetch;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Firecrawl API-based content extractor.
 *
 * <p>
 * Uses the Firecrawl scrape API to extract main content from web pages as markdown.
 * This provides higher quality extraction than local DOM parsing for complex pages
 * (e.g. JavaScript-rendered content, paywalled sites).
 *
 * <h3>API Details</h3>
 * <ul>
 * <li>Endpoint: https://api.firecrawl.dev/v1/scrape
 * <li>Method: POST
 * <li>Body: {"url": "...", "formats": ["markdown"], "onlyMainContent": true}
 * </ul>
 *
 * <p>
 * This extractor ignores the {@code html} parameter and uses the {@code url} parameter
 * to fetch content directly from Firecrawl. The {@code extractMode} parameter is accepted
 * but Firecrawl always returns markdown format.
 *
 * <h3>Trust Boundary</h3>
 * <p>
 * The {@code httpClient} communicates only with the Firecrawl API endpoint (a trusted
 * external service), not with user-supplied URLs directly. URL validation and SSRF
 * protection are the caller's responsibility (typically handled by {@code WebFetchTool}
 * via {@link at.aimon.core.tools.web.security.SsrfGuard} before invoking any
 * {@link ContentExtractor}).
 */
public class FirecrawlContentExtractor implements ContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(FirecrawlContentExtractor.class);

    private static final String DEFAULT_BASE_URL = "https://api.firecrawl.dev/v1/scrape";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    /**
     * Creates a FirecrawlContentExtractor.
     *
     * @param httpClient
     *            the HTTP client (not null)
     * @param apiKey
     *            the Firecrawl API key (not null)
     * @param objectMapper
     *            the Jackson ObjectMapper for JSON processing (not null)
     */
    public FirecrawlContentExtractor(OkHttpClient httpClient, String apiKey, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    @Override
    public String extract(String html, String url, String extractMode) {
        if (url == null || url.isBlank()) {
            return "";
        }

        try {
            return fetchFromFirecrawl(url, extractMode);
        } catch (Exception e) {
            log.warn("Firecrawl extraction failed for URL {}: {}", url, e.getMessage());
            return "";
        }
    }

    /**
     * Returns the base URL for the Firecrawl API.
     *
     * <p>
     * Overridable for testing with MockWebServer.
     *
     * @return the base URL
     */
    protected String getBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    private String fetchFromFirecrawl(String url, String extractMode) throws IOException {
        HttpUrl parsedUrl = HttpUrl.parse(getBaseUrl());
        if (parsedUrl == null) {
            throw new IOException("Invalid Firecrawl API URL: " + getBaseUrl());
        }

        Map<String, Object> bodyMap = Map.of("url", url, "formats", List.of("markdown"), "onlyMainContent", true);

        String requestJson = objectMapper.writeValueAsString(bodyMap);

        Request request = new Request.Builder().url(parsedUrl).header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .post(RequestBody.create(requestJson, JSON_MEDIA_TYPE)).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Firecrawl scrape failed: HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                return "";
            }
            return parseFirecrawlResponse(body.string());
        }
    }

    private String parseFirecrawlResponse(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);

        if (!root.path("success").asBoolean(false)) {
            String error = root.path("error").asText("Unknown error");
            log.warn("Firecrawl returned failure: {}", error);
            return "";
        }

        JsonNode data = root.path("data");
        if (data.isMissingNode()) {
            return "";
        }

        return data.path("markdown").asText("");
    }
}
