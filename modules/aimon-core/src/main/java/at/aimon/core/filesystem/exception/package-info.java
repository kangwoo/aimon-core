/**
 * Exception hierarchy for virtual filesystem operations.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package contains all exceptions that can be thrown during virtual filesystem operations. All exceptions extend
 * {@link at.aimon.core.filesystem.exception.VirtualFileSystemException}, which extends
 * {@link at.aimon.core.base.exception.AimonException}.
 *
 * <h2>Exception Hierarchy</h2>
 *
 * <pre>
 * AimonException (at.aimon.core.base.exception)
 *   └─ VirtualFileSystemException
 *       ├─ FileNotFoundException
 *       ├─ FileAlreadyExistsException
 *       ├─ InvalidPathException
 *       ├─ BackendConnectionException
 *       ├─ InsufficientStorageException
 *       └─ ConfigurationException
 * </pre>
 *
 * <h2>Exception Types</h2>
 *
 * <h3>File Operation Exceptions</h3>
 * <ul>
 * <li>{@link at.aimon.core.filesystem.exception.FileNotFoundException} - Thrown when attempting to access a file that
 * does not exist. Includes:
 * <ul>
 * <li>Path that was not found</li>
 * <li>Backend type where the file was not found (optional)</li>
 * </ul>
 * </li>
 * <li>{@link at.aimon.core.filesystem.exception.FileAlreadyExistsException} - Thrown when attempting to create a file
 * that already exists (for non-overwrite operations). Includes:
 * <ul>
 * <li>Path that already exists</li>
 * <li>Backend type where the file already exists (optional)</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <h3>Path Validation Exceptions</h3>
 * <ul>
 * <li>{@link at.aimon.core.filesystem.exception.InvalidPathException} - Thrown when a file path is invalid, insecure,
 * or contains illegal characters. Common causes:
 * <ul>
 * <li>Directory traversal attempts (../ or absolute paths outside base path)</li>
 * <li>Windows reserved filenames (CON, PRN, AUX, NUL, COM1-9, LPT1-9)</li>
 * <li>Invalid characters (null bytes, control characters, etc.)</li>
 * <li>Path length exceeds maximum (4096 characters)</li>
 * <li>Empty or whitespace-only paths</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <h3>Backend Exceptions</h3>
 * <ul>
 * <li>{@link at.aimon.core.filesystem.exception.BackendConnectionException} - Thrown when backend connection fails or
 * backend is unavailable. Includes:
 * <ul>
 * <li>Backend type that failed to connect (optional)</li>
 * <li>Underlying cause of the connection failure</li>
 * </ul>
 * </li>
 * <li>{@link at.aimon.core.filesystem.exception.InsufficientStorageException} - Thrown when storage is full or quota is
 * exceeded.</li>
 * <li>{@link at.aimon.core.filesystem.exception.ConfigurationException} - Thrown when configuration is invalid or
 * required properties are missing.</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Handling Specific Exceptions</h3>
 *
 * <pre>
 * {@code
 * try {
 *     fs.read("nonexistent.txt");
 * } catch (FileNotFoundException e) {
 *     System.err.println("File not found: " + e.getPath());
 *     System.err.println("Backend: " + e.getBackendType());
 * } catch (BackendConnectionException e) {
 *     System.err.println("Backend unavailable: " + e.getBackendType());
 *     System.err.println("Cause: " + e.getCause().getMessage());
 * }
 * }
 * </pre>
 *
 * <h3>Handling Path Validation Errors</h3>
 *
 * <pre>
 * {@code
 * try {
 *     fs.write("../../etc/passwd", content);
 * } catch (InvalidPathException e) {
 *     System.err.println("Invalid path: " + e.getMessage());
 *     // Message: "Invalid path '../../etc/passwd': Path traversal detected (security violation)"
 * }
 * }
 * </pre>
 *
 * <h3>Generic Exception Handling</h3>
 *
 * <pre>
 * {@code
 * try {
 *     fs.copy("source.txt", "dest.txt", false);
 * } catch (FileNotFoundException e) {
 *     // Source file doesn't exist
 *     System.err.println("Source not found: " + e.getPath());
 * } catch (FileAlreadyExistsException e) {
 *     // Destination already exists and overwrite is false
 *     System.err.println("Destination exists: " + e.getPath());
 * } catch (VirtualFileSystemException e) {
 *     // Any other filesystem error
 *     System.err.println("Filesystem error: " + e.getMessage());
 * }
 * }
 * </pre>
 *
 * <h3>Retrying on Connection Errors</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     int maxRetries = 3;
 *     for (int i = 0; i &lt; maxRetries; i++) {
 *         try {
 *             fs.write("data.txt", content);
 *             break; // Success
 *         } catch (BackendConnectionException e) {
 *             if (i == maxRetries - 1) {
 *                 throw e; // Max retries exceeded
 *             }
 *             Thread.sleep(1000 * (i + 1)); // Exponential backoff
 *         }
 *     }
 * }
 * </pre>
 *
 * <h2>Exception Properties</h2>
 *
 * <h3>Common to All Exceptions</h3>
 * <ul>
 * <li><b>message:</b> Human-readable error message</li>
 * <li><b>cause:</b> Underlying exception (if any)</li>
 * <li><b>stackTrace:</b> Full stack trace for debugging</li>
 * </ul>
 *
 * <h3>Exception-Specific Properties</h3>
 * <ul>
 * <li><b>FileNotFoundException:</b> path, backendType</li>
 * <li><b>FileAlreadyExistsException:</b> path, backendType</li>
 * <li><b>InvalidPathException:</b> path (in message), reason (in message)</li>
 * <li><b>BackendConnectionException:</b> backendType</li>
 * </ul>
 *
 * <h2>Design Considerations</h2>
 *
 * <ul>
 * <li><b>Unchecked exceptions:</b> All exceptions are unchecked (extend RuntimeException) for cleaner API</li>
 * <li><b>Contextual information:</b> Exceptions include relevant context (path, backend type) for better debugging</li>
 * <li><b>Hierarchical:</b> Common base exception allows generic catch blocks while preserving type information</li>
 * <li><b>Serializable:</b> All exceptions include serialVersionUID for proper serialization</li>
 * <li><b>Backward compatible:</b> New constructors added without removing old ones (deprecated where appropriate)</li>
 * </ul>
 *
 * <h2>Best Practices</h2>
 *
 * <ul>
 * <li>Catch specific exceptions when you can handle them differently (e.g., FileNotFoundException vs
 * BackendConnectionException)</li>
 * <li>Use generic VirtualFileSystemException catch block as fallback</li>
 * <li>Log exception details including path and backend type for troubleshooting</li>
 * <li>Don't catch and ignore exceptions - at minimum, log them</li>
 * <li>For retry logic, only retry BackendConnectionException (not path validation or file not found errors)</li>
 * </ul>
 *
 * @see at.aimon.core.filesystem.VirtualFileSystem
 * @see at.aimon.core.filesystem.PathValidator
 * @since 1.0.0
 */
package at.aimon.core.filesystem.exception;
