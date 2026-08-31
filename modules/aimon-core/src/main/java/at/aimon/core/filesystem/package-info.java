/**
 * Virtual filesystem abstraction providing unified file operations across multiple storage backends.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides a storage-agnostic filesystem API that supports local filesystem, MongoDB GridFS, and
 * S3-compatible storage. All implementations provide consistent behavior and adhere to the same contract defined by
 * {@link at.aimon.core.filesystem.VirtualFileSystem}.
 *
 * <h2>Core Components</h2>
 *
 * <h3>Primary Interface</h3>
 * <ul>
 * <li>{@link at.aimon.core.filesystem.VirtualFileSystem} - Main interface for all file operations. Supports:
 * <ul>
 * <li>Basic operations: read, write, delete, exists</li>
 * <li>Directory operations: list, listRecursive</li>
 * <li>File operations: copy, move</li>
 * <li>Streaming: openInputStream, openOutputStream</li>
 * <li>Metadata: getMetadata, isDirectory</li>
 * <li>Backend management: initialize, getStatus, close</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <h3>Value Objects</h3>
 * <ul>
 * <li>{@link at.aimon.core.filesystem.FileMetadata} - Immutable file metadata (size, timestamps, MIME type, custom
 * metadata). Built using the Builder pattern for flexible construction.</li>
 * <li>{@link at.aimon.core.filesystem.BackendType} - Type-safe backend identifier. Supports predefined types (LOCAL,
 * GRIDFS, S3) and custom types via {@code of(String)}. Uses caching to ensure identity comparison works.</li>
 * <li>{@link at.aimon.core.filesystem.BackendStatus} - Immutable backend connectivity status with connection state,
 * timestamp, and error message.</li>
 * </ul>
 *
 * <h3>Utilities</h3>
 * <ul>
 * <li>{@link at.aimon.core.filesystem.PathValidator} - Thread-safe path validation and normalization. Provides security
 * features:
 * <ul>
 * <li>Directory traversal attack prevention (../ detection)</li>
 * <li>Windows reserved filename rejection (CON, PRN, AUX, NUL, COM1-9, LPT1-9)</li>
 * <li>Invalid character filtering (null bytes, control characters)</li>
 * <li>Path length limit enforcement (4096 characters)</li>
 * <li>Base path containment verification</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <h3>Factory</h3>
 * <ul>
 * <li>{@link at.aimon.core.filesystem.config.FileSystemFactory} - Creates a single VirtualFileSystem directly.
 * Supports:
 * <ul>
 * <li>Local filesystem: {@code createLocalFileSystem(basePath)}</li>
 * <li>MongoDB GridFS: {@code createGridFSFileSystem(connectionString, databaseName)}</li>
 * <li>AWS S3: {@code createS3FileSystem(bucketName, accessKey, secretKey)}</li>
 * <li>Environment-based: {@code createFromEnvironment()}</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <p>
 * <b>This factory is not how an agent gets its file system, and the examples below are not the embedding path.</b>
 * An {@code AgentRuntime} receives one built <i>per runtime</i> by the bootstrap layer
 * (<code>aimon-bootstrap</code>), rooted at a per-runtime subtree of the workspace so that agents and tenants
 * cannot read each other's files. An application embedding AIMON configures that spec — with a shared instance, a
 * factory keyed by {@code AgentRuntimeId}, or the default local subtree — instead of calling this factory. What
 * follows is for code that wants one file system on its own.
 *
 * <h2>Exception Hierarchy</h2>
 *
 * <p>
 * All exceptions extend {@link at.aimon.core.filesystem.exception.VirtualFileSystemException}:
 *
 * <ul>
 * <li>{@link at.aimon.core.filesystem.exception.FileNotFoundException} - File not found (includes path and backend
 * type)</li>
 * <li>{@link at.aimon.core.filesystem.exception.FileAlreadyExistsException} - File already exists (includes path and
 * backend type)</li>
 * <li>{@link at.aimon.core.filesystem.exception.InvalidPathException} - Invalid or insecure path (includes path and
 * reason)</li>
 * <li>{@link at.aimon.core.filesystem.exception.BackendConnectionException} - Backend connection failure (includes
 * backend type)</li>
 * <li>{@link at.aimon.core.filesystem.exception.InsufficientStorageException} - Storage quota exceeded</li>
 * <li>{@link at.aimon.core.filesystem.exception.ConfigurationException} - Invalid configuration</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Local Filesystem</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem fs = FileSystemFactory.createLocalFileSystem("/tmp/myapp");
 *     fs.initialize();
 *
 *     // Write file
 *     fs.write("hello.txt", "Hello, World!");
 *
 *     // Read file
 *     try (InputStream in = fs.read("hello.txt")) {
 *         String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
 *         System.out.println(content); // "Hello, World!"
 *     }
 *
 *     // Get metadata
 *     FileMetadata metadata = fs.getMetadata("hello.txt");
 *     System.out.println("Size: " + metadata.getSize() + " bytes");
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>MongoDB GridFS</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem fs = FileSystemFactory.createGridFSFileSystem("mongodb://localhost:27017", "mydb");
 *     fs.initialize();
 *
 *     // Large file streaming
 *     try (InputStream in = Files.newInputStream(Paths.get("large-file.bin"))) {
 *         fs.write("uploads/large-file.bin", in, Files.size(Paths.get("large-file.bin")));
 *     }
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>AWS S3</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem fs = FileSystemFactory.createS3FileSystem("my-bucket", "AKIAIOSFODNN7EXAMPLE",
 *             "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
 *     fs.initialize();
 *
 *     // Check backend status
 *     BackendStatus status = fs.getStatus();
 *     if (status.isAvailable()) {
 *         fs.write("data/file.txt", content);
 *     }
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>Environment-based Configuration</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Reads FILESYSTEM_BACKEND environment variable
 *     // and creates appropriate filesystem instance
 *     VirtualFileSystem fs = FileSystemFactory.createFromEnvironment();
 *     fs.initialize();
 *
 *     // Use fs...
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>Path Validation</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     PathValidator validator = new PathValidator("/app/data");
 *
 *     // Valid path
 *     Path path = validator.validateAndNormalize("users/john/profile.json");
 *     // Result: /app/data/users/john/profile.json
 *
 *     // Invalid: directory traversal
 *     try {
 *         validator.validateAndNormalize("../../etc/passwd");
 *     } catch (InvalidPathException e) {
 *         // Path traversal detected (security violation)
 *     }
 *
 *     // Invalid: Windows reserved name
 *     try {
 *         validator.validateAndNormalize("COM1.txt");
 *     } catch (InvalidPathException e) {
 *         // Path contains Windows reserved filename: COM1.txt
 *     }
 * }
 * </pre>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Interface Segregation:</b> Single comprehensive interface rather than multiple small ones, but with logical
 * grouping</li>
 * <li><b>Dependency Inversion:</b> Core package defines abstractions, implementation modules depend on core</li>
 * <li><b>Open/Closed Principle:</b> Extensible through BackendType.of() and custom implementations</li>
 * <li><b>Single Responsibility:</b> Each class has one clear purpose (validation, metadata, status, etc.)</li>
 * <li><b>Liskov Substitution:</b> All implementations fully substitute VirtualFileSystem interface</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <ul>
 * <li><b>Immutable:</b> FileMetadata, BackendType, BackendStatus are fully immutable and thread-safe</li>
 * <li><b>Stateless:</b> PathValidator is stateless with immutable basePath</li>
 * <li><b>Implementation-dependent:</b> VirtualFileSystem thread-safety varies by implementation (see implementation
 * docs)</li>
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 *
 * <ul>
 * <li><b>Streaming:</b> All operations support streaming to handle large files efficiently</li>
 * <li><b>Caching:</b> BackendType instances are cached to reduce object allocation</li>
 * <li><b>Lazy initialization:</b> Backend connections established only when initialize() is called</li>
 * <li><b>Fast operations:</b> exists(), isDirectory(), getStatus() are optimized for performance</li>
 * </ul>
 *
 * <h2>Security Features</h2>
 *
 * <ul>
 * <li>Directory traversal attack prevention (../ detection)</li>
 * <li>Windows reserved filename rejection</li>
 * <li>Path length limit enforcement</li>
 * <li>Base path containment verification</li>
 * <li>Invalid character filtering</li>
 * <li>Cross-platform path normalization</li>
 * </ul>
 *
 * <h2>Related Packages</h2>
 *
 * <ul>
 * <li>{@code at.aimon.core.filesystem.impl.local} - Local filesystem implementation</li>
 * <li>{@code at.aimon.filesystem.gridfs} - MongoDB GridFS implementation (aimon-filesystem-gridfs module)</li>
 * <li>{@code at.aimon.filesystem.s3} - AWS S3 implementation (aimon-filesystem-s3 module)</li>
 * </ul>
 *
 * @see at.aimon.core.filesystem.VirtualFileSystem
 * @see at.aimon.core.filesystem.config.FileSystemFactory
 * @see at.aimon.core.filesystem.PathValidator
 * @since 1.0.0
 */
package at.aimon.core.filesystem;
