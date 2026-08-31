package at.aimon.core.filesystem;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import at.aimon.core.filesystem.exception.BackendConnectionException;
import at.aimon.core.filesystem.exception.ConfigurationException;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InsufficientStorageException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;

/**
 * Unified interface for file operations across different storage backends. All implementations must provide consistent
 * behavior across local filesystem, MongoDB GridFS, and S3-compatible storage.
 *
 * <p>
 * Implementations: LocalFileSystem, GridFSFileSystem, S3FileSystem
 *
 * <h2>Security Requirements</h2>
 *
 * <p>
 * All implementations <b>must</b> enforce the following security constraints internally. These are mandatory contracts
 * that protect against unauthorized file access regardless of the storage backend.
 *
 * <h3>Path Traversal Prevention</h3>
 * <p>
 * Implementations must prevent directory traversal attacks that attempt to access files outside the allowed storage
 * boundary. Specifically:
 * <ul>
 * <li>Paths containing {@code ../} or {@code ..\\} sequences must be rejected or normalized and validated against the
 * base path boundary after normalization.</li>
 * <li>After path normalization, the resolved path must remain within the configured base path (working directory). Any
 * resolved path that escapes the base path must throw {@link InvalidPathException}.</li>
 * <li>Null bytes ({@code \0}) and control characters in paths must be rejected.</li>
 * </ul>
 * <p>
 * For local filesystem implementations, use {@link PathValidator} which provides these checks. For remote backends
 * (GridFS, S3) where paths are treated as opaque keys, path traversal is inherently prevented by the backend, but
 * implementations should still normalize and sanitize paths to prevent confusion and ensure consistent behavior.
 *
 * <h3>Symbolic Link Blocking</h3>
 * <p>
 * For local filesystem implementations, symbolic links must not be followed when resolving paths. A symlink inside the
 * base path could point to a location outside the base path, effectively bypassing path traversal prevention.
 * Implementations must:
 * <ul>
 * <li>Detect symbolic links during path resolution and reject them by throwing {@link InvalidPathException}. All
 * symbolic links are rejected regardless of their target — even symlinks pointing to locations inside the base path are
 * not allowed. This strict policy eliminates any ambiguity and prevents subtle escape vectors.</li>
 * <li>Use {@link java.nio.file.LinkOption#NOFOLLOW_LINKS} where applicable for file existence checks to detect symlinks
 * without following them.</li>
 * <li>Walk each path component from the base path down to the target path, checking each existing component for
 * symbolic links before proceeding.</li>
 * </ul>
 * <p>
 * Remote backends (GridFS, S3) do not have symbolic links, so this requirement is not applicable to them.
 *
 * <h2>Directory Semantics</h2>
 *
 * <p>
 * Backends disagree about whether directories physically exist. A local filesystem has real inodes; GridFS and S3
 * store a flat key space where a directory would otherwise be nothing but a side effect of some file's path — it
 * could not be empty, and it would vanish when its last file did. That difference must not reach callers: an agent
 * that runs {@code createDirectory("out")} and then lists its parent has to see {@code out} on every backend.
 * Implementations therefore <b>must</b> provide the following behavior, materializing whatever markers their storage
 * model requires.
 *
 * <ul>
 * <li><b>Empty directories exist.</b> After {@link #createDirectory(String)}, {@link #exists(String)} and
 * {@link #isDirectory(String)} return {@code true} for that path and {@link #getMetadata(String)} returns metadata
 * with {@link FileMetadata#isDirectory()} set, even while the directory holds nothing.</li>
 * <li><b>Implied directories exist too.</b> Writing {@code docs/a.txt} makes {@code docs} a directory (FR-006), and
 * it is indistinguishable from one created explicitly.</li>
 * <li><b>{@link #list(String)} returns files and immediate subdirectories</b> — including subdirectories that hold
 * no files. {@link #listRecursive(String)} returns <b>files only</b>; directories are not repeated at depth.</li>
 * <li><b>Returned paths are relative to the VFS root</b>, not to the {@code directory} argument. This holds for
 * {@link #list(String)}, {@link #listRecursive(String)} and {@link #search(String, String, int)} alike: listing
 * {@code "docs"} yields {@code "docs/a.txt"}, never {@code "a.txt"}.</li>
 * <li><b>Listing a missing path throws</b> {@link FileNotFoundException}, and listing a regular file throws
 * {@link InvalidPathException}. Neither returns an empty list.</li>
 * <li><b>{@link #delete(String)} removes an empty directory</b> and refuses a populated one with a
 * {@link VirtualFileSystemException}. Deleting a populated tree is {@link #deleteRecursive(String)}'s job.</li>
 * <li><b>Files and directories may not collide.</b> {@link #createDirectory(String)} throws
 * {@link FileAlreadyExistsException} when a regular file occupies the path, and a write whose path names an existing
 * directory is rejected rather than shadowing it.</li>
 * </ul>
 *
 * <h2>Maximum File Size</h2>
 *
 * <p>
 * A backend <b>may</b> be configured with a per-file byte cap — {@code maxFileSize} on its configuration object,
 * defaulting to {@link #NO_MAX_FILE_SIZE}. It is the one bound an agent's workspace can be given without a quota
 * system: a single runaway write cannot fill the store. Where a backend offers the option at all, the following holds,
 * so that moving a workspace from one backend to another does not change which writes succeed.
 *
 * <ul>
 * <li><b>Both write paths enforce it.</b> {@link #write(String, InputStream, long)} and the stream handed back by
 * {@link #openOutputStream(String)} share one enforcement point ({@link SizeLimitedOutputStream}). A caller cannot
 * escape the cap by choosing the streaming API.</li>
 * <li><b>The count is of bytes actually written</b>, never of the declared {@code contentLength}. A stream that
 * under-declares its length, or declares {@code -1}, is capped just the same; a declared length that already exceeds
 * the cap may additionally be rejected up front, before any byte is stored.</li>
 * <li><b>Rejection is {@link InsufficientStorageException}</b>, thrown from {@code write} or from the {@code write} /
 * {@code close} call on the stream that crosses the line — not from {@link #openOutputStream(String)} itself, which
 * has no bytes to judge yet.</li>
 * <li><b>No truncated file is left behind by {@link #write(String, InputStream, long)}.</b> The rejected write leaves
 * the path either absent or holding its previous content; what it must never leave is the prefix that fit.</li>
 * <li><b>The streaming path's leftovers are backend-specific.</b> The caller owns that stream's lifecycle, so bytes
 * accepted before the overflow may remain visible (a local file keeps them) or may be discarded wholesale (GridFS
 * aborts the upload, so the previous revision stands). Each backend states which it does.</li>
 * </ul>
 *
 * @see PathValidator
 * @see SizeLimitedOutputStream
 */
public interface VirtualFileSystem extends AutoCloseable {

    /**
     * The {@code maxFileSize} value that means "no per-file cap", shared by every backend configuration that offers
     * the setting so the sentinel cannot drift between them.
     */
    long NO_MAX_FILE_SIZE = -1;

    // File content operations

    /**
     * Write file content to the specified path. Creates parent directories automatically if they don't exist (FR-006).
     * Uses streaming to handle large files without loading entire content into memory (FR-016).
     *
     * <p>
     * <b>Content Length Handling:</b>
     * <ul>
     * <li>If {@code contentLength >= 0}: The implementation will read exactly this many bytes from the stream.
     * Implementations may use this information for optimization (e.g., pre-allocation, progress tracking).</li>
     * <li>If {@code contentLength == -1}: The length is unknown. The implementation will read until the stream ends
     * (EOF). This may be less efficient for some backends (e.g., S3 requires buffering for multipart upload), but is
     * guaranteed to work correctly.</li>
     * <li>If {@code contentLength < -1}: Invalid value. Behavior is undefined and may throw
     * IllegalArgumentException.</li>
     * </ul>
     *
     * @param path
     *            Path where file should be written (relative or absolute)
     * @param content
     *            Input stream containing file content (must not be null)
     * @param contentLength
     *            Length of content in bytes, or -1 if unknown. Implementations must handle -1 correctly by reading
     *            until EOF.
     * @throws FileAlreadyExistsException
     *             If file exists and overwrite not specified
     * @throws InvalidPathException
     *             If path is invalid
     * @throws InsufficientStorageException
     *             If storage quota exceeded, or if the content exceeds the backend's configured
     *             {@link #NO_MAX_FILE_SIZE maximum file size} — measured on the bytes actually read, not on
     *             {@code contentLength}. The path is left absent or holding its previous content; no truncated prefix
     *             is stored.
     * @throws BackendConnectionException
     *             If backend is unavailable
     * @throws IllegalArgumentException
     *             If contentLength &lt; -1
     */
    void write(String path, InputStream content, long contentLength);

    /**
     * Write file content from a byte array. Convenience method for small files that fit in memory. For large files, use
     * {@link #write(String, InputStream, long)} with streaming.
     *
     * @param path
     *            Path where file should be written
     * @param content
     *            File content as byte array (must not be null)
     * @throws FileAlreadyExistsException
     *             If file exists and overwrite not specified
     * @throws InvalidPathException
     *             If path is invalid
     * @throws InsufficientStorageException
     *             If storage quota exceeded
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    default void write(String path, byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        write(path, new ByteArrayInputStream(content), content.length);
    }

    /**
     * Write file content from a string using UTF-8 encoding. Convenience method for text files. For binary files or
     * large files, use {@link #write(String, InputStream, long)} with streaming.
     *
     * @param path
     *            Path where file should be written
     * @param content
     *            File content as string (must not be null)
     * @throws FileAlreadyExistsException
     *             If file exists and overwrite not specified
     * @throws InvalidPathException
     *             If path is invalid
     * @throws InsufficientStorageException
     *             If storage quota exceeded
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    default void write(String path, String content) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Read file content from the specified path. Returns input stream for streaming large files without loading entire
     * content into memory (FR-015). Caller is responsible for closing the returned stream.
     *
     * @param path
     *            Path to file to read
     * @return InputStream for reading file content
     * @throws FileNotFoundException
     *             If file does not exist
     * @throws InvalidPathException
     *             If path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    InputStream read(String path);

    /**
     * Delete file at the specified path. Removes file permanently (no recycle bin/trash).
     *
     * <p>
     * An <b>empty</b> directory is deleted as well; a populated one is refused with a
     * {@link VirtualFileSystemException}. Use {@link #deleteRecursive(String)} to remove a tree.
     *
     * @param path
     *            Path to file or empty directory to delete
     * @throws FileNotFoundException
     *             If file does not exist
     * @throws InvalidPathException
     *             If path is invalid
     * @throws VirtualFileSystemException
     *             If the path is a non-empty directory
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    void delete(String path);

    // File existence and metadata

    /**
     * Check if file exists at the specified path (FR-009). Fast operation (metadata query only, no content access).
     *
     * @param path
     *            Path to check
     * @return true if file exists, false otherwise
     * @throws InvalidPathException
     *             If path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    boolean exists(String path);

    /**
     * Check if path points to a directory. Fast operation (metadata query only, no content access).
     *
     * @param path
     *            Path to check
     * @return true if path is a directory, false if it's a file or doesn't exist
     * @throws InvalidPathException
     *             If path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    boolean isDirectory(String path);

    /**
     * Retrieve metadata for a file or directory at the specified path (FR-007). Returns immutable metadata object with
     * size, timestamps, MIME type, and directory flag. Does not access file content (metadata query only).
     *
     * @param path
     *            Path to file or directory
     * @return FileMetadata object with file or directory information
     * @throws FileNotFoundException
     *             If file or directory does not exist
     * @throws InvalidPathException
     *             If path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    FileMetadata getMetadata(String path);

    // Directory operations

    /**
     * List files and directories in the specified directory (non-recursive) (FR-008). Returns both files and
     * subdirectories in the immediate directory, empty subdirectories included. Use {@link #isDirectory(String)} to
     * distinguish between files and directories.
     *
     * @param directory
     *            Directory path to list
     * @return List of file and directory paths relative to the <b>VFS root</b> — listing {@code "docs"} yields
     *         {@code "docs/a.txt"}, not {@code "a.txt"}
     * @throws FileNotFoundException
     *             If directory does not exist
     * @throws InvalidPathException
     *             If path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    List<String> list(String directory);

    /**
     * List all files in directory tree recursively (FR-008). Returns all files in directory and all subdirectories at
     * any depth. Unlike {@link #list(String)} this returns <b>files only</b> — directories are not listed at depth.
     *
     * @param directory
     *            Root directory path to list
     * @return List of all file paths relative to the <b>VFS root</b> — listing {@code "docs"} yields
     *         {@code "docs/sub/a.txt"}, not {@code "sub/a.txt"}
     * @throws FileNotFoundException
     *             If directory does not exist
     * @throws InvalidPathException
     *             If path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    List<String> listRecursive(String directory);

    // Copy and move operations

    /**
     * Copy file from source to destination (FR-010). Source file remains unchanged. Creates destination file with same
     * content. Creates parent directories for destination if needed (FR-006).
     *
     * @param sourcePath
     *            Source file path
     * @param destinationPath
     *            Destination file path
     * @param overwrite
     *            Whether to overwrite destination if it exists (defaults to false per FR-011)
     * @throws FileNotFoundException
     *             If source file does not exist
     * @throws FileAlreadyExistsException
     *             If destination exists and overwrite is false
     * @throws InvalidPathException
     *             If either path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    void copy(String sourcePath, String destinationPath, boolean overwrite);

    /**
     * Move file from source to destination (FR-010). Source file is deleted after successful copy to destination.
     *
     * <p>
     * <b>Atomicity Guarantees:</b>
     * <ul>
     * <li><b>Local filesystem</b>: Atomic when source and destination are on the same filesystem (uses native rename
     * operation). Non-atomic when crossing filesystem boundaries (copy then delete).</li>
     * <li><b>GridFS and S3</b>: Non-atomic. Implemented as copy-then-delete. If the operation fails between copy and
     * delete, the source file may remain (idempotent retry safe).</li>
     * <li><b>Failure handling</b>: If copy succeeds but delete fails, implementations may leave both files present.
     * Callers should be prepared to handle partial failures.</li>
     * </ul>
     *
     * @param sourcePath
     *            Source file path
     * @param destinationPath
     *            Destination file path
     * @param overwrite
     *            Whether to overwrite destination if it exists (defaults to false per FR-011)
     * @throws FileNotFoundException
     *             If source file does not exist
     * @throws FileAlreadyExistsException
     *             If destination exists and overwrite is false
     * @throws InvalidPathException
     *             If either path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    void move(String sourcePath, String destinationPath, boolean overwrite);

    // Streaming operations

    /**
     * Open output stream for writing to file. Caller is responsible for closing the stream. File created when stream is
     * closed. Overwrites existing file if present.
     *
     * <p>
     * When the backend is configured with a {@link #NO_MAX_FILE_SIZE maximum file size}, the returned stream enforces
     * it: the {@code write} call that would push the file past the cap throws {@link InsufficientStorageException}
     * instead, and none of that call's bytes are stored. The exception surfaces from the stream, not from this method,
     * which has no content to judge yet. What becomes of the bytes accepted before the overflow is stated by each
     * backend — they may remain as a shorter file, or the whole upload may be discarded.
     *
     * @param path
     *            Path to file to write
     * @return OutputStream for writing
     * @throws InvalidPathException
     *             If path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    OutputStream openOutputStream(String path);

    /**
     * Open input stream for reading from file. Caller is responsible for closing the stream. Same behavior as read()
     * operation.
     *
     * @param path
     *            Path to file to read
     * @return InputStream for reading
     * @throws FileNotFoundException
     *             If file does not exist
     * @throws InvalidPathException
     *             If path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    InputStream openInputStream(String path);

    // Extension methods for VFS File Explorer

    /**
     * Create a directory at the specified path. Creates intermediate directories if they don't exist (mkdir -p
     * behavior). If the directory already exists, this method does nothing (idempotent). If the VFS root path is given,
     * this method does nothing (root already exists).
     *
     * @param path
     *            Directory path to create
     * @throws FileAlreadyExistsException
     *             If a non-directory file already exists at the path
     * @throws InvalidPathException
     *             If path is invalid
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    default void createDirectory(String path) {
        throw new UnsupportedOperationException("createDirectory not supported by this backend");
    }

    /**
     * Delete a file or directory recursively. If a file path is given, deletes the single file. If a directory path is
     * given, deletes the directory and all its contents.
     *
     * <p>
     * <b>Safety:</b> VFS root directory cannot be deleted. Partial failures are handled best-effort: remaining items
     * continue to be deleted, and a {@link VirtualFileSystemException} is thrown at the end if any item failed.
     *
     * @param path
     *            File or directory path to delete
     * @throws FileNotFoundException
     *             If path does not exist
     * @throws InvalidPathException
     *             If path is invalid
     * @throws VirtualFileSystemException
     *             If root deletion is attempted or partial deletion failure
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    default void deleteRecursive(String path) {
        throw new UnsupportedOperationException("deleteRecursive not supported by this backend");
    }

    /**
     * Search for files by filename glob pattern within the specified directory. Pattern matches against filenames only
     * (not full paths). Results are limited to maxResults entries.
     *
     * <p>
     * <b>Pattern rules:</b> Allowed wildcards: {@code *} (0+ chars), {@code ?} (1 char). Blocked characters
     * ({@code /\{}[]}) are removed. {@code **} is collapsed to {@code *}.
     *
     * @param directory
     *            Directory to search in
     * @param pattern
     *            Filename glob pattern (e.g., "*.json", "report-??.csv")
     * @param maxResults
     *            Maximum number of results (must be >= 1)
     * @return List of matching file paths relative to VFS root
     * @throws FileNotFoundException
     *             If directory does not exist
     * @throws InvalidPathException
     *             If directory path is invalid
     * @throws IllegalArgumentException
     *             If maxResults &lt; 1
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    default List<String> search(String directory, String pattern, int maxResults) {
        throw new UnsupportedOperationException("search not supported by this backend");
    }

    /**
     * Get usage summary for the entire VFS. Traverses the full file tree to collect statistics.
     *
     * @return Usage summary with total size, file count, and directory count
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    default FileSystemUsage getUsageSummary() {
        throw new UnsupportedOperationException("getUsageSummary not supported by this backend");
    }

    /**
     * Get usage summary for one subtree. Counts the files and directories under {@code path}, and the directory
     * itself is <b>not</b> counted — the result of scoping to the root is identical to {@link #getUsageSummary()}.
     *
     * <p>
     * The default implementation ignores {@code path} and reports the whole VFS, which is the honest answer for a
     * backend that cannot scope: over-reporting a quota is safe, under-reporting is not. Backends able to answer per
     * subtree should override it — a shared bucket carved into per-agent prefixes by
     * {@code ScopedVirtualFileSystem} is otherwise unable to tell one tenant's usage from every tenant's.
     *
     * @param path
     *            Directory to summarize; a path naming the VFS root summarizes everything
     * @return Usage summary with total size, file count, and directory count for that subtree
     * @throws FileNotFoundException
     *             If the path does not exist
     * @throws InvalidPathException
     *             If the path is invalid or names a regular file
     * @throws BackendConnectionException
     *             If backend is unavailable
     */
    default FileSystemUsage getUsageSummary(String path) {
        return getUsageSummary();
    }

    // Backend management

    /**
     * Get the working directory for this filesystem. Returns the base path or root directory where files are stored.
     * For local filesystem, this is the absolute base path. For remote backends (GridFS, S3), this typically returns
     * the root ("/").
     *
     * @return The working directory path (never null)
     */
    String getWorkingDirectory();

    /**
     * Initialize the file system backend and validate connectivity (FR-013). Must be called before any file operations.
     * Validates backend connectivity and creates necessary resources. Idempotent (safe to call multiple times).
     *
     * @throws BackendConnectionException
     *             If cannot connect to backend
     * @throws ConfigurationException
     *             If configuration is invalid
     */
    void initialize();

    /**
     * Get current backend status and connectivity. Returns current backend status without throwing exceptions (returns
     * UNKNOWN state on error). May perform lightweight connectivity check. Fast operation (cached status acceptable).
     *
     * @return BackendStatus object with current status
     */
    BackendStatus getStatus();

    /**
     * Close file system and release resources. Closes all open streams and connections. FileSystem unusable after
     * close. Idempotent (safe to call multiple times). Should be called in try-with-resources or finally block.
     *
     * @throws VirtualFileSystemException
     *             If error occurs during cleanup
     */
    @Override
    void close();
}
