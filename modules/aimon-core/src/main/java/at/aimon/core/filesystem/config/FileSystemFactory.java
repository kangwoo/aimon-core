package at.aimon.core.filesystem.config;

import java.util.Objects;

import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

/**
 * Standalone factory for creating one {@link VirtualFileSystem} directly. Supports Local, GridFS, and S3 backends.
 *
 * <p>
 * <b>Nothing in the framework calls this class.</b> An agent runtime does not get its file system here: the
 * bootstrap layer (<code>aimon-bootstrap</code>) builds one <i>per runtime</i> from its own file-system spec —
 * either a caller-supplied instance, a caller-supplied factory keyed by {@code AgentRuntimeId}, or, by default, a
 * {@code LocalFileSystem} rooted at a per-runtime subtree of the workspace. An application embedding AIMON should
 * configure that spec rather than call this factory, or it loses the per-agent and per-tenant sandboxing the
 * subtree layout provides. This class is for code that wants a single file system and nothing else.
 *
 * <p>
 * The GridFS and S3 backends are reached reflectively because {@code aimon-core} deliberately does not depend on
 * {@code aimon-filesystem-gridfs} / {@code aimon-filesystem-s3}; a missing module surfaces at call time as
 * {@link IllegalStateException} rather than at compile time. The local backend is <b>not</b> in that situation —
 * it ships inside this module — so it is constructed directly and the class names stay compiler-checked.
 */
public final class FileSystemFactory {

    /**
     * Creates a VirtualFileSystem instance for local filesystem.
     *
     * @param basePath
     *            Base directory path
     * @return LocalFileSystem instance
     */
    public static VirtualFileSystem createLocalFileSystem(String basePath) {
        Objects.requireNonNull(basePath, "Base path cannot be null");

        // Constructed directly, unlike the two backends below: LocalFileSystem lives in this module, so it cannot
        // be absent and there is no "filesystem-local" artifact to advise anyone to add. Naming it as a string
        // would only move a compiler error to run time.
        return new LocalFileSystem(new LocalFileSystemConfig(basePath));
    }

    /**
     * Creates a VirtualFileSystem instance for MongoDB GridFS.
     *
     * @param connectionString
     *            MongoDB connection string
     * @param databaseName
     *            Database name
     * @return GridFSFileSystem instance
     */
    public static VirtualFileSystem createGridFSFileSystem(String connectionString, String databaseName) {
        Objects.requireNonNull(connectionString, "Connection string cannot be null");
        Objects.requireNonNull(databaseName, "Database name cannot be null");

        try {
            final Class<?> configClass = Class.forName("at.aimon.filesystem.core.gridfs.GridFSConfig");
            final Class<?> fsClass = Class.forName("at.aimon.filesystem.core.gridfs.GridFSFileSystem");

            final Object config = configClass.getConstructor(String.class, String.class).newInstance(connectionString,
                    databaseName);
            return (VirtualFileSystem) fsClass.getConstructor(configClass).newInstance(config);

        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to create GridFSFileSystem. Make sure filesystem-gridfs is on the classpath.", e);
        }
    }

    /**
     * Creates a VirtualFileSystem instance for AWS S3.
     *
     * @param bucketName
     *            S3 bucket name
     * @param accessKey
     *            AWS access key
     * @param secretKey
     *            AWS secret key
     * @return S3FileSystem instance
     */
    public static VirtualFileSystem createS3FileSystem(String bucketName, String accessKey, String secretKey) {
        Objects.requireNonNull(bucketName, "Bucket name cannot be null");
        Objects.requireNonNull(accessKey, "Access key cannot be null");
        Objects.requireNonNull(secretKey, "Secret key cannot be null");

        try {
            final Class<?> configClass = Class.forName("at.aimon.filesystem.core.s3.S3Config");
            final Class<?> fsClass = Class.forName("at.aimon.filesystem.core.s3.S3FileSystem");

            final Object config = configClass.getConstructor(String.class, String.class, String.class)
                    .newInstance(bucketName, accessKey, secretKey);
            return (VirtualFileSystem) fsClass.getConstructor(configClass).newInstance(config);

        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to create S3FileSystem. Make sure filesystem-s3 is on the classpath.", e);
        }
    }

    /**
     * Creates a VirtualFileSystem instance based on backend type and properties.
     *
     * @param backendType
     *            Backend type
     * @param properties
     *            Configuration properties (varies by backend type)
     * @return VirtualFileSystem instance
     */
    public static VirtualFileSystem createFileSystem(BackendType backendType, String... properties) {
        Objects.requireNonNull(backendType, "Backend type cannot be null");

        if (BackendType.LOCAL.equals(backendType)) {
            if (properties.length < 1) {
                throw new IllegalArgumentException("LOCAL backend requires basePath");
            }
            return createLocalFileSystem(properties[0]);
        } else if (BackendType.GRIDFS.equals(backendType)) {
            if (properties.length < 2) {
                throw new IllegalArgumentException("GRIDFS backend requires connectionString and databaseName");
            }
            return createGridFSFileSystem(properties[0], properties[1]);
        } else if (BackendType.S3.equals(backendType)) {
            if (properties.length < 3) {
                throw new IllegalArgumentException("S3 backend requires bucketName, accessKey, and secretKey");
            }
            return createS3FileSystem(properties[0], properties[1], properties[2]);
        } else {
            throw new IllegalArgumentException("Unknown backend type: " + backendType);
        }
    }

    /**
     * Creates a VirtualFileSystem instance from environment variables. Reads: FILESYSTEM_BACKEND, FILESYSTEM_*
     * properties
     *
     * @return VirtualFileSystem instance
     */
    public static VirtualFileSystem createFromEnvironment() {
        String backendStr = System.getenv("FILESYSTEM_BACKEND");
        if (backendStr == null) {
            backendStr = System.getProperty("filesystem.backend", "LOCAL");
        }

        final BackendType backend = BackendType.of(backendStr.toUpperCase());

        if (BackendType.LOCAL.equals(backend)) {
            final String basePath = getEnvOrProperty("FILESYSTEM_BASE_PATH", "filesystem.base.path", "/tmp/aimon-fs");
            return createLocalFileSystem(basePath);
        } else if (BackendType.GRIDFS.equals(backend)) {
            final String mongoConnection = getEnvOrProperty("FILESYSTEM_MONGO_CONNECTION",
                    "filesystem.mongo.connection", "mongodb://localhost:27017");
            // "aimon", not "at/aimon": MongoDB rejects '/' in a database name, so the old default made this branch
            // fail on every unconfigured run.
            final String mongoDatabase = getEnvOrProperty("FILESYSTEM_MONGO_DATABASE", "filesystem.mongo.database",
                    "aimon");
            return createGridFSFileSystem(mongoConnection, mongoDatabase);
        } else if (BackendType.S3.equals(backend)) {
            final String s3Bucket = getEnvOrProperty("FILESYSTEM_S3_BUCKET", "filesystem.s3.bucket", null);
            final String s3AccessKey = getEnvOrProperty("FILESYSTEM_S3_ACCESS_KEY", "filesystem.s3.access.key", null);
            final String s3SecretKey = getEnvOrProperty("FILESYSTEM_S3_SECRET_KEY", "filesystem.s3.secret.key", null);

            if (s3Bucket == null || s3AccessKey == null || s3SecretKey == null) {
                throw new IllegalStateException("S3 backend requires FILESYSTEM_S3_BUCKET, "
                        + "FILESYSTEM_S3_ACCESS_KEY, and FILESYSTEM_S3_SECRET_KEY");
            }

            return createS3FileSystem(s3Bucket, s3AccessKey, s3SecretKey);
        } else {
            throw new IllegalArgumentException("Unknown backend type: " + backend);
        }
    }

    private FileSystemFactory() {
        // Utility class
    }

    private static String getEnvOrProperty(String envVar, String property, String defaultValue) {
        String value = System.getenv(envVar);
        if (value == null) {
            value = System.getProperty(property, defaultValue);
        }
        return value;
    }
}
