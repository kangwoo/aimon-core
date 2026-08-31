package at.aimon.filesystem.core.s3;

import java.util.Objects;

import at.aimon.core.filesystem.VirtualFileSystem;

/** Configuration for AWS S3 backend. Immutable and thread-safe. */
public final class S3Config {
    private final String bucketName;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final String endpoint; // Optional, for S3-compatible services
    private final long maxFileSize;

    /** Default AWS region. */
    public static final String DEFAULT_REGION = "us-east-1";

    /** No maximum file size limit. Alias of the contract-wide {@link VirtualFileSystem#NO_MAX_FILE_SIZE}. */
    public static final long NO_MAX_FILE_SIZE = VirtualFileSystem.NO_MAX_FILE_SIZE;

    /**
     * Creates a new S3Config with custom settings and a per-file size cap.
     *
     * @param bucketName
     *            S3 bucket name
     * @param region
     *            AWS region
     * @param accessKey
     *            AWS access key
     * @param secretKey
     *            AWS secret key
     * @param endpoint
     *            Optional endpoint (for S3-compatible services like MinIO)
     * @param maxFileSize
     *            Maximum file size in bytes, or {@link #NO_MAX_FILE_SIZE} for no limit
     */
    public S3Config(String bucketName, String region, String accessKey, String secretKey, String endpoint,
            long maxFileSize) {
        Objects.requireNonNull(bucketName, "Bucket name cannot be null");
        Objects.requireNonNull(region, "Region cannot be null");
        Objects.requireNonNull(accessKey, "Access key cannot be null");
        Objects.requireNonNull(secretKey, "Secret key cannot be null");
        if (maxFileSize < NO_MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Max file size must be -1 (no limit) or positive");
        }
        this.bucketName = bucketName;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
        this.maxFileSize = maxFileSize;
    }

    /**
     * Creates a new S3Config with custom settings.
     *
     * @param bucketName
     *            S3 bucket name
     * @param region
     *            AWS region
     * @param accessKey
     *            AWS access key
     * @param secretKey
     *            AWS secret key
     * @param endpoint
     *            Optional endpoint (for S3-compatible services like MinIO)
     */
    public S3Config(String bucketName, String region, String accessKey, String secretKey, String endpoint) {
        this(bucketName, region, accessKey, secretKey, endpoint, NO_MAX_FILE_SIZE);
    }

    /**
     * Creates a new S3Config for standard AWS S3.
     *
     * @param bucketName
     *            S3 bucket name
     * @param region
     *            AWS region
     * @param accessKey
     *            AWS access key
     * @param secretKey
     *            AWS secret key
     */
    public S3Config(String bucketName, String region, String accessKey, String secretKey) {
        this(bucketName, region, accessKey, secretKey, null);
    }

    /**
     * Creates a new S3Config with default region.
     *
     * @param bucketName
     *            S3 bucket name
     * @param accessKey
     *            AWS access key
     * @param secretKey
     *            AWS secret key
     */
    public S3Config(String bucketName, String accessKey, String secretKey) {
        this(bucketName, DEFAULT_REGION, accessKey, secretKey, null);
    }

    private S3Config(Builder builder) {
        this(builder.bucketName, builder.region, builder.accessKey, builder.secretKey, builder.endpoint,
                builder.maxFileSize);
    }

    /**
     * Creates a new builder for S3Config.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getBucketName() {
        return bucketName;
    }

    public String getRegion() {
        return region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    /**
     * @return the per-file cap in bytes, or {@link #NO_MAX_FILE_SIZE} when uncapped
     */
    public long getMaxFileSize() {
        return maxFileSize;
    }

    /** 커스텀 엔드포인트 설정 여부를 반환한다. */
    public boolean hasCustomEndpoint() {
        return endpoint != null && !endpoint.isEmpty();
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
        S3Config s3Config = (S3Config) o;
        return maxFileSize == s3Config.maxFileSize && Objects.equals(bucketName, s3Config.bucketName)
                && Objects.equals(region, s3Config.region) && Objects.equals(accessKey, s3Config.accessKey)
                && Objects.equals(secretKey, s3Config.secretKey) && Objects.equals(endpoint, s3Config.endpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucketName, region, accessKey, secretKey, endpoint, maxFileSize);
    }

    @Override
    public String toString() {
        return "S3Config{" + "bucketName='" + bucketName + '\'' + ", region='" + region + '\'' + ", accessKey='"
                + maskKey(accessKey) + '\'' + ", secretKey='" + maskKey(secretKey) + '\'' + ", endpoint='" + endpoint
                + '\'' + ", maxFileSize=" + maxFileSize + '}';
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 4) {
            return "***";
        }
        return key.substring(0, 4) + "***";
    }

    /** Builder for S3Config. */
    public static final class Builder {
        private String bucketName;
        private String region = DEFAULT_REGION;
        private String accessKey;
        private String secretKey;
        private String endpoint;
        private long maxFileSize = NO_MAX_FILE_SIZE;

        private Builder() {
        }

        /**
         * Sets the S3 bucket name.
         *
         * @param bucketName
         *            S3 bucket name (required)
         * @return This builder
         */
        public Builder bucketName(String bucketName) {
            this.bucketName = bucketName;
            return this;
        }

        /**
         * Sets the AWS region.
         *
         * @param region
         *            AWS region (default: us-east-1)
         * @return This builder
         */
        public Builder region(String region) {
            this.region = region;
            return this;
        }

        /**
         * Sets the AWS access key.
         *
         * @param accessKey
         *            AWS access key (required)
         * @return This builder
         */
        public Builder accessKey(String accessKey) {
            this.accessKey = accessKey;
            return this;
        }

        /**
         * Sets the AWS secret key.
         *
         * @param secretKey
         *            AWS secret key (required)
         * @return This builder
         */
        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        /**
         * Sets the endpoint for S3-compatible services (e.g., MinIO, LocalStack).
         *
         * @param endpoint
         *            Custom endpoint URL (optional)
         * @return This builder
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Sets the maximum file size limit. A write that exceeds it is rejected with
         * {@code InsufficientStorageException} and nothing is uploaded, so the key keeps whatever it held before.
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
         * Builds the S3Config instance.
         *
         * @return Configured S3Config
         * @throws NullPointerException
         *             if required fields are null
         * @throws IllegalArgumentException
         *             if maxFileSize is negative and not {@link #NO_MAX_FILE_SIZE}
         */
        public S3Config build() {
            return new S3Config(this);
        }
    }
}
