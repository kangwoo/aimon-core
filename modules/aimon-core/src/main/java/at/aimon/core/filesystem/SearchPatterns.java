package at.aimon.core.filesystem;

/**
 * Utility class for sanitizing file search patterns used by VirtualFileSystem implementations.
 */
public final class SearchPatterns {

    private SearchPatterns() {
        // Utility class
    }

    /**
     * Sanitizes a search pattern by removing blocked characters and collapsing consecutive wildcards.
     *
     * <p>
     * Blocked characters ({@code /\{}[]}) are removed. Consecutive {@code *} wildcards are collapsed to a single
     * {@code *}.
     *
     * @param pattern
     *            The raw search pattern
     * @return Sanitized pattern, or empty string if pattern becomes empty after sanitization
     */
    public static String sanitize(String pattern) {
        // Remove blocked characters: / \ { } [ ]
        String sanitized = pattern.replaceAll("[/\\\\{}\\[\\]]", "");
        // Collapse consecutive * to single *
        sanitized = sanitized.replaceAll("\\*{2,}", "*");
        return sanitized;
    }
}
