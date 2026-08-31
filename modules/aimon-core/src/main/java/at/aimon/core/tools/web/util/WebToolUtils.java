package at.aimon.core.tools.web.util;

import java.net.URI;

/**
 * WebSearchTool / WebFetchTool common utilities.
 *
 * <p>
 * Provides shared helper methods for parameter clamping, string truncation,
 * URL validation, and cache key generation.
 */
public final class WebToolUtils {

    private WebToolUtils() {
    }

    /**
     * Clamps a value to the specified min-max range.
     *
     * @param value
     *            the value to clamp
     * @param min
     *            the minimum allowed value
     * @param max
     *            the maximum allowed value
     * @return the clamped value
     */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Truncates a string to the specified maximum length.
     *
     * @param content
     *            the string to truncate (may be null)
     * @param maxLength
     *            the maximum length
     * @return the truncated string, or null if input was null
     */
    public static String truncate(String content, int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength);
    }

    /**
     * Validates that a URL uses the http or https scheme and is well-formed.
     *
     * <p>
     * Uses {@link URI} parsing to reject malformed URLs and obfuscation patterns
     * such as newlines or userinfo.
     *
     * @param url
     *            the URL to validate (may be null)
     * @return true if the URL is a valid http or https URL with a non-empty host
     */
    public static boolean isValidHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Builds a cache key from the given parts.
     *
     * <p>
     * Parts are joined with a null character ({@code '\0'}) separator to prevent
     * accidental collisions between parameter values. Null parts are replaced with
     * empty strings.
     *
     * @param parts
     *            the parts to join
     * @return the cache key
     */
    public static String buildCacheKey(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('\0');
            }
            sb.append(parts[i] != null ? parts[i] : "");
        }
        return sb.toString();
    }
}
