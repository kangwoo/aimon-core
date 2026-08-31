package at.aimon.core.tools.web.fetch;

/**
 * Content extraction interface for web pages.
 *
 * <p>
 * Extracts main content from web pages and converts it to markdown or plain text.
 * Implementations may use local HTML parsing (e.g. Jsoup) or external APIs (e.g. Firecrawl).
 *
 * <p>
 * Not all implementations use every parameter. For example, API-based extractors may
 * ignore the {@code html} parameter and fetch content directly using the {@code url}.
 * See implementation Javadoc for details.
 */
public interface ContentExtractor {

    /**
     * Extracts content from a web page.
     *
     * @param html
     *            the raw HTML string (may be ignored by API-based implementations)
     * @param url
     *            the source URL (used for resolving relative paths or fetching content)
     * @param extractMode
     *            the extraction mode ("markdown" or "text")
     * @return the extracted content, or an empty string if extraction fails
     */
    String extract(String html, String url, String extractMode);
}
