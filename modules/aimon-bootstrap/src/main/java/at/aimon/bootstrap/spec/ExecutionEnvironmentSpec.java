package at.aimon.bootstrap.spec;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.environment.ExecutionEnvironmentProvider;

/**
 * Declares where each runtime's executions run: the filesystem the model's tools see and the shell {@code Bash} runs
 * in (execution-environment design §4, §9).
 *
 * <p>
 * By default the stack builds a local provider per runtime over that runtime's workspace (see {@link FileSystemSpec}):
 * the control store sits at the workspace's {@code .aimon/} and is hidden from the file tools, and skill files are
 * staged into {@code .aimon-staged/}. {@link #factory(Function)} replaces that with a caller-supplied provider per
 * runtime — a sandbox, for instance; the control store is then still the one {@link FileSystemSpec} describes. A
 * provider the factory returns that is {@link AutoCloseable} is owned by the runtime's teardown, like the file system.
 * {@link #shared(ExecutionEnvironmentProvider)} hands every runtime one caller-owned provider, which the stack never
 * closes ("whoever creates it closes it").
 */
public final class ExecutionEnvironmentSpec {

    /** Default limit on one skill directory's staged size: 50 MB. */
    public static final long DEFAULT_MAX_STAGED_BYTES = 50L * 1024 * 1024;

    private final Function<AgentRuntimeId, ExecutionEnvironmentProvider> factory;
    private final boolean callerOwned;
    private final long maxStagedBytes;
    private final boolean controlWritable;
    private final boolean contentSearch;

    private ExecutionEnvironmentSpec(Builder builder) {
        this.factory = builder.factory;
        this.callerOwned = builder.callerOwned;
        if (builder.maxStagedBytes < 0) {
            throw new IllegalArgumentException("maxStagedBytes must be >= 0, got: " + builder.maxStagedBytes);
        }
        this.maxStagedBytes = builder.maxStagedBytes;
        this.controlWritable = builder.controlWritable;
        this.contentSearch = builder.contentSearch;
    }

    /** @return the default: a local provider per runtime */
    public static ExecutionEnvironmentSpec defaults() {
        return builder().build();
    }

    /**
     * A caller-supplied provider per runtime.
     *
     * @param factory
     *            maps a runtime id to its provider (must not be null; must not return null)
     * @return the spec
     */
    public static ExecutionEnvironmentSpec factory(Function<AgentRuntimeId, ExecutionEnvironmentProvider> factory) {
        return builder().factory(Objects.requireNonNull(factory, "factory must not be null")).build();
    }

    /**
     * One caller-owned provider for every runtime. The stack uses it and never closes it.
     *
     * @param provider
     *            the provider (must not be null)
     * @return the spec
     */
    public static ExecutionEnvironmentSpec shared(ExecutionEnvironmentProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        final Builder builder = builder().factory(id -> provider);
        builder.callerOwned = true;
        return builder.build();
    }

    /**
     * Whether the providers come from the caller and stay the caller's to close ({@link #shared}).
     *
     * @return {@code true} if the stack must not close the providers
     */
    public boolean isCallerOwned() {
        return callerOwned;
    }

    /** @return the caller's provider factory, or empty for the local default */
    public Optional<Function<AgentRuntimeId, ExecutionEnvironmentProvider>> getFactory() {
        return Optional.ofNullable(factory);
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

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ExecutionEnvironmentSpec{factory=" + (factory != null) + ", maxStagedBytes=" + maxStagedBytes
                + ", controlWritable=" + controlWritable + ", contentSearch=" + contentSearch + '}';
    }

    /** Builder for {@link ExecutionEnvironmentSpec}. */
    public static final class Builder {
        private Function<AgentRuntimeId, ExecutionEnvironmentProvider> factory;
        private boolean callerOwned;
        private long maxStagedBytes = DEFAULT_MAX_STAGED_BYTES;
        private boolean controlWritable;
        private boolean contentSearch = true;

        private Builder() {
        }

        /**
         * @param factory
         *            a caller-supplied provider per runtime, or null for the local default
         * @return this builder
         */
        public Builder factory(Function<AgentRuntimeId, ExecutionEnvironmentProvider> factory) {
            this.factory = factory;
            return this;
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

        /** @return the spec */
        public ExecutionEnvironmentSpec build() {
            return new ExecutionEnvironmentSpec(this);
        }
    }
}
