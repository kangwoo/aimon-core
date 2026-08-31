package at.aimon.filesystem.core.gridfs;

import java.util.Objects;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Configuration for MongoDB GridFS backend. Immutable and thread-safe.
 *
 * <p>
 * The connection string is required only when {@link GridFSFileSystem} has to build its own {@code MongoClient}. An
 * application that already owns a client passes it to
 * {@link GridFSFileSystem#GridFSFileSystem(GridFSConfig, com.mongodb.client.MongoClient)} and builds its config with
 * {@link #forSharedClient(String)} instead — the live client deliberately stays <em>out</em> of this value object, so
 * that equality, {@code toString} and reuse across instances keep working.
 */
public final class GridFSConfig {
    private final String connectionString;
    private final String databaseName;
    private final String bucketName;
    private final int chunkSizeBytes;
    private final long maxFileSize;

    /** Default chunk size for GridFS (1MB). */
    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;

    /** Default bucket name. */
    public static final String DEFAULT_BUCKET_NAME = "fs";

    /** No maximum file size limit. Alias of the contract-wide {@link VirtualFileSystem#NO_MAX_FILE_SIZE}. */
    public static final long NO_MAX_FILE_SIZE = VirtualFileSystem.NO_MAX_FILE_SIZE;

    /**
     * Creates a new GridFSConfig with custom settings.
     *
     * @param connectionString
     *            MongoDB connection string (e.g., "mongodb://localhost:27017")
     * @param databaseName
     *            Database name
     * @param bucketName
     *            GridFS bucket name
     * @param chunkSizeBytes
     *            Chunk size in bytes
     */
    public GridFSConfig(String connectionString, String databaseName, String bucketName, int chunkSizeBytes) {
        this(Objects.requireNonNull(connectionString, "Connection string cannot be null"), databaseName, bucketName,
                chunkSizeBytes, NO_MAX_FILE_SIZE, true);
    }

    /**
     * Canonical constructor. The connection string is optional on the shared-client path, where the caller supplies a
     * ready {@code MongoClient} and nothing here would ever dial out.
     *
     * @param ownedClient
     *            marker for the caller's intent; {@code true} means this config must be able to build its own client,
     *            so a connection string is mandatory. Only ever {@code false} via {@link #forSharedClient(String)}.
     */
    private GridFSConfig(String connectionString, String databaseName, String bucketName, int chunkSizeBytes,
            long maxFileSize, boolean ownedClient) {
        Objects.requireNonNull(databaseName, "Database name cannot be null");
        Objects.requireNonNull(bucketName, "Bucket name cannot be null");
        if (chunkSizeBytes <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        if (maxFileSize < NO_MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Max file size must be -1 (no limit) or positive");
        }
        this.connectionString = ownedClient
                ? Objects.requireNonNull(connectionString, "Connection string cannot be null")
                : connectionString;
        this.databaseName = databaseName;
        this.bucketName = bucketName;
        this.chunkSizeBytes = chunkSizeBytes;
        this.maxFileSize = maxFileSize;
    }

    /**
     * Creates a config for a filesystem that will be handed an externally managed {@code MongoClient}, with the
     * default bucket name and chunk size. No connection string is needed — and none is stored, so this config cannot
     * accidentally open a second connection pool to the same cluster.
     *
     * @param databaseName
     *            Database name
     * @return A config whose {@link #getConnectionString()} is {@code null}
     */
    public static GridFSConfig forSharedClient(String databaseName) {
        return forSharedClient(databaseName, DEFAULT_BUCKET_NAME, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Creates a config for a filesystem that will be handed an externally managed {@code MongoClient}.
     *
     * @param databaseName
     *            Database name
     * @param bucketName
     *            GridFS bucket name
     * @param chunkSizeBytes
     *            Chunk size in bytes
     * @return A config whose {@link #getConnectionString()} is {@code null}
     */
    public static GridFSConfig forSharedClient(String databaseName, String bucketName, int chunkSizeBytes) {
        return forSharedClient(databaseName, bucketName, chunkSizeBytes, NO_MAX_FILE_SIZE);
    }

    /**
     * Creates a config for a filesystem that will be handed an externally managed {@code MongoClient}, with a per-file
     * size cap.
     *
     * @param databaseName
     *            Database name
     * @param bucketName
     *            GridFS bucket name
     * @param chunkSizeBytes
     *            Chunk size in bytes
     * @param maxFileSize
     *            Maximum file size in bytes, or {@link #NO_MAX_FILE_SIZE} for no limit
     * @return A config whose {@link #getConnectionString()} is {@code null}
     */
    public static GridFSConfig forSharedClient(String databaseName, String bucketName, int chunkSizeBytes,
            long maxFileSize) {
        return new GridFSConfig(null, databaseName, bucketName, chunkSizeBytes, maxFileSize, false);
    }

    /**
     * Creates a new GridFSConfig with default bucket name and chunk size.
     *
     * @param connectionString
     *            MongoDB connection string
     * @param databaseName
     *            Database name
     */
    public GridFSConfig(String connectionString, String databaseName) {
        this(connectionString, databaseName, DEFAULT_BUCKET_NAME, DEFAULT_CHUNK_SIZE);
    }

    private GridFSConfig(Builder builder) {
        this(Objects.requireNonNull(builder.connectionString, "Connection string cannot be null"), builder.databaseName,
                builder.bucketName, builder.chunkSizeBytes, builder.maxFileSize, true);
    }

    /**
     * Creates a new builder for GridFSConfig.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the MongoDB connection string, or {@code null} for a {@link #forSharedClient(String)} config whose
     *         client is supplied from outside
     */
    public String getConnectionString() {
        return connectionString;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getBucketName() {
        return bucketName;
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    /**
     * @return the per-file cap in bytes, or {@link #NO_MAX_FILE_SIZE} when uncapped
     */
    public long getMaxFileSize() {
        return maxFileSize;
    }

    /**
     * Check if maximum file size limit is enabled.
     *
     * @return true if max file size is set (not -1), false otherwise
     */
    public boolean hasMaxFileSize() {
        return maxFileSize != NO_MAX_FILE_SIZE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GridFSConfig that = (GridFSConfig) o;
        return chunkSizeBytes == that.chunkSizeBytes && maxFileSize == that.maxFileSize
                && Objects.equals(connectionString, that.connectionString)
                && Objects.equals(databaseName, that.databaseName) && Objects.equals(bucketName, that.bucketName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connectionString, databaseName, bucketName, chunkSizeBytes, maxFileSize);
    }

    @Override
    public String toString() {
        return "GridFSConfig{" + "connectionString='" + maskConnectionString(connectionString) + '\''
                + ", databaseName='" + databaseName + '\'' + ", bucketName='" + bucketName + '\'' + ", chunkSizeBytes="
                + chunkSizeBytes + ", maxFileSize=" + maxFileSize + '}';
    }

    private String maskConnectionString(String connString) {
        if (connString == null) {
            return "<shared client>";
        }
        // Mask password in connection string for security
        if (connString.contains("@")) {
            int atIndex = connString.indexOf("@");
            int slashIndex = connString.indexOf("//");
            if (slashIndex != -1 && slashIndex < atIndex) {
                return connString.substring(0, slashIndex + 2) + "***@" + connString.substring(atIndex + 1);
            }
        }
        return connString;
    }

    /** Builder for GridFSConfig. */
    public static final class Builder {
        private String connectionString;
        private String databaseName;
        private String bucketName = DEFAULT_BUCKET_NAME;
        private int chunkSizeBytes = DEFAULT_CHUNK_SIZE;
        private long maxFileSize = NO_MAX_FILE_SIZE;

        private Builder() {
        }

        /**
         * Sets the MongoDB connection string.
         *
         * @param connectionString
         *            MongoDB connection string (e.g., "mongodb://localhost:27017")
         * @return This builder
         */
        public Builder connectionString(String connectionString) {
            this.connectionString = connectionString;
            return this;
        }

        /**
         * Sets the database name.
         *
         * @param databaseName
         *            Database name
         * @return This builder
         */
        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        /**
         * Sets the GridFS bucket name.
         *
         * @param bucketName
         *            Bucket name
         * @return This builder
         * @throws NullPointerException
         *             if bucketName is null
         */
        public Builder bucketName(String bucketName) {
            this.bucketName = Objects.requireNonNull(bucketName, "Bucket name cannot be null");
            return this;
        }

        /**
         * Sets the chunk size in bytes.
         *
         * @param chunkSizeBytes
         *            Chunk size in bytes
         * @return This builder
         */
        public Builder chunkSizeBytes(int chunkSizeBytes) {
            this.chunkSizeBytes = chunkSizeBytes;
            return this;
        }

        /**
         * Sets the maximum file size limit. A write that exceeds it is rejected with
         * {@code InsufficientStorageException} and leaves the path holding whatever it held before.
         *
         * @param maxFileSize
         *            Maximum file size in bytes, or {@link #NO_MAX_FILE_SIZE} for no limit
         * @return This builder
         */
        public Builder maxFileSize(long maxFileSize) {
            this.maxFileSize = maxFileSize;
            return this;
        }

        /**
         * Builds the GridFSConfig.
         *
         * @return A new GridFSConfig instance
         * @throws NullPointerException
         *             if connectionString or databaseName is null
         * @throws IllegalArgumentException
         *             if chunkSizeBytes is not positive, or maxFileSize is negative and not
         *             {@link #NO_MAX_FILE_SIZE}
         */
        public GridFSConfig build() {
            return new GridFSConfig(this);
        }
    }
}
