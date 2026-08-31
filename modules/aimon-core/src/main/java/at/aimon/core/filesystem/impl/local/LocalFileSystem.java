package at.aimon.core.filesystem.impl.local;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.core.filesystem.PathValidator;
import at.aimon.core.filesystem.SearchPatterns;
import at.aimon.core.filesystem.SizeLimitedOutputStream;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.BackendConnectionException;
import at.aimon.core.filesystem.exception.ConfigurationException;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InsufficientStorageException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;

/**
 * Local filesystem implementation of VirtualFileSystem interface. Uses Java NIO.2 for cross-platform file operations.
 * Thread-safe for concurrent operations.
 */
public final class LocalFileSystem implements VirtualFileSystem {
    private static final Logger log = LoggerFactory.getLogger(LocalFileSystem.class);
    /** Maximum search depth to prevent DoS from deeply nested directories. */
    private static final int MAX_SEARCH_DEPTH = 20;
    private final LocalFileSystemConfig config;
    private final PathValidator pathValidator;
    private volatile boolean initialized;
    private volatile boolean closed;

    /** Creates a new LocalFileSystem. */
    public LocalFileSystem(LocalFileSystemConfig config) {
        Objects.requireNonNull(config, "Config cannot be null");
        this.config = config;
        pathValidator = new PathValidator(config.getBasePath().toString());
        initialized = false;
        closed = false;
    }

    @Override
    public void initialize() {
        if (initialized) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Cannot initialize closed VirtualFileSystem");
        }

        log.info("Initializing LocalFileSystem with base path: {}", config.getBasePath());

        try {
            final Path basePath = config.getBasePath();

            // Create base directory if it doesn't exist
            if (!Files.exists(basePath)) {
                Files.createDirectories(basePath);
            }

            // Validate that base path is a directory
            if (!Files.isDirectory(basePath)) {
                throw new ConfigurationException("Base path is not a directory: " + basePath);
            }

            // Validate write permissions
            if (!Files.isWritable(basePath)) {
                throw new ConfigurationException("Base path is not writable: " + basePath);
            }

            initialized = true;
            log.info("LocalFileSystem initialized successfully");
        } catch (IOException e) {
            log.error("Failed to initialize LocalFileSystem: {}", e.getMessage(), e);
            throw new BackendConnectionException(BackendType.LOCAL,
                    "Failed to initialize local filesystem: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(String path, InputStream content, long contentLength) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");

        // Fast-fail when the caller declares a length that already exceeds the cap (avoids starting the write). This
        // is advisory only — the authoritative enforcement is the running byte counter in the copy loop below, which
        // also covers streaming writes with an unknown/under-declared length (contentLength <= 0).
        if (config.hasMaxFileSize() && contentLength > 0 && contentLength > config.getMaxFileSize()) {
            throw new InsufficientStorageException("File size " + contentLength
                    + " bytes exceeds maximum allowed size of " + config.getMaxFileSize() + " bytes");
        }

        final Path targetPath = pathValidator.validateAndNormalize(path);

        log.debug("Writing file: {} ({} bytes)", path, contentLength);

        // Create parent directories if configured to do so
        createParentDirectories(targetPath);

        try {
            // Write file using buffered I/O. The optional SizeLimitedOutputStream enforces maxFileSize against bytes
            // ACTUALLY written, not the caller-declared contentLength, so a streaming write (contentLength == -1) or an
            // under-declared length cannot bypass the limit. It is the same wrapper openOutputStream() applies, so both
            // the bulk-write and streaming paths share one enforcement point.
            try (BufferedInputStream bis = new BufferedInputStream(content, config.getBufferSize());
                    BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(targetPath,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS), config.getBufferSize())) {

                final OutputStream out = SizeLimitedOutputStream.wrap(bos, config.getMaxFileSize());
                final byte[] buffer = new byte[config.getBufferSize()];
                int bytesRead;
                while ((bytesRead = bis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush(); // Ensure all buffered data is written
            }
            log.debug("Successfully wrote file: {}", path);
        } catch (InsufficientStorageException e) {
            // Remove the partial file so a rejected oversized write leaves no truncated artifact behind.
            deletePartialFile(targetPath);
            throw e;
        } catch (IOException e) {
            log.error("Failed to write file {}: {}", path, e.getMessage(), e);
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to write file: " + path, e);
        }
    }

    private void deletePartialFile(Path targetPath) {
        try {
            Files.deleteIfExists(targetPath);
        } catch (IOException e) {
            log.warn("Failed to delete partial file after a rejected write: {} ({})", targetPath, e.getMessage());
        }
    }

    @Override
    public InputStream read(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        log.debug("Reading file: {}", path);

        final Path targetPath = pathValidator.validateAndNormalize(path);

        // Check if file exists
        if (!Files.exists(targetPath)) {
            log.debug("File not found: {}", path);
            throw new FileNotFoundException(path);
        }

        // Check if it's a regular file
        if (!Files.isRegularFile(targetPath)) {
            log.debug("Not a regular file: {}", path);
            throw new InvalidPathException(path, "Not a regular file");
        }

        try {
            log.debug("Successfully opened file for reading: {}", path);
            return new BufferedInputStream(
                    Files.newInputStream(targetPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                    config.getBufferSize());
        } catch (IOException e) {
            log.error("Failed to read file {}: {}", path, e.getMessage(), e);
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to read file: " + path, e);
        }
    }

    @Override
    public void delete(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        final Path targetPath = pathValidator.validateAndNormalize(path);

        // Check if file exists
        if (!Files.exists(targetPath)) {
            throw new FileNotFoundException(path);
        }

        try {
            Files.delete(targetPath);
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to delete file: " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        try {
            final Path targetPath = pathValidator.validateAndNormalize(path);
            return Files.exists(targetPath);
        } catch (InvalidPathException | SecurityException e) {
            // If path validation fails or access is denied, consider it as non-existent
            log.debug("Path validation or access check failed for {}: {}", path, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isDirectory(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        try {
            final Path targetPath = pathValidator.validateAndNormalize(path);
            return Files.isDirectory(targetPath);
        } catch (InvalidPathException | SecurityException e) {
            // If path validation fails or access is denied, it's not a directory
            log.debug("Path validation or access check failed for {}: {}", path, e.getMessage());
            return false;
        }
    }

    @Override
    public FileMetadata getMetadata(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        final Path targetPath = pathValidator.validateAndNormalize(path);

        // Check if file or directory exists
        if (!Files.exists(targetPath)) {
            throw new FileNotFoundException(path);
        }

        try {
            final BasicFileAttributes attrs = Files.readAttributes(targetPath, BasicFileAttributes.class);

            if (attrs.isDirectory()) {
                return FileMetadata.builder().path(path).size(0).directory(true)
                        .createdAt(attrs.creationTime().toInstant()).modifiedAt(attrs.lastModifiedTime().toInstant())
                        .build();
            }

            if (!attrs.isRegularFile()) {
                throw new InvalidPathException(path, "Not a regular file or directory");
            }

            // Detect MIME type based on file content and extension
            final String mimeType = MimeTypeDetector.detectMimeType(targetPath);

            return FileMetadata.builder().path(path).size(attrs.size()).createdAt(attrs.creationTime().toInstant())
                    .modifiedAt(attrs.lastModifiedTime().toInstant()).mimeType(mimeType).build();
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to read metadata for file: " + path, e);
        }
    }

    @Override
    public List<String> list(String directory) {
        checkState();
        Objects.requireNonNull(directory, "Directory cannot be null");

        final Path targetPath = pathValidator.validateAndNormalize(directory);

        // Check if directory exists
        if (!Files.exists(targetPath)) {
            throw new FileNotFoundException(directory);
        }

        // Check if it's a directory
        if (!Files.isDirectory(targetPath)) {
            throw new InvalidPathException(directory, "Not a directory");
        }

        try {
            final List<String> entries = new ArrayList<>();
            final Path basePath = config.getBasePath();

            try (Stream<Path> stream = Files.list(targetPath)) {
                stream.forEach(p -> {
                    // Convert to relative path from base
                    final Path relativePath = basePath.relativize(p);
                    entries.add(relativePath.toString().replace('\\', '/'));
                });
            }

            return entries;
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to list directory: " + directory, e);
        }
    }

    @Override
    public List<String> listRecursive(String directory) {
        checkState();
        Objects.requireNonNull(directory, "Directory cannot be null");

        final Path targetPath = pathValidator.validateAndNormalize(directory);

        // Check if directory exists
        if (!Files.exists(targetPath)) {
            throw new FileNotFoundException(directory);
        }

        // Check if it's a directory
        if (!Files.isDirectory(targetPath)) {
            throw new InvalidPathException(directory, "Not a directory");
        }

        try {
            final List<String> files = new ArrayList<>();
            final Path basePath = config.getBasePath();

            try (Stream<Path> stream = Files.walk(targetPath)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    // Convert to relative path from base
                    final Path relativePath = basePath.relativize(p);
                    files.add(relativePath.toString().replace('\\', '/'));
                });
            }

            return files;
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL,
                    "Failed to list directory recursively: " + directory, e);
        }
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        checkState();
        Objects.requireNonNull(sourcePath, "Source path cannot be null");
        Objects.requireNonNull(destinationPath, "Destination path cannot be null");

        final Path source = pathValidator.validateAndNormalize(sourcePath);
        final Path destination = pathValidator.validateAndNormalize(destinationPath);

        // Check if source exists
        if (!Files.exists(source)) {
            throw new FileNotFoundException(sourcePath);
        }

        // Check if source is a regular file
        if (!Files.isRegularFile(source)) {
            throw new InvalidPathException(sourcePath, "Not a regular file");
        }

        // Check if destination exists when overwrite=false
        if (!overwrite && Files.exists(destination)) {
            throw new FileAlreadyExistsException(destinationPath);
        }

        // Create parent directories for destination if needed
        createParentDirectories(destination);

        try {
            // Perform copy
            if (overwrite) {
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(source, destination);
            }
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL,
                    "Failed to copy file from " + sourcePath + " to " + destinationPath, e);
        }
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        checkState();
        Objects.requireNonNull(sourcePath, "Source path cannot be null");
        Objects.requireNonNull(destinationPath, "Destination path cannot be null");

        final Path source = pathValidator.validateAndNormalize(sourcePath);
        final Path destination = pathValidator.validateAndNormalize(destinationPath);

        // Check if source exists
        if (!Files.exists(source)) {
            throw new FileNotFoundException(sourcePath);
        }

        // Check if source is a regular file
        if (!Files.isRegularFile(source)) {
            throw new InvalidPathException(sourcePath, "Not a regular file");
        }

        // Check if destination exists when overwrite=false
        if (!overwrite && Files.exists(destination)) {
            throw new FileAlreadyExistsException(destinationPath);
        }

        // Create parent directories for destination if needed
        createParentDirectories(destination);

        // Attempt atomic move first, fall back to non-atomic if not supported
        try {
            performAtomicMove(source, destination, overwrite);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("Atomic move not supported, falling back to standard move for {} to {}", sourcePath,
                    destinationPath);
            performNonAtomicMove(source, destination, overwrite, sourcePath, destinationPath);
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL,
                    "Failed to move file from " + sourcePath + " to " + destinationPath, e);
        }
    }

    /**
     * Performs an atomic move operation.
     *
     * @param source
     *            Source file path
     * @param destination
     *            Destination file path
     * @param overwrite
     *            Whether to overwrite existing destination
     * @throws IOException
     *             If move operation fails
     */
    private void performAtomicMove(Path source, Path destination, boolean overwrite) throws IOException {
        if (overwrite) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } else {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * Performs a non-atomic move operation as fallback. Used when atomic move is not supported by the file system.
     *
     * @param source
     *            Source file path
     * @param destination
     *            Destination file path
     * @param overwrite
     *            Whether to overwrite existing destination
     * @param sourcePath
     *            Original source path string (for error messages)
     * @param destinationPath
     *            Original destination path string (for error messages)
     */
    private void performNonAtomicMove(Path source, Path destination, boolean overwrite, String sourcePath,
            String destinationPath) {
        try {
            if (overwrite) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination);
            }
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL,
                    "Failed to move file from " + sourcePath + " to " + destinationPath, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * When a {@code maxFileSize} is configured the returned stream enforces the cap: a write that would push the file
     * past the limit throws {@link InsufficientStorageException} and no bytes from that write are persisted, mirroring
     * {@link #write}. Because the caller owns the stream lifecycle, any bytes accepted before the overflow (always
     * within the cap) remain in the partially written file — the streaming API does not delete it on rejection.
     */
    @Override
    public OutputStream openOutputStream(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        final Path targetPath = pathValidator.validateAndNormalize(path);

        // Create parent directories if configured to do so
        createParentDirectories(targetPath);

        try {
            final OutputStream buffered = new BufferedOutputStream(
                    Files.newOutputStream(targetPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    config.getBufferSize());
            // Enforce maxFileSize on the streaming path too. Without this wrapper a caller could write past the
            // configured cap through the raw stream, bypassing the guarantee write() provides (see issue #9).
            return SizeLimitedOutputStream.wrap(buffered, config.getMaxFileSize());
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to open output stream for: " + path, e);
        }
    }

    @Override
    public InputStream openInputStream(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        final Path targetPath = pathValidator.validateAndNormalize(path);

        // Check if file exists
        if (!Files.exists(targetPath)) {
            throw new FileNotFoundException(path);
        }

        // Check if it's a regular file
        if (!Files.isRegularFile(targetPath)) {
            throw new InvalidPathException(path, "Not a regular file");
        }

        try {
            return new BufferedInputStream(
                    Files.newInputStream(targetPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                    config.getBufferSize());
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to open input stream for: " + path, e);
        }
    }

    @Override
    public String getWorkingDirectory() {
        return config.getBasePath().toString();
    }

    @Override
    public BackendStatus getStatus() {
        if (closed) {
            return BackendStatus.disconnected(BackendType.LOCAL, "VirtualFileSystem is closed");
        }
        if (!initialized) {
            return BackendStatus.unknown(BackendType.LOCAL);
        }

        // Check if base path is still accessible
        try {
            final Path basePath = config.getBasePath();
            if (!Files.exists(basePath) || !Files.isWritable(basePath)) {
                return BackendStatus.disconnected(BackendType.LOCAL, "Base path is not accessible or not writable");
            }
            return BackendStatus.connected(BackendType.LOCAL);
        } catch (Exception e) {
            return BackendStatus.disconnected(BackendType.LOCAL, "Error checking base path: " + e.getMessage());
        }
    }

    @Override
    public void createDirectory(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        final Path resolved = pathValidator.validateAndNormalize(path);
        final Path basePath = config.getBasePath();

        // Root directory already exists — no-op
        if (resolved.equals(basePath)) {
            return;
        }

        // If something exists at this path, check whether it's a directory
        if (Files.exists(resolved)) {
            if (!Files.isDirectory(resolved)) {
                throw new FileAlreadyExistsException(path);
            }
            // Already a directory — idempotent no-op
            return;
        }

        try {
            Files.createDirectories(resolved);
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to create directory: " + path, e);
        }
    }

    @Override
    public void deleteRecursive(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        final Path resolved = pathValidator.validateAndNormalize(path);
        final Path basePath = config.getBasePath();

        // Prevent deletion of VFS root
        if (resolved.equals(basePath)) {
            throw new VirtualFileSystemException("Cannot delete VFS root directory");
        }

        if (!Files.exists(resolved)) {
            throw new FileNotFoundException(path);
        }

        // Single file — delete directly
        if (Files.isRegularFile(resolved)) {
            try {
                Files.delete(resolved);
            } catch (IOException e) {
                throw new BackendConnectionException(BackendType.LOCAL, "Failed to delete file: " + path, e);
            }
            return;
        }

        // Directory — walk and delete post-order
        final AtomicInteger failureCount = new AtomicInteger(0);
        try {
            Files.walkFileTree(resolved, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            try {
                                Files.delete(file);
                            } catch (IOException e) {
                                log.warn("Failed to delete file during recursive delete: {}", file, e);
                                failureCount.incrementAndGet();
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            log.warn("Failed to access file during recursive delete: {}", file, exc);
                            failureCount.incrementAndGet();
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                            try {
                                Files.delete(dir);
                            } catch (IOException e) {
                                log.warn("Failed to delete directory during recursive delete: {}", dir, e);
                                failureCount.incrementAndGet();
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL,
                    "Failed to walk directory for recursive delete: " + path, e);
        }

        if (failureCount.get() > 0) {
            throw new VirtualFileSystemException(
                    "Recursive delete partially failed: " + failureCount.get() + " items could not be deleted");
        }
    }

    @Override
    public List<String> search(String directory, String pattern, int maxResults) {
        checkState();
        Objects.requireNonNull(directory, "Directory cannot be null");
        Objects.requireNonNull(pattern, "Pattern cannot be null");
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1, got: " + maxResults);
        }

        final Path dirPath = pathValidator.validateAndNormalize(directory);

        if (!Files.exists(dirPath)) {
            throw new FileNotFoundException(directory);
        }
        if (!Files.isDirectory(dirPath)) {
            throw new InvalidPathException(directory, "Not a directory");
        }

        final String sanitized = SearchPatterns.sanitize(pattern);
        if (sanitized.isEmpty()) {
            return List.of();
        }

        final PathMatcher matcher = dirPath.getFileSystem().getPathMatcher("glob:" + sanitized);
        final List<String> results = new ArrayList<>();
        final Path basePath = config.getBasePath();

        try {
            Files.walkFileTree(dirPath, EnumSet.noneOf(FileVisitOption.class), MAX_SEARCH_DEPTH,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (matcher.matches(file.getFileName())) {
                                final String relativePath = basePath.relativize(file).toString().replace('\\', '/');
                                results.add(relativePath);
                                if (results.size() >= maxResults) {
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            log.warn("Failed to access file during search: {}", file, exc);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to search directory: " + directory, e);
        }

        return results;
    }

    @Override
    public FileSystemUsage getUsageSummary() {
        checkState();

        final Path basePath = config.getBasePath();
        if (!Files.exists(basePath)) {
            return FileSystemUsage.builder().totalSize(0).fileCount(0).directoryCount(0).build();
        }

        return walkUsage(basePath);
    }

    @Override
    public FileSystemUsage getUsageSummary(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");

        final Path dirPath = pathValidator.validateAndNormalize(path);

        // The root gets the lenient treatment above: a workspace whose base directory has not been created yet has
        // zero usage rather than a missing directory, and callers scoping to the root expect the same answer they
        // would get from the no-arg overload.
        if (dirPath.equals(config.getBasePath())) {
            return getUsageSummary();
        }
        if (!Files.exists(dirPath)) {
            throw new FileNotFoundException(path);
        }
        if (!Files.isDirectory(dirPath)) {
            throw new InvalidPathException(path, "Not a directory");
        }

        return walkUsage(dirPath);
    }

    /**
     * Walks one subtree and totals it. The root of the walk is never counted as a directory — usage describes what a
     * directory contains, not the directory itself.
     *
     * @param root
     *            Existing directory to summarize
     * @return Usage totals for that subtree
     */
    private FileSystemUsage walkUsage(Path root) {
        final AtomicLong totalSize = new AtomicLong(0);
        final AtomicLong fileCount = new AtomicLong(0);
        final AtomicLong directoryCount = new AtomicLong(0);

        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            fileCount.incrementAndGet();
                            totalSize.addAndGet(attrs.size());
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(root)) {
                                directoryCount.incrementAndGet();
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            log.warn("Failed to access file during usage summary: {}", file, exc);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to calculate usage summary", e);
        }

        return FileSystemUsage.builder().totalSize(totalSize.get()).fileCount(fileCount.get())
                .directoryCount(directoryCount.get()).build();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            // Local filesystem has no resources to clean up
            // This method exists for consistency with other backends
        }
    }

    private void checkState() {
        if (closed) {
            throw new IllegalStateException("VirtualFileSystem is closed");
        }
        if (!initialized) {
            throw new IllegalStateException("VirtualFileSystem not initialized. Call initialize() first.");
        }
    }

    /**
     * Creates parent directories for the given path if configured to do so.
     *
     * @param targetPath
     *            The path whose parent directories should be created
     */
    private void createParentDirectories(Path targetPath) {
        if (config.isCreateDirectories()) {
            final Path parentDir = targetPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                try {
                    Files.createDirectories(parentDir);
                } catch (IOException e) {
                    throw new BackendConnectionException(BackendType.LOCAL,
                            "Failed to create parent directories for: " + targetPath, e);
                }
            }
        }
    }

}
