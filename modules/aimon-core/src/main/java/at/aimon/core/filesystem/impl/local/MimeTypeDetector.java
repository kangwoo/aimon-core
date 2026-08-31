package at.aimon.core.filesystem.impl.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility for detecting MIME types based on file content and extension.
 *
 * <p>
 * Provides two detection strategies:
 * <ul>
 * <li>Java's built-in {@link Files#probeContentType(Path)} for content-based detection
 * <li>Extension-based fallback using a comprehensive mapping of common file extensions
 * </ul>
 *
 * <p>
 * All methods are static and thread-safe.
 */
final class MimeTypeDetector {

    /** Common MIME type mappings by file extension. */
    private static final Map<String, String> MIME_TYPE_MAP = createMimeTypeMap();

    private MimeTypeDetector() {
        // Utility class
    }

    private static Map<String, String> createMimeTypeMap() {
        final Map<String, String> map = new HashMap<>();

        // Text
        map.put("txt", "text/plain");
        map.put("html", "text/html");
        map.put("htm", "text/html");
        map.put("css", "text/css");
        map.put("js", "text/javascript");
        map.put("json", "application/json");
        map.put("xml", "application/xml");
        map.put("csv", "text/csv");
        map.put("md", "text/markdown");

        // Images
        map.put("jpg", "image/jpeg");
        map.put("jpeg", "image/jpeg");
        map.put("png", "image/png");
        map.put("gif", "image/gif");
        map.put("bmp", "image/bmp");
        map.put("svg", "image/svg+xml");
        map.put("ico", "image/x-icon");
        map.put("webp", "image/webp");

        // Documents
        map.put("pdf", "application/pdf");
        map.put("doc", "application/msword");
        map.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        map.put("xls", "application/vnd.ms-excel");
        map.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        map.put("ppt", "application/vnd.ms-powerpoint");
        map.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

        // Archives
        map.put("zip", "application/zip");
        map.put("tar", "application/x-tar");
        map.put("gz", "application/gzip");
        map.put("rar", "application/x-rar-compressed");
        map.put("7z", "application/x-7z-compressed");

        // Audio
        map.put("mp3", "audio/mpeg");
        map.put("wav", "audio/wav");
        map.put("ogg", "audio/ogg");
        map.put("m4a", "audio/mp4");

        // Video
        map.put("mp4", "video/mp4");
        map.put("avi", "video/x-msvideo");
        map.put("mov", "video/quicktime");
        map.put("wmv", "video/x-ms-wmv");
        map.put("flv", "video/x-flv");
        map.put("mkv", "video/x-matroska");

        // Binary/Other
        map.put("bin", "application/octet-stream");
        map.put("exe", "application/x-msdownload");
        map.put("jar", "application/java-archive");

        return Collections.unmodifiableMap(map);
    }

    /**
     * Detects the MIME type of a file based on its path and content. First attempts to use Java's built-in content type
     * detection, then falls back to extension-based mapping if that fails.
     *
     * @param targetPath
     *            The file path to analyze
     * @return MIME type string, or null if type cannot be determined
     */
    static String detectMimeType(Path targetPath) {
        // First try Java's built-in content type detection
        try {
            final String mimeType = Files.probeContentType(targetPath);
            if (mimeType != null) {
                return mimeType;
            }
        } catch (IOException e) {
            // Fall through to extension-based detection
        }

        // Fall back to extension-based detection
        return detectMimeTypeByExtension(targetPath.toString());
    }

    /**
     * Detects MIME type based on file extension. Used as fallback when Files.probeContentType() fails.
     *
     * @param path
     *            The file path including extension
     * @return MIME type string, or null if extension is unknown
     */
    static String detectMimeTypeByExtension(String path) {
        final int lastDot = path.lastIndexOf('.');
        if (lastDot == -1 || lastDot == path.length() - 1) {
            return null; // No extension
        }

        final String extension = path.substring(lastDot + 1).toLowerCase();
        return MIME_TYPE_MAP.get(extension);
    }
}
