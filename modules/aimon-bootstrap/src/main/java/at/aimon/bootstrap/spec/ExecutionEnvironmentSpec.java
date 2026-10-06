package at.aimon.bootstrap.spec;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import at.aimon.core.environment.ExecutionEnvironmentProvider;

/**
 * Declares where each runtime's executions run: the filesystem the model's tools see and the shell {@code Bash} runs
 * in (execution-environment design §4, §9).
 *
 * <p>
 * <b>One provider per stack.</b> Every runtime of the stack resolves its environment from the same provider, which
 * lives as long as the stack and picks the workspace from the request's runtime id. It is not closed when a tenant
 * runtime is evicted — a background command that runtime started may still be running in its shell. What the provider
 * holds for one runtime it releases when the stack closes the handle it got from
 * {@link ExecutionEnvironmentProvider#bindRuntime}, which the stack calls for each runtime it builds.
 *
 * <p>
 * By default that provider is a local one with a workspace per runtime (see {@link FileSystemSpec}): the control
 * store sits at the workspace's {@code .aimon/} and is hidden from the file tools, and skill files are staged into
 * {@code .aimon-staged/}. {@link #provider(Supplier)} replaces it with a caller-supplied provider — a sandbox, for
 * instance — that the stack builds once, owns, and closes when it closes; the control store is then still the one
 * {@link FileSystemSpec} describes. {@link #shared(ExecutionEnvironmentProvider)} is the same with the ownership
 * left with the caller: the stack uses the provider, binds its runtimes to it, and never closes it ("whoever creates
 * it closes it").
 */
public final class ExecutionEnvironmentSpec {

    /** Default limit on one skill directory's staged size: 50 MB. */
    public static final long DEFAULT_MAX_STAGED_BYTES = 50L * 1024 * 1024;

    private final Supplier<ExecutionEnvironmentProvider> providerSupplier;
    private final ExecutionEnvironmentProvider sharedProvider;
    private final long maxStagedBytes;
    private final boolean controlWritable;
    private final boolean contentSearch;
    private final boolean contentHashStamps;
    private final Duration backgroundCommandTimeout;

    private ExecutionEnvironmentSpec(Builder builder) {
        this.contentHashStamps = builder.contentHashStamps;
        this.providerSupplier = builder.providerSupplier;
        this.sharedProvider = builder.sharedProvider;
        if (builder.maxStagedBytes < 0) {
            throw new IllegalArgumentException("maxStagedBytes must be >= 0, got: " + builder.maxStagedBytes);
        }
        this.maxStagedBytes = builder.maxStagedBytes;
        this.controlWritable = builder.controlWritable;
        this.contentSearch = builder.contentSearch;
        this.backgroundCommandTimeout = builder.backgroundCommandTimeout;
    }

    /** @return the default: one local provider with a workspace per runtime */
    public static ExecutionEnvironmentSpec defaults() {
        return builder().build();
    }

    /**
     * One caller-supplied provider for every runtime, built and owned by the stack.
     *
     * <p>
     * The supplier is called once, when the stack is assembled. The provider it returns picks each execution's
     * workspace from {@code EnvironmentRequest.agentRuntimeId()}, learns of runtimes coming and going through
     * {@code bindRuntime}, and — when it is {@link AutoCloseable} — is closed when the stack closes.
     *
     * @param provider
     *            builds the provider (must not be null; must not return null)
     * @return the spec
     */
    public static ExecutionEnvironmentSpec provider(Supplier<ExecutionEnvironmentProvider> provider) {
        final Builder builder = builder();
        builder.providerSupplier = Objects.requireNonNull(provider, "provider must not be null");
        return builder.build();
    }

    /**
     * One caller-owned provider for every runtime. The stack uses it, binds its runtimes to it, and never closes it.
     *
     * @param provider
     *            the provider (must not be null)
     * @return the spec
     */
    public static ExecutionEnvironmentSpec shared(ExecutionEnvironmentProvider provider) {
        final Builder builder = builder();
        builder.sharedProvider = Objects.requireNonNull(provider, "provider must not be null");
        return builder.build();
    }

    /**
     * Whether the provider comes from the caller and stays the caller's to close ({@link #shared}).
     *
     * @return {@code true} if the stack must not close the provider
     */
    public boolean isCallerOwned() {
        return sharedProvider != null;
    }

    /** @return the supplier of the stack-owned provider ({@link #provider}), or empty */
    public Optional<Supplier<ExecutionEnvironmentProvider>> getProviderSupplier() {
        return Optional.ofNullable(providerSupplier);
    }

    /** @return the caller-owned provider ({@link #shared}), or empty */
    public Optional<ExecutionEnvironmentProvider> getSharedProvider() {
        return Optional.ofNullable(sharedProvider);
    }

    /**
     * The longest a background command may run in the local provider's environments
     * ({@code ExecutionEnvironment.backgroundCommandTimeout()}). A caller-supplied provider answers for its own
     * environments; this setting does not reach it.
     *
     * @return the ceiling, or empty to leave it at {@code Bash}'s default of 24 hours
     */
    public Optional<Duration> getBackgroundCommandTimeout() {
        return Optional.ofNullable(backgroundCommandTimeout);
    }

    /** @return the local provider's limit on one skill directory's staged size */
    public long getMaxStagedBytes() {
        return maxStagedBytes;
    }

    /**
     * Whether the local provider leaves the control store ({@code .aimon/}) visible and writable to the file tools —
     * the explicit opt-in for letting the model edit its own skills. Off by default (§9.2).
     *
     * @return {@code true} to expose the control store
     */
    public boolean isControlWritable() {
        return controlWritable;
    }

    /** @return whether the local provider answers {@code Grep} with {@code rg} when it is on the PATH */
    public boolean isContentSearch() {
        return contentSearch;
    }

    /**
     * Whether the local provider's workspaces report a content hash as each file's etag, so the file tools' read
     * stamps compare content rather than size and modification time. Off by default: it costs a full read of the
     * file per stamp. It applies to the workspaces the stack builds from a workspace root; a file system the
     * application supplies ({@code FileSystemSpec}'s instance or factory) carries its own setting.
     *
     * @return {@code true} to hash file content into read stamps
     */
    public boolean isContentHashStamps() {
        return contentHashStamps;
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ExecutionEnvironmentSpec{provider="
                + (sharedProvider != null ? "shared" : providerSupplier != null ? "supplied" : "local")
                + ", maxStagedBytes=" + maxStagedBytes + ", controlWritable=" + controlWritable + ", contentSearch="
                + contentSearch + ", contentHashStamps=" + contentHashStamps + ", backgroundCommandTimeout="
                + backgroundCommandTimeout + '}';
    }

    /** Builder for {@link ExecutionEnvironmentSpec}. */
    public static final class Builder {
        private Supplier<ExecutionEnvironmentProvider> providerSupplier;
        private ExecutionEnvironmentProvider sharedProvider;
        private long maxStagedBytes = DEFAULT_MAX_STAGED_BYTES;
        private boolean controlWritable;
        private boolean contentSearch = true;
        private boolean contentHashStamps;
        private Duration backgroundCommandTimeout;

        private Builder() {
        }

        /**
         * @param maxStagedBytes
         *            the local provider's limit on one skill directory's staged size
         * @return this builder
         */
        public Builder maxStagedBytes(long maxStagedBytes) {
            this.maxStagedBytes = maxStagedBytes;
            return this;
        }

        /**
         * @param controlWritable
         *            whether to expose the control store to the file tools
         * @return this builder
         */
        public Builder controlWritable(boolean controlWritable) {
            this.controlWritable = controlWritable;
            return this;
        }

        /**
         * @param contentSearch
         *            whether to answer {@code Grep} with {@code rg} when available
         * @return this builder
         */
        public Builder contentSearch(boolean contentSearch) {
            this.contentSearch = contentSearch;
            return this;
        }

        /**
         * @param contentHashStamps
         *            whether the local provider's workspace-root workspaces hash file content into their etags, so
         *            read stamps catch a same-size rewrite on a file system whose modification time counts in whole
         *            seconds — at the price of a full read of the file per stamp (default {@code false})
         * @return this builder
         */
        public Builder contentHashStamps(boolean contentHashStamps) {
            this.contentHashStamps = contentHashStamps;
            return this;
        }

        /**
         * @param backgroundCommandTimeout
         *            the longest a background command may run in the local provider's environments (must be
         *            positive), or null for {@code Bash}'s default of 24 hours
         * @return this builder
         * @throws IllegalArgumentException
         *             if the value is zero or negative
         */
        public Builder backgroundCommandTimeout(Duration backgroundCommandTimeout) {
            if (backgroundCommandTimeout != null
                    && (backgroundCommandTimeout.isZero() || backgroundCommandTimeout.isNegative())) {
                throw new IllegalArgumentException(
                        "backgroundCommandTimeout must be positive, got: " + backgroundCommandTimeout);
            }
            this.backgroundCommandTimeout = backgroundCommandTimeout;
            return this;
        }

        /** @return the spec */
        public ExecutionEnvironmentSpec build() {
            return new ExecutionEnvironmentSpec(this);
        }
    }
}
