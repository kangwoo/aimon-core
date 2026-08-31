package at.aimon.core.agent.artifact;

/**
 * Utility class for extracting file names from paths for artifact registration.
 *
 * <p>
 * Handles both forward slashes ({@code /}) and Windows backslashes ({@code \}). Trailing separators are stripped before
 * extraction.
 *
 * <p>
 * Examples:
 * <ul>
 * <li>{@code "/reports/sales-2024.csv"} returns {@code "sales-2024.csv"}
 * <li>{@code "C:\\reports\\sales.csv"} returns {@code "sales.csv"}
 * <li>{@code "/reports/"} returns {@code "reports"}
 * <li>{@code null} returns {@code null}
 * </ul>
 */
public final class ArtifactFileNames {

    private ArtifactFileNames() {
    }

    /**
     * Extracts the file name from a file path.
     *
     * @param filePath
     *            the file path (may be null or empty)
     * @return the file name portion of the path, or the original value if null/empty
     */
    public static String extractFileName(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return filePath;
        }
        String normalized = filePath.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        final int lastSlash = normalized.lastIndexOf('/');
        final String name = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        return name.isEmpty() ? filePath : name;
    }

}
