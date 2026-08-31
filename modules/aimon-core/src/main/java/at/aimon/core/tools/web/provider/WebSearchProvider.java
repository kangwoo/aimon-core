package at.aimon.core.tools.web.provider;

import java.io.IOException;

import at.aimon.core.tools.web.model.WebSearchResponse;

/**
 * Web search provider interface.
 *
 * <p>
 * Abstracts various search engines (Brave, Google, Perplexity, etc.) behind a
 * uniform interface. Adding a new provider requires only implementing this interface,
 * with no changes to existing code (OCP).
 *
 * <p>
 * Each implementation holds its own provider-specific settings such as API keys
 * and endpoints.
 */
public interface WebSearchProvider {

    /**
     * Performs a web search.
     *
     * @param query
     *            the search query (not blank)
     * @param count
     *            the number of results (1-10)
     * @param country
     *            the country code (nullable, e.g. "US", "KR")
     * @param searchLang
     *            the search language (nullable, e.g. "en", "ko")
     * @param freshness
     *            the freshness filter (nullable, e.g. "pd", "pw", "pm", "py")
     * @return the search response
     * @throws IOException
     *             on network errors
     */
    WebSearchResponse search(String query, int count, String country, String searchLang, String freshness)
            throws IOException;

    /**
     * Returns the provider name (e.g. "brave", "google").
     *
     * @return the provider name
     */
    String getProviderName();
}
