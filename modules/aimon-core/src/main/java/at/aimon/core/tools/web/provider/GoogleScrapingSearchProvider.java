package at.aimon.core.tools.web.provider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.tools.web.model.WebSearchResponse;
import at.aimon.core.tools.web.model.WebSearchResult;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Google search HTML scraping implementation of {@link WebSearchProvider}.
 *
 * <p>
 * Fetches Google search results via OkHttp and parses the HTML with Jsoup.
 * Does not require an API key, making it suitable for local development, testing,
 * and small-scale usage.
 *
 * <h3>WARNING: Not recommended for production use</h3>
 * <p>
 * Google's Terms of Service explicitly prohibit automated scraping. Using this provider
 * in production may result in IP blocking, CAPTCHA challenges, or legal consequences.
 * For production environments, use {@link BraveSearchProvider} or
 * {@link PerplexitySearchProvider} instead.
 *
 * <h3>Limitations</h3>
 * <ul>
 * <li>Google ToS prohibits automated scraping — use only for development/testing.
 * <li>Frequent requests may trigger CAPTCHA or HTTP 429 responses.
 * <li>HTML structure changes may break parsing logic without notice.
 * <li>Snippet extraction accuracy may be lower than API-based providers.
 * <li>Date range freshness format ("YYYY-MM-DDtoYYYY-MM-DD") is not supported.
 * </ul>
 */
public class GoogleScrapingSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleScrapingSearchProvider.class);
    private static final String DEFAULT_BASE_URL = "https://www.google.com/search";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Map<String, String> FRESHNESS_MAP = Map.of("pd", "qdr:d", "pw", "qdr:w", "pm", "qdr:m", "py",
            "qdr:y");

    private static final List<String> SNIPPET_SELECTORS = List.of("div[data-sncf]", "div.VwiC3b", "span.aCOpRe",
            "div.IsZvec");

    private final OkHttpClient httpClient;

    /**
     * Creates a new GoogleScrapingSearchProvider.
     *
     * @param httpClient
     *            the OkHttpClient (timeout etc. configured externally)
     */
    public GoogleScrapingSearchProvider(OkHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
    }

    @Override
    public WebSearchResponse search(String query, int count, String country, String searchLang, String freshness)
            throws IOException {
        HttpUrl parsedUrl = HttpUrl.parse(getBaseUrl());
        if (parsedUrl == null) {
            throw new IOException("Invalid search URL: " + getBaseUrl());
        }

        HttpUrl.Builder urlBuilder = parsedUrl.newBuilder().addQueryParameter("q", query).addQueryParameter("num",
                String.valueOf(count));

        if (searchLang != null) {
            urlBuilder.addQueryParameter("hl", searchLang);
        }
        if (country != null) {
            urlBuilder.addQueryParameter("gl", country);
        }
        if (freshness != null) {
            String tbs = FRESHNESS_MAP.get(freshness);
            if (tbs != null) {
                urlBuilder.addQueryParameter("tbs", tbs);
            } else {
                log.debug("Unsupported freshness format for Google scraping, ignoring: {}", freshness);
            }
        }

        Request request = new Request.Builder().url(urlBuilder.build()).header("User-Agent", USER_AGENT)
                .header("Accept", "text/html").header("Accept-Language", searchLang != null ? searchLang : "en")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Google search failed: HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                return WebSearchResponse.builder().provider("google").build();
            }
            return parseGoogleResponse(body.string());
        }
    }

    /**
     * Returns the base URL for Google search.
     *
     * <p>
     * Overridable for testing with MockWebServer.
     *
     * @return the base URL
     */
    protected String getBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    private WebSearchResponse parseGoogleResponse(String html) {
        Document doc = Jsoup.parse(html);
        List<WebSearchResult> results = new ArrayList<>();

        // Google search results are inside <div class="g">
        Elements searchResults = doc.select("div.g");
        for (Element result : searchResults) {
            // Extract link + title
            Element linkEl = result.selectFirst("a[href]");
            if (linkEl == null) {
                continue;
            }
            String url = linkEl.absUrl("href");
            if (url.isEmpty() || !url.startsWith("http")) {
                url = linkEl.attr("href");
                if (url.isEmpty() || !url.startsWith("http")) {
                    continue;
                }
            }

            Element titleEl = linkEl.selectFirst("h3");
            String title = (titleEl != null) ? titleEl.text() : "";
            if (title.isEmpty()) {
                continue;
            }

            // Extract snippet (try multiple selectors)
            String snippet = "";
            for (String selector : SNIPPET_SELECTORS) {
                Element snippetEl = result.selectFirst(selector);
                if (snippetEl != null && !snippetEl.text().isBlank()) {
                    snippet = snippetEl.text();
                    break;
                }
            }

            results.add(WebSearchResult.builder().title(title).url(url).snippet(snippet).build());
        }

        log.debug("Parsed {} results from Google HTML", results.size());
        return WebSearchResponse.builder().provider("google").results(results).build();
    }

    @Override
    public String getProviderName() {
        return "google";
    }
}
