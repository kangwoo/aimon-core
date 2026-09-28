package at.aimon.core.tools.artifact;

/**
 * Whether the file tools register artifacts, and how much an execution may archive into the control store when its
 * environment is not durable (execution-environment design §9.3). Immutable.
 */
public final class ArtifactPolicy {

    /** Default limit for one archived file: 50 MB. */
    public static final long DEFAULT_MAX_FILE_BYTES = 50L * 1024 * 1024;

    /** Default limit for everything one execution archives: 100 MB. */
    public static final long DEFAULT_MAX_EXECUTION_BYTES = 100L * 1024 * 1024;

    private static final ArtifactPolicy DISABLED = builder().enabled(false).build();

    private final boolean enabled;
    private final long maxFileBytes;
    private final long maxExecutionBytes;

    private ArtifactPolicy(Builder builder) {
        if (builder.maxFileBytes < 0 || builder.maxExecutionBytes < 0) {
            throw new IllegalArgumentException("Artifact limits must be non-negative");
        }
        this.enabled = builder.enabled;
        this.maxFileBytes = builder.maxFileBytes;
        this.maxExecutionBytes = builder.maxExecutionBytes;
    }

    /** @return a policy that registers no artifacts */
    public static ArtifactPolicy disabled() {
        return DISABLED;
    }

    /** @return a policy that registers artifacts with the default limits */
    public static ArtifactPolicy enabledWithDefaults() {
        return builder().enabled(true).build();
    }

    /** @return whether the file tools register artifacts */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return the largest single file archived from a non-durable environment */
    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    /** @return the most one execution archives from a non-durable environment in total */
    public long getMaxExecutionBytes() {
        return maxExecutionBytes;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ArtifactPolicy{enabled=" + enabled + ", maxFileBytes=" + maxFileBytes + ", maxExecutionBytes="
                + maxExecutionBytes + '}';
    }

    /** Builder for {@link ArtifactPolicy}. */
    public static final class Builder {
        private boolean enabled;
        private long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        private long maxExecutionBytes = DEFAULT_MAX_EXECUTION_BYTES;

        private Builder() {
        }

        /**
         * @param enabled
         *            whether artifacts are registered
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * @param maxFileBytes
         *            the per-file archive limit
         * @return this builder
         */
        public Builder maxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
            return this;
        }

        /**
         * @param maxExecutionBytes
         *            the per-execution archive limit
         * @return this builder
         */
        public Builder maxExecutionBytes(long maxExecutionBytes) {
            this.maxExecutionBytes = maxExecutionBytes;
            return this;
        }

        /** @return the policy */
        public ArtifactPolicy build() {
            return new ArtifactPolicy(this);
        }
    }
}
