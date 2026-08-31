package at.aimon.core.filesystem;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import at.aimon.core.filesystem.exception.InvalidPathException;

/**
 * Validates and normalizes file paths for security and cross-platform compatibility. Thread-safe (stateless operations
 * on immutable basePath).
 *
 * <p>
 * Security features:
 * <ul>
 * <li>Prevents directory traversal attacks (../)
 * <li>Rejects null bytes and control characters
 * <li>Enforces base path containment
 * <li>Normalizes paths for cross-platform compatibility
 * <li>Rejects Windows reserved filenames
 * <li>Enforces maximum path length limits
 * <li>Rejects symbolic links to prevent base path escape
 * </ul>
 *
 * <p>
 * <strong>Security Note (TOCTTOU):</strong> The symbolic link validation is subject to a Time-of-Check-Time-of-Use
 * race condition. Between validation and actual file operation, the filesystem could change (e.g., a symlink could be
 * created). Callers performing security-sensitive file operations should additionally use
 * {@link java.nio.file.LinkOption#NOFOLLOW_LINKS} in their I/O calls to mitigate this risk.
 */
public final class PathValidator {
    private static final Pattern INVALID_CHARS = Pattern.compile("[\\x00-\\x1F<>:\"|?*]");

    /**
     * Windows reserved filenames that cannot be used. These names are reserved at the OS level and attempting to create
     * files with these names will fail on Windows systems.
     */
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3",
            "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7",
            "LPT8", "LPT9");

    /**
     * Maximum path length across different platforms. Linux/Unix: 4096 bytes Windows: 260 characters (without extended
     * path support) We use the more restrictive limit for cross-platform compatibility.
     */
    private static final int MAX_PATH_LENGTH = 4096;

    private final Path basePath;

    /** PathValidator를 생성한다. */
    public PathValidator(String basePath) {
        Objects.requireNonNull(basePath, "Base path cannot be null");
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
    }

    /**
     * Validate and normalize a path string.
     *
     * @param pathString
     *            Path to validate
     * @return Normalized absolute Path
     * @throws InvalidPathException
     *             If path is invalid, contains reserved names, exceeds length limit, attempts directory traversal, or
     *             contains symbolic links
     */
    public Path validateAndNormalize(String pathString) {
        Objects.requireNonNull(pathString, "Path cannot be null");

        // Step 1: Check for empty path
        if (pathString.trim().isEmpty()) {
            throw new InvalidPathException(pathString, "Path cannot be empty");
        }

        // Step 2: Check for invalid characters
        if (INVALID_CHARS.matcher(pathString).find()) {
            throw new InvalidPathException(pathString, "Path contains invalid characters");
        }

        // Step 3: Check for Windows reserved filenames
        validateWindowsReservedNames(pathString);

        // Step 4: Convert to Path and normalize
        final Path path = Paths.get(pathString).normalize();

        // Step 5: Resolve against base path
        final Path resolved = basePath.resolve(path).normalize();

        // Step 6: Check path length limit
        final String resolvedString = resolved.toString();
        if (resolvedString.length() > MAX_PATH_LENGTH) {
            throw new InvalidPathException(pathString, "Path exceeds maximum length of " + MAX_PATH_LENGTH
                    + " characters (" + resolvedString.length() + " characters)");
        }

        // Step 7: Security check - prevent directory traversal
        if (!resolved.startsWith(basePath)) {
            throw new InvalidPathException(pathString, "Path traversal detected (security violation)");
        }

        // Step 8: Security check - reject symbolic links
        validateNoSymbolicLinks(resolved, pathString);

        return resolved;
    }

    /**
     * Validates that the resolved path does not contain any symbolic links. Walks from the base path down to the
     * resolved path, checking each existing component. Stops at the first non-existent component (for write operations
     * creating new files/directories).
     *
     * <p>
     * <strong>Note:</strong> This check is subject to TOCTTOU (Time-of-Check-Time-of-Use) race conditions. The
     * filesystem state may change between this validation and the subsequent file operation. For defense in depth,
     * callers should use {@link java.nio.file.LinkOption#NOFOLLOW_LINKS} in actual file I/O operations.
     *
     * @param resolved
     *            The resolved absolute path to validate
     * @param originalPath
     *            The original path string (for error messages)
     * @throws InvalidPathException
     *             If any component along the path is a symbolic link
     */
    private void validateNoSymbolicLinks(Path resolved, String originalPath) {
        final Path relative = basePath.relativize(resolved);
        Path current = basePath;

        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new InvalidPathException(originalPath, "Symbolic link detected (security violation)");
                }
            } else {
                // Remaining path components don't exist yet — no further checks needed
                break;
            }
        }
    }

    /**
     * Validates that the path does not contain Windows reserved filenames. Reserved names are checked
     * case-insensitively and with or without file extensions.
     *
     * @param pathString
     *            Path to validate
     * @throws InvalidPathException
     *             If path contains a Windows reserved filename
     */
    private void validateWindowsReservedNames(String pathString) {
        final Path path = Paths.get(pathString);

        // Check each component of the path
        for (Path component : path) {
            String name = component.toString().toUpperCase();

            // Remove extension if present (e.g., "CON.txt" -> "CON")
            final int dotIndex = name.indexOf('.');
            if (dotIndex > 0) {
                name = name.substring(0, dotIndex);
            }

            if (WINDOWS_RESERVED_NAMES.contains(name)) {
                throw new InvalidPathException(pathString, "Path contains Windows reserved filename: " + component);
            }
        }
    }

    /**
     * Check if a path string is valid without throwing exceptions.
     *
     * @param pathString
     *            Path to check
     * @return true if valid, false otherwise
     */
    public boolean isValid(String pathString) {
        try {
            validateAndNormalize(pathString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Path getBasePath() {
        return basePath;
    }
}
