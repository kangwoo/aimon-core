/**
 * Local filesystem implementation of the VirtualFileSystem interface using Java NIO.2.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides a production-ready local filesystem backend that uses standard Java NIO.2 APIs for
 * cross-platform file operations. It offers high performance through buffered I/O, thread-safe operation, and
 * comprehensive error handling.
 *
 * <h2>Core Components</h2>
 *
 * <h3>Main Implementation</h3>
 * <ul>
 * <li>{@link at.aimon.core.filesystem.impl.local.LocalFileSystem} - Thread-safe local filesystem implementation.
 * Features:
 * <ul>
 * <li>Buffered I/O with configurable buffer sizes (default: 8KB)</li>
 * <li>Atomic file move operations with automatic fallback</li>
 * <li>Comprehensive MIME type detection (45+ file types)</li>
 * <li>Automatic parent directory creation (optional)</li>
 * <li>File size limit enforcement (optional)</li>
 * <li>Cross-platform path handling (Windows/Unix)</li>
 * <li>Robust state management (initialized/closed tracking)</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <ul>
 * <li>{@link at.aimon.core.filesystem.impl.local.LocalFileSystemConfig} - Immutable configuration object. Supports:
 * <ul>
 * <li>Base path specification (automatically normalized)</li>
 * <li>Buffer size tuning (default: 8KB, customizable)</li>
 * <li>Auto-create directories flag (default: true)</li>
 * <li>Maximum file size limit (default: unlimited)</li>
 * <li>Builder pattern for flexible construction</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <h3>Performance Optimizations</h3>
 * <ul>
 * <li><b>Buffered I/O:</b> Uses BufferedInputStream/BufferedOutputStream for efficient read/write operations</li>
 * <li><b>Atomic Operations:</b> Attempts atomic file moves first, falls back to standard move if not supported</li>
 * <li><b>Streaming:</b> Supports large file operations through streaming without loading entire files into memory</li>
 * <li><b>Configurable Buffers:</b> Buffer size can be tuned based on file sizes and I/O patterns</li>
 * </ul>
 *
 * <h3>MIME Type Detection</h3>
 * <p>
 * Automatic MIME type detection using two-stage approach:
 * <ol>
 * <li>Java's built-in {@code Files.probeContentType()} for content-based detection</li>
 * <li>Fallback to extension-based lookup from comprehensive MIME type map</li>
 * </ol>
 *
 * <p>
 * Supported categories:
 * <ul>
 * <li>Text: txt, html, css, js, json, xml, csv, md</li>
 * <li>Images: jpg, png, gif, bmp, svg, ico, webp</li>
 * <li>Documents: pdf, doc, docx, xls, xlsx, ppt, pptx</li>
 * <li>Archives: zip, tar, gz, rar, 7z</li>
 * <li>Audio: mp3, wav, ogg, m4a</li>
 * <li>Video: mp4, avi, mov, wmv, flv, mkv</li>
 * <li>Binary: bin, exe, jar</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <ul>
 * <li><b>Concurrent Operations:</b> Safe for concurrent read/write operations from multiple threads</li>
 * <li><b>State Management:</b> Uses volatile fields for initialized/closed state</li>
 * <li><b>Immutable Config:</b> Configuration object is immutable and thread-safe</li>
 * <li><b>Stateless Utilities:</b> PathValidator is stateless and thread-safe</li>
 * </ul>
 *
 * <h3>Error Handling</h3>
 * <ul>
 * <li>Comprehensive null checks on all parameters</li>
 * <li>State validation before operations (initialized, not closed)</li>
 * <li>Path validation through PathValidator (security checks)</li>
 * <li>Specific exception types for different failure scenarios</li>
 * <li>Debug logging for troubleshooting</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Basic Usage</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Simple configuration
 *     LocalFileSystemConfig config = new LocalFileSystemConfig("/tmp/myapp");
 *     LocalFileSystem fs = new LocalFileSystem(config);
 *     fs.initialize();
 *
 *     // Write file
 *     try (InputStream content = new ByteArrayInputStream("Hello, World!".getBytes())) {
 *         fs.write("hello.txt", content, 13);
 *     }
 *
 *     // Read file
 *     try (InputStream in = fs.read("hello.txt")) {
 *         String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
 *         System.out.println(content); // "Hello, World!"
 *     }
 *
 *     // Copy file
 *     fs.copy("hello.txt", "backup/hello.txt", false);
 *
 *     // Move file
 *     fs.move("hello.txt", "archive/hello.txt", false);
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>Advanced Configuration with Builder</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Custom configuration using Builder pattern
 *     LocalFileSystemConfig config = LocalFileSystemConfig.builder("/data/uploads").bufferSize(16384) // 16KB buffer
 *                                                                                                     // for large
 *                                                                                                     // files
 *             .createDirectories(true) // Auto-create parent directories
 *             .maxFileSize(100 * 1024 * 1024) // 100MB limit
 *             .build();
 *
 *     LocalFileSystem fs = new LocalFileSystem(config);
 *     fs.initialize();
 *
 *     // Upload large file with size limit enforcement
 *     try (InputStream in = Files.newInputStream(uploadPath)) {
 *         fs.write("videos/large-video.mp4", in, uploadSize);
 *     } catch (InsufficientStorageException e) {
 *         System.err.println("File exceeds maximum size limit");
 *     }
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>Directory Operations</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     LocalFileSystem fs = new LocalFileSystem(new LocalFileSystemConfig("/app/data"));
 *     fs.initialize();
 *
 *     // List files in directory
 *     List&lt;String&gt; files = fs.list("uploads");
 *     files.forEach(System.out::println);
 *
 *     // List all files recursively
 *     List&lt;String&gt; allFiles = fs.listRecursive("uploads");
 *     System.out.println("Total files: " + allFiles.size());
 *
 *     // Check if path is directory
 *     if (fs.isDirectory("uploads")) {
 *         System.out.println("It's a directory");
 *     }
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>Metadata Operations</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     LocalFileSystem fs = new LocalFileSystem(new LocalFileSystemConfig("/data"));
 *     fs.initialize();
 *
 *     // Get file metadata
 *     FileMetadata metadata = fs.getMetadata("document.pdf");
 *     System.out.println("Size: " + metadata.getSize() + " bytes");
 *     System.out.println("MIME: " + metadata.getMimeType()); // "application/pdf"
 *     System.out.println("Created: " + metadata.getCreatedAt());
 *     System.out.println("Modified: " + metadata.getModifiedAt());
 *
 *     // Check file existence
 *     if (fs.exists("document.pdf")) {
 *         System.out.println("File exists");
 *     }
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>Streaming Large Files</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     LocalFileSystemConfig config = LocalFileSystemConfig.builder("/data").bufferSize(32768) // 32KB buffer for better
 *                                                                                             // throughput
 *             .build();
 *
 *     LocalFileSystem fs = new LocalFileSystem(config);
 *     fs.initialize();
 *
 *     // Stream large file write
 *     try (OutputStream out = fs.openOutputStream("large-file.bin")) {
 *         // Write data in chunks
 *         byte[] buffer = new byte[8192];
 *         int bytesRead;
 *         while ((bytesRead = sourceStream.read(buffer)) != -1) {
 *             out.write(buffer, 0, bytesRead);
 *         }
 *     }
 *
 *     // Stream large file read
 *     try (InputStream in = fs.openInputStream("large-file.bin")) {
 *         // Process data in chunks
 *         byte[] buffer = new byte[8192];
 *         int bytesRead;
 *         while ((bytesRead = in.read(buffer)) != -1) {
 *             processData(buffer, 0, bytesRead);
 *         }
 *     }
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>Backend Status Monitoring</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     LocalFileSystem fs = new LocalFileSystem(new LocalFileSystemConfig("/app/data"));
 *     fs.initialize();
 *
 *     // Check backend status
 *     BackendStatus status = fs.getStatus();
 *     if (status.isAvailable()) {
 *         System.out.println("Filesystem is operational");
 *         System.out.println("Backend: " + status.getBackendType().getName()); // "LOCAL"
 *         System.out.println("Last checked: " + status.getLastChecked());
 *     } else {
 *         System.err.println("Filesystem unavailable: " + status.getErrorMessage());
 *     }
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h2>Configuration Options</h2>
 *
 * <h3>Buffer Size Selection</h3>
 * <table border="1">
 * <tr>
 * <th>File Size</th>
 * <th>Recommended Buffer</th>
 * <th>Rationale</th>
 * </tr>
 * <tr>
 * <td>&lt; 1MB</td>
 * <td>8KB (default)</td>
 * <td>Good balance for small files</td>
 * </tr>
 * <tr>
 * <td>1MB - 10MB</td>
 * <td>16KB</td>
 * <td>Better throughput for medium files</td>
 * </tr>
 * <tr>
 * <td>&gt; 10MB</td>
 * <td>32KB - 64KB</td>
 * <td>Optimal for large files, reduces system calls</td>
 * </tr>
 * </table>
 *
 * <h3>File Size Limits</h3>
 * <ul>
 * <li><b>Unlimited (default):</b> Use {@code NO_MAX_FILE_SIZE} (-1) for no restriction</li>
 * <li><b>Application uploads:</b> Typical limits: 10MB - 100MB</li>
 * <li><b>Media files:</b> Larger limits: 500MB - 5GB</li>
 * <li><b>Enterprise systems:</b> May need multiple GB limits</li>
 * </ul>
 *
 * <h2>Best Practices</h2>
 *
 * <h3>Resource Management</h3>
 * <ul>
 * <li>Always call {@code initialize()} before using the filesystem</li>
 * <li>Always call {@code close()} when done (use try-with-resources)</li>
 * <li>Close streams returned by {@code read()}, {@code openInputStream()}, {@code openOutputStream()}</li>
 * <li>Reuse filesystem instances rather than creating new ones frequently</li>
 * </ul>
 *
 * <h3>Error Handling</h3>
 * <ul>
 * <li>Catch specific exceptions rather than generic {@code Exception}</li>
 * <li>Handle {@code FileNotFoundException} for optional file reads</li>
 * <li>Check {@code exists()} before operations if file may not exist</li>
 * <li>Use {@code getStatus()} to verify filesystem health</li>
 * </ul>
 *
 * <h3>Performance</h3>
 * <ul>
 * <li>Tune buffer size based on typical file sizes</li>
 * <li>Use streaming operations for large files (avoid {@code readAllBytes()})</li>
 * <li>Batch operations when possible (e.g., multiple writes)</li>
 * <li>Enable auto-create directories to avoid manual directory management</li>
 * </ul>
 *
 * <h3>Security</h3>
 * <ul>
 * <li>Use PathValidator to prevent directory traversal attacks</li>
 * <li>Set appropriate base path to restrict file access scope</li>
 * <li>Configure max file size to prevent disk space exhaustion</li>
 * <li>Validate user-provided paths before passing to filesystem</li>
 * </ul>
 *
 * <h2>Platform Considerations</h2>
 *
 * <h3>Cross-Platform Compatibility</h3>
 * <ul>
 * <li><b>Path Separators:</b> Automatically handles both forward slash (/) and backslash (\)</li>
 * <li><b>Path Normalization:</b> Converts all paths to canonical form</li>
 * <li><b>Case Sensitivity:</b> Respects underlying filesystem (case-sensitive on Unix, case-insensitive on
 * Windows)</li>
 * <li><b>Reserved Names:</b> PathValidator rejects Windows reserved names (CON, PRN, etc.)</li>
 * </ul>
 *
 * <h3>Filesystem Limitations</h3>
 * <ul>
 * <li><b>File Size:</b> Limited by underlying filesystem (typically 2GB - 16EB)</li>
 * <li><b>Path Length:</b> PathValidator enforces 4096 character limit</li>
 * <li><b>Atomic Move:</b> May not be supported across different filesystems/volumes</li>
 * <li><b>Permissions:</b> Subject to filesystem and OS-level permissions</li>
 * </ul>
 *
 * <h2>Testing</h2>
 *
 * <p>
 * Comprehensive test coverage includes:
 * <ul>
 * <li>Unit tests for all operations (read, write, delete, copy, move, list)</li>
 * <li>Integration tests for workflows (write-read-delete cycles)</li>
 * <li>Performance tests for large files and concurrent operations</li>
 * <li>Edge case tests (empty files, nested directories, special characters)</li>
 * <li>Error condition tests (invalid paths, missing files, closed filesystem)</li>
 * </ul>
 *
 * <h2>Related Documentation</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.filesystem} - Core filesystem abstractions and interfaces</li>
 * <li>{@link at.aimon.core.filesystem.PathValidator} - Path validation and security</li>
 * <li>{@link at.aimon.core.filesystem.VirtualFileSystem} - Main filesystem interface</li>
 * <li>{@link at.aimon.core.filesystem.FileMetadata} - File metadata representation</li>
 * </ul>
 *
 * @see at.aimon.core.filesystem.impl.local.LocalFileSystem
 * @see at.aimon.core.filesystem.impl.local.LocalFileSystemConfig
 * @since 1.0.0
 */
package at.aimon.core.filesystem.impl.local;
