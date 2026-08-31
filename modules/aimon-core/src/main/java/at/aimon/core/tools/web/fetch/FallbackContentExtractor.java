package at.aimon.core.tools.web.fetch;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback chain decorator for {@link ContentExtractor}.
 *
 * <p>
 * Delegates to the primary extractor first. If the primary result is shorter than
 * {@code minLength}, delegates to the fallback extractor instead. This enables a
 * progressive enhancement strategy where a fast local extractor (e.g. Jsoup) is tried
 * first, and a more powerful external service (e.g. Firecrawl) is used only when needed.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * ContentExtractor primary = new JsoupContentExtractor();
 * ContentExtractor fallback = new FirecrawlContentExtractor(httpClient, apiKey, objectMapper);
 * ContentExtractor extractor = new FallbackContentExtractor(primary, fallback, 500);
 * }</pre>
 */
public class FallbackContentExtractor implements ContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(FallbackContentExtractor.class);

    private final ContentExtractor primary;
    private final ContentExtractor fallback;
    private final int minLength;

    /**
     * Creates a FallbackContentExtractor.
     *
     * @param primary
     *            the primary extractor to try first (not null)
     * @param fallback
     *            the fallback extractor when primary result is too short (not null)
     * @param minLength
     *            the minimum acceptable length from the primary extractor (must be &gt; 0)
     * @throws NullPointerException
     *             if primary or fallback is null
     * @throws IllegalArgumentException
     *             if minLength is less than 1
     */
    public FallbackContentExtractor(ContentExtractor primary, ContentExtractor fallback, int minLength) {
        this.primary = Objects.requireNonNull(primary, "primary cannot be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback cannot be null");
        if (minLength < 1) {
            throw new IllegalArgumentException("minLength must be > 0");
        }
        this.minLength = minLength;
    }

    @Override
    public String extract(String html, String url, String extractMode) {
        String result = primary.extract(html, url, extractMode);
        if (result != null && result.length() >= minLength) {
            return result;
        }

        int resultLength = (result != null) ? result.length() : 0;

        log.debug("Primary extraction too short ({} chars < {}), falling back for URL: {}", resultLength, minLength,
                url);
        return fallback.extract(html, url, extractMode);
    }
}
