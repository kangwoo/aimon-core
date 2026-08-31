/**
 * Configuration and factory utilities for creating virtual filesystem instances.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides factory classes and utilities for creating and configuring
 * {@link at.aimon.core.filesystem.VirtualFileSystem} instances across different storage backends.
 *
 * <h2>Not the path an agent's file system takes</h2>
 *
 * <p>
 * <b>Nothing in the framework calls this package.</b> An {@code AgentRuntime} receives its file system from the
 * bootstrap layer (<code>aimon-bootstrap</code>), which builds one <i>per runtime</i> from its own file-system spec:
 * a caller-supplied instance, a caller-supplied factory keyed by {@code AgentRuntimeId}, or — by default — a
 * {@code LocalFileSystem} rooted at a per-runtime subtree of the workspace. An application embedding AIMON should
 * configure that spec; reaching for this package instead gives every agent and every tenant the same root and
 * loses the sandboxing the subtree layout provides.
 *
 * <p>
 * What is left here is a standalone convenience for code that wants a single file system and nothing else.
 *
 * <h2>Core Components</h2>
 *
 * <h3>FileSystemFactory</h3>
 * <p>
 * {@link at.aimon.core.filesystem.config.FileSystemFactory} creates one VirtualFileSystem directly. It supports:
 *
 * <ul>
 * <li><b>Local Filesystem:</b> {@code createLocalFileSystem(basePath)}</li>
 * <li><b>MongoDB GridFS:</b> {@code createGridFSFileSystem(connectionString, databaseName)}</li>
 * <li><b>AWS S3:</b> {@code createS3FileSystem(bucketName, accessKey, secretKey)}</li>
 * <li><b>Generic:</b> {@code createFileSystem(backendType, properties...)}</li>
 * <li><b>Environment-based:</b> {@code createFromEnvironment()}</li>
 * </ul>
 *
 * <p>
 * The GridFS and S3 backends are looked up reflectively so that {@code aimon-core} need not depend on their
 * modules; a missing module surfaces as {@link java.lang.IllegalStateException} at call time. The local backend
 * ships in this module and is constructed directly.
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Local Filesystem</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create local filesystem at /var/app/data
 *     VirtualFileSystem fs = FileSystemFactory.createLocalFileSystem("/var/app/data");
 *     fs.initialize();
 *
 *     // Use filesystem...
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
 *     // Create GridFS filesystem
 *     VirtualFileSystem fs = FileSystemFactory.createGridFSFileSystem("mongodb://localhost:27017", "myapp");
 *     fs.initialize();
 *
 *     // Use filesystem...
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
 *     // Create S3 filesystem
 *     VirtualFileSystem fs = FileSystemFactory.createS3FileSystem("my-bucket", "AKIAIOSFODNN7EXAMPLE",
 *             "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
 *     fs.initialize();
 *
 *     // Use filesystem...
 *
 *     fs.close();
 * }
 * </pre>
 *
 * <h3>Generic Factory Method</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create filesystem using BackendType
 *     BackendType type = BackendType.LOCAL;
 *     VirtualFileSystem fs = FileSystemFactory.createFileSystem(type, "/var/app/data");
 *     fs.initialize();
 *
 *     // Or with GridFS
 *     type = BackendType.GRIDFS;
 *     fs = FileSystemFactory.createFileSystem(type, "mongodb://localhost:27017", "myapp");
 * }
 * </pre>
 *
 * <h3>Environment-based Configuration</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Reads configuration from environment variables:
 *     // FILESYSTEM_BACKEND=LOCAL|GRIDFS|S3
 *     // FILESYSTEM_BASE_PATH=/var/app/data (for LOCAL)
 *     // FILESYSTEM_MONGO_CONNECTION=mongodb://... (for GRIDFS)
 *     // FILESYSTEM_MONGO_DATABASE=myapp (for GRIDFS)
 *     // FILESYSTEM_S3_BUCKET=my-bucket (for S3)
 *     // FILESYSTEM_S3_ACCESS_KEY=... (for S3)
 *     // FILESYSTEM_S3_SECRET_KEY=... (for S3)
 *
 *     VirtualFileSystem fs = FileSystemFactory.createFromEnvironment();
 *     fs.initialize();
 * }
 * </pre>
 *
 * <h2>Environment Variables</h2>
 *
 * <table border="1">
 * <caption>Supported Environment Variables</caption>
 * <tr>
 * <th>Variable</th>
 * <th>Backend</th>
 * <th>Description</th>
 * <th>Default</th>
 * </tr>
 * <tr>
 * <td>FILESYSTEM_BACKEND</td>
 * <td>All</td>
 * <td>Backend type (LOCAL, GRIDFS, S3)</td>
 * <td>LOCAL</td>
 * </tr>
 * <tr>
 * <td>FILESYSTEM_BASE_PATH</td>
 * <td>LOCAL</td>
 * <td>Base directory path</td>
 * <td>/tmp/aimon-fs</td>
 * </tr>
 * <tr>
 * <td>FILESYSTEM_MONGO_CONNECTION</td>
 * <td>GRIDFS</td>
 * <td>MongoDB connection string</td>
 * <td>mongodb://localhost:27017</td>
 * </tr>
 * <tr>
 * <td>FILESYSTEM_MONGO_DATABASE</td>
 * <td>GRIDFS</td>
 * <td>MongoDB database name</td>
 * <td>aimon</td>
 * </tr>
 * <tr>
 * <td>FILESYSTEM_S3_BUCKET</td>
 * <td>S3</td>
 * <td>S3 bucket name</td>
 * <td>(required)</td>
 * </tr>
 * <tr>
 * <td>FILESYSTEM_S3_ACCESS_KEY</td>
 * <td>S3</td>
 * <td>AWS access key</td>
 * <td>(required)</td>
 * </tr>
 * <tr>
 * <td>FILESYSTEM_S3_SECRET_KEY</td>
 * <td>S3</td>
 * <td>AWS secret key</td>
 * <td>(required)</td>
 * </tr>
 * </table>
 *
 * <p>
 * Note: System properties with equivalent names (with dots instead of underscores) are also supported as fallback. For
 * example, {@code filesystem.backend} as fallback for {@code FILESYSTEM_BACKEND}.
 *
 * <h2>Module Dependencies</h2>
 *
 * <p>
 * The factory uses reflection to create implementation instances, allowing optional dependencies:
 *
 * <ul>
 * <li><b>Local filesystem:</b> Always available (built into aimon-core)</li>
 * <li><b>GridFS:</b> Requires aimon-filesystem-gridfs module on classpath</li>
 * <li><b>S3:</b> Requires aimon-filesystem-s3 module on classpath</li>
 * </ul>
 *
 * <p>
 * If a module is not available, attempting to create a filesystem for that backend will throw
 * {@code IllegalStateException} with a descriptive error message.
 *
 * <h2>Error Handling</h2>
 *
 * <pre>
 * {@code
 * try {
 *     VirtualFileSystem fs = FileSystemFactory.createGridFSFileSystem("mongodb://localhost:27017", "myapp");
 *     fs.initialize();
 * } catch (IllegalStateException e) {
 *     // GridFS module not on classpath
 *     System.err.println("GridFS not available: " + e.getMessage());
 * } catch (ConfigurationException e) {
 *     // Invalid configuration
 *     System.err.println("Configuration error: " + e.getMessage());
 * } catch (BackendConnectionException e) {
 *     // Cannot connect to MongoDB
 *     System.err.println("Connection failed: " + e.getMessage());
 * }
 * }
 * </pre>
 *
 * <h2>Best Practices</h2>
 *
 * <ul>
 * <li>Use {@code createFromEnvironment()} for production deployments (12-factor app)</li>
 * <li>Use specific factory methods for tests (explicit configuration)</li>
 * <li>Always call {@code initialize()} before using the filesystem</li>
 * <li>Always call {@code close()} when done (use try-with-resources)</li>
 * <li>Check {@code getStatus().isAvailable()} before operations in production</li>
 * <li>Handle {@code IllegalStateException} if optional modules might be missing</li>
 * </ul>
 *
 * <h2>Security Considerations</h2>
 *
 * <ul>
 * <li>Avoid passing credentials as method parameters (use environment variables instead)</li>
 * <li>For S3, prefer IAM roles over access keys when running on AWS</li>
 * <li>For MongoDB, use connection strings with authentication</li>
 * <li>Restrict base path for local filesystem to prevent unauthorized access</li>
 * <li>Never log credentials or include them in error messages</li>
 * </ul>
 *
 * @see at.aimon.core.filesystem.VirtualFileSystem
 * @see at.aimon.core.filesystem.BackendType
 * @since 1.0.0
 */
package at.aimon.core.filesystem.config;
